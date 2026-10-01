package com.sakata.focusflow

import android.content.Context
import com.sakata.focusflow.data.CoreDataReadResult
import com.sakata.focusflow.data.CoreDataRepository
import com.sakata.focusflow.data.withCourseWriteLock
import com.sakata.focusflow.data.Stage7Inverse
import com.sakata.focusflow.data.CoursePreferenceSnapshot
import com.sakata.focusflow.data.Stage7CommitGuard
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

    /**
     * Runs under the shared Legacy course write lock (see [CourseMergeOperation.recover]): the
     * journal read, course read and preference migration are serialized with every other course
     * writer, and the per-object `@Synchronized` monitors are gone so merge and split cannot acquire
     * each other's monitors in opposite orders.
     */
    fun recover(context: Context, repository: CoreDataRepository): Boolean = repository.withCourseWriteLock {
        if (StorageProtection.readOnly || Stage7CommitGuard.uncertain) return@withCourseWriteLock false
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = runCatching { prefs.getString(KEY, null) }.getOrElse { return@withCourseWriteLock false }
            ?: return@withCourseWriteLock true
        val journal = decode(raw) ?: return@withCourseWriteLock false
        val courses = (repository.read() as? CoreDataReadResult.Ready)?.snapshot?.courses
            ?: return@withCourseWriteLock false
        val original = courses.singleOrNull { it.id == journal.originalId } ?: return@withCourseWriteLock false
        val successor = courses.singleOrNull { it.id == journal.successorId }
        if (successor == null) {
            // The course CAS did not commit; no preference migration was attempted.
            return@withCourseWriteLock if (original.effectiveUntilEpochDay != journal.boundary - 1) {
                prefs.edit().remove(KEY).commit()
            } else {
                false
            }
        }
        if (original.effectiveUntilEpochDay != journal.boundary - 1 ||
            successor.effectiveFromEpochDay != journal.boundary) return@withCourseWriteLock false
        if (!CourseLocationOverrides.applySplit(context, journal.originalId, journal.successorId,
                journal.futureLocations)) return@withCourseWriteLock false
        if (!CourseReminders.applySplit(context, journal.successorId, journal.reminderEnabled)) {
            return@withCourseWriteLock false
        }
        prefs.edit().remove(KEY).commit()
    }

    fun apply(context: Context, repository: CoreDataRepository, expectedCourses: List<Course>,
        original: Course, edited: Course, boundary: Long): Outcome = repository.withCourseWriteLock {
        if (StorageProtection.readOnly || Stage7CommitGuard.uncertain) return@withCourseWriteLock Outcome.WRITE_FAILED
        if (!CourseMergeOperation.recover(context, repository) || !recover(context, repository))
            return@withCourseWriteLock Outcome.RECOVERY_PENDING
        val current = (repository.read() as? CoreDataReadResult.Ready)?.snapshot?.courses
            ?: return@withCourseWriteLock Outcome.WRITE_FAILED
        if (current != expectedCourses || current.singleOrNull { it.id == original.id } != original ||
            edited.id != original.id) return@withCourseWriteLock Outcome.STALE
        if (boundary <= 0 || original.needsConfirmation) return@withCourseWriteLock Outcome.REJECTED
        val planned = CourseEditPlans.planCourseSplit(original, boundary, original.id)
            as? CourseEditPlans.CourseSplitPlan.Applied ?: return@withCourseWriteLock Outcome.REJECTED
        val successor = edited.copy(id = generateSequence(::newItemId).first { id ->
            id > 0 && current.none { it.id == id }
        }, effectiveFromEpochDay = boundary, effectiveUntilEpochDay = original.effectiveUntilEpochDay)
        val successorWithParent = if (successor.title.trim() == original.title.trim()) successor
            else successor.copy(courseId = successor.id)
        if (successorWithParent.title.isBlank() || successorWithParent.weekday !in 1..7 ||
            successorWithParent.startPeriod !in 1..20 || successorWithParent.endPeriod !in successorWithParent.startPeriod..20)
            return@withCourseWriteLock Outcome.REJECTED
        if (!CourseLocationOverrides.canSafelyMove(context, listOf(original.id), boundary) ||
            !CourseReminders.canSafelyMerge(context, listOf(original.id)))
            return@withCourseWriteLock Outcome.REJECTED
        val future = CourseLocationOverrides.snapshot(context, original.id).filterKeys { it >= boundary }
        if (future.values.any { it.length > 100 }) return@withCourseWriteLock Outcome.REJECTED
        val reminder = CourseReminders.load(context).overrides[original.id]
        val selectedIds = setOf(original.id,successorWithParent.id)
        val captured = runCatching { CoursePreferenceSnapshot.capture(context,selectedIds) }.getOrElse { return@withCourseWriteLock Outcome.REJECTED }
        if(captured.overrides[successorWithParent.id]!=null || captured.watermarks[successorWithParent.id]!=null || captured.locations[successorWithParent.id].orEmpty().isNotEmpty()) return@withCourseWriteLock Outcome.REJECTED
        val afterPreferences = captured.copy(overrides=captured.overrides + (successorWithParent.id to reminder),
            locations=mapOf(original.id to captured.locations[original.id].orEmpty().filterKeys { it<boundary },successorWithParent.id to future))
        val journal = Journal(original.id, successorWithParent.id, boundary, reminder, future)
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.edit().putString(KEY, journal.encode()).commit()) {
            return@withCourseWriteLock Outcome.WRITE_FAILED
        }
        val updated = current.flatMap { course ->
            if (course.id == original.id) listOf(planned.original, successorWithParent) else listOf(course)
        }
        if (!Stage7Inverse.commitCourseEdit(context,repository,current,updated,"course_split_inverse",captured,afterPreferences).applied) {
            return@withCourseWriteLock Outcome.WRITE_FAILED
        }
        if (recover(context, repository)) Outcome.APPLIED else Outcome.RECOVERY_PENDING
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
