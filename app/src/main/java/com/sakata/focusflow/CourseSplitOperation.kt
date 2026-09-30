package com.sakata.focusflow

import android.content.Context
import com.sakata.focusflow.data.CoreDataReadResult
import com.sakata.focusflow.data.CoreDataRepository
import org.json.JSONArray
import org.json.JSONObject

/** Journaled edit of this meeting from a selected date onwards. */
internal object CourseSplitOperation {
    private const val FILE = "course_split_journal"
    private const val KEY = "pending"

    internal enum class Outcome { APPLIED, REJECTED, STALE, WRITE_FAILED, RECOVERY_PENDING }

    private data class Journal(
        val originalId: Long,
        val successorId: Long,
        val boundary: Long,
        val reminderEnabled: Boolean?,
        val futureLocations: Map<Long, String>
    ) {
        fun encode() = JSONObject().apply {
            put("originalId", originalId)
            put("successorId", successorId)
            put("boundary", boundary)
            put("reminderEnabled", reminderEnabled ?: JSONObject.NULL)
            put("futureLocations", JSONArray().apply {
                futureLocations.toSortedMap().forEach { (day, place) ->
                    put(JSONObject().put("day", day).put("place", place))
                }
            })
        }.toString()
    }

    @Synchronized fun recover(context: Context, repository: CoreDataRepository): Boolean {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = runCatching { prefs.getString(KEY, null) }.getOrElse { return false } ?: return true
        val journal = decode(raw) ?: return false
        val courses = (repository.read() as? CoreDataReadResult.Ready)?.snapshot?.courses ?: return false
        val original = courses.singleOrNull { it.id == journal.originalId } ?: return false
        val successor = courses.singleOrNull { it.id == journal.successorId }
        if (successor == null) {
            // The course CAS did not commit; no preference migration was attempted.
            return if (original.effectiveUntilEpochDay != journal.boundary - 1) {
                prefs.edit().remove(KEY).commit()
            } else false
        }
        if (original.effectiveUntilEpochDay != journal.boundary - 1 ||
            successor.effectiveFromEpochDay != journal.boundary) return false
        if (!CourseLocationOverrides.applySplit(context, journal.originalId, journal.successorId,
                journal.futureLocations)) return false
        if (!CourseReminders.applySplit(context, journal.successorId, journal.reminderEnabled)) return false
        return prefs.edit().remove(KEY).commit()
    }

    @Synchronized fun apply(context: Context, repository: CoreDataRepository, expectedCourses: List<Course>,
        original: Course, edited: Course, boundary: Long): Outcome {
        if (!CourseMergeOperation.recover(context, repository) || !recover(context, repository))
            return Outcome.RECOVERY_PENDING
        val current = (repository.read() as? CoreDataReadResult.Ready)?.snapshot?.courses ?: return Outcome.WRITE_FAILED
        if (current != expectedCourses || current.singleOrNull { it.id == original.id } != original ||
            edited.id != original.id) return Outcome.STALE
        if (boundary <= 0 || original.needsConfirmation) return Outcome.REJECTED
        val planned = CourseEditPlans.planCourseSplit(original, boundary, original.id)
            as? CourseEditPlans.CourseSplitPlan.Applied ?: return Outcome.REJECTED
        val successor = edited.copy(id = generateSequence(::newItemId).first { id ->
            id > 0 && current.none { it.id == id }
        }, effectiveFromEpochDay = boundary, effectiveUntilEpochDay = original.effectiveUntilEpochDay)
        val successorWithParent = if (successor.title.trim() == original.title.trim()) successor
            else successor.copy(courseId = successor.id)
        if (successorWithParent.title.isBlank() || successorWithParent.weekday !in 1..7 ||
            successorWithParent.startPeriod !in 1..20 || successorWithParent.endPeriod !in successorWithParent.startPeriod..20)
            return Outcome.REJECTED
        if (!CourseLocationOverrides.canSafelyMove(context, listOf(original.id), boundary) ||
            !CourseReminders.canSafelyMerge(context, listOf(original.id)))
            return Outcome.REJECTED
        val future = CourseLocationOverrides.snapshot(context, original.id).filterKeys { it >= boundary }
        if (future.values.any { it.length > 100 }) return Outcome.REJECTED
        val reminder = CourseReminders.load(context).overrides[original.id]
        val journal = Journal(original.id, successorWithParent.id, boundary, reminder, future)
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.edit().putString(KEY, journal.encode()).commit()) return Outcome.WRITE_FAILED
        val updated = current.flatMap { course ->
            if (course.id == original.id) listOf(planned.original, successorWithParent) else listOf(course)
        }
        if (!repository.replaceCourses(updated, current).applied) {
            prefs.edit().remove(KEY).commit()
            return Outcome.WRITE_FAILED
        }
        return if (recover(context, repository)) Outcome.APPLIED else Outcome.RECOVERY_PENDING
    }

    private fun decode(raw: String): Journal? = runCatching {
        val json = JSONObject(raw)
        val original = json.getLong("originalId")
        val successor = json.getLong("successorId")
        val boundary = json.getLong("boundary")
        val array = json.getJSONArray("futureLocations")
        val future = (0 until array.length()).associate { index ->
            array.getJSONObject(index).let { it.getLong("day") to it.getString("place") }
        }
        require(original > 0 && successor > 0 && original != successor && boundary > 0 &&
            boundary > Long.MIN_VALUE + 1 && future.keys.all { it >= boundary } &&
            future.values.all { it.isNotBlank() && it.length <= 100 } && future.size == array.length())
        Journal(original, successor, boundary,
            if (json.isNull("reminderEnabled")) null else json.getBoolean("reminderEnabled"), future)
    }.getOrNull()
}
