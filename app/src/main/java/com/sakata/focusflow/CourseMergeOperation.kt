package com.sakata.focusflow

import android.content.Context
import com.sakata.focusflow.data.CoreDataReadResult
import com.sakata.focusflow.data.CoreDataRepository
import org.json.JSONArray
import org.json.JSONObject

/** Journaled manual merge of old single-meeting Course records. Never joins different meetings. */
internal object CourseMergeOperation {
    private const val FILE = "course_merge_journal"
    private const val KEY = "pending"

    internal enum class Outcome { APPLIED, REJECTED, STALE, WRITE_FAILED, RECOVERY_PENDING }

    private data class Journal(
        val survivorId: Long,
        val deletedIds: List<Long>,
        val reminderEnabled: Boolean?,
        val locations: Map<Long, String>
    ) {
        fun encode() = JSONObject().apply {
            put("survivorId", survivorId)
            put("deletedIds", JSONArray(deletedIds))
            put("reminderEnabled", reminderEnabled ?: JSONObject.NULL)
            put("locations", JSONArray().apply {
                locations.toSortedMap().forEach { (day, place) -> put(JSONObject().apply {
                    put("day", day); put("place", place)
                }) }
            })
        }.toString()
    }

    fun preview(context: Context, selected: List<Course>, preferredId: Long): CourseEditPlans.CourseMergePlan {
        val settings = CourseReminders.load(context)
        val snapshots = selected.map { course ->
            CourseEditPlans.CourseOverrideSnapshot(
                courseId = course.id,
                temporaryLocations = CourseLocationOverrides.snapshot(context, course.id),
                reminderEnabled = settings.overrides[course.id]
            )
        }
        if (snapshots.any { it.temporaryLocations.values.any { place -> place.length > 100 } }) {
            return CourseEditPlans.CourseMergePlan.Rejected(CourseEditPlans.MergeRejectReason.CORRUPT_OVERRIDES,
                "有本次地点超过当前长度限制，请先逐项核对，不能在合并时静默丢弃。")
        }
        return CourseEditPlans.planCourseMerge(selected, snapshots, preferredId)
    }

    /** Must be called before showing a new snapshot or scheduling a merged course. */
    @Synchronized fun recover(context: Context, repository: CoreDataRepository): Boolean {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = runCatching { prefs.getString(KEY, null) }.getOrElse { return false } ?: return true
        val journal = decode(raw) ?: return false // Corrupt journal is preserved for manual repair.
        val courses = (repository.read() as? CoreDataReadResult.Ready)?.snapshot?.courses ?: return false
        if (journal.deletedIds.all { id -> courses.any { it.id == id } }) {
            // The course write did not commit. Preferences have not been touched.
            return prefs.edit().remove(KEY).commit()
        }
        if (courses.none { it.id == journal.survivorId } || journal.deletedIds.any { id -> courses.any { it.id == id } }) {
            return false // Partial/unexpected course state: never guess which IDs to delete.
        }
        if (!CourseLocationOverrides.applyMerge(context, journal.survivorId, journal.deletedIds, journal.locations)) return false
        if (!CourseReminders.applyMerge(context, journal.survivorId, journal.deletedIds, journal.reminderEnabled)) return false
        return prefs.edit().remove(KEY).commit()
    }

    @Synchronized fun apply(
        context: Context, repository: CoreDataRepository, expectedCourses: List<Course>,
        selectedIds: Set<Long>, preferredId: Long, expectedPlan: CourseEditPlans.CourseMergePlan.Applied
    ): Outcome {
        if (!recover(context, repository)) return Outcome.RECOVERY_PENDING
        val current = (repository.read() as? CoreDataReadResult.Ready)?.snapshot?.courses ?: return Outcome.WRITE_FAILED
        if (current != expectedCourses || selectedIds.size < 2 || preferredId !in selectedIds) return Outcome.STALE
        val selected = current.filter { it.id in selectedIds }
        if (selected.size != selectedIds.size) return Outcome.STALE
        val plan = preview(context, selected, preferredId) as? CourseEditPlans.CourseMergePlan.Applied
            ?: return Outcome.REJECTED
        if (plan != expectedPlan) return Outcome.STALE
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val journal = Journal(plan.survivingId, plan.deletedCourseIds,
            plan.mergedReminderEnabled, plan.mergedTemporaryLocations)
        if (!prefs.edit().putString(KEY, journal.encode()).commit()) return Outcome.WRITE_FAILED
        val updated = current.mapNotNull { course ->
            when (course.id) {
                plan.survivingId -> plan.survivingCourse
                in selectedIds -> null
                else -> course
            }
        }
        if (!repository.replaceCourses(updated, current).applied) {
            // No preference migration has occurred; a failed journal clear is harmless on recovery.
            prefs.edit().remove(KEY).commit()
            return Outcome.WRITE_FAILED
        }
        return if (recover(context, repository)) Outcome.APPLIED else Outcome.RECOVERY_PENDING
    }

    private fun decode(raw: String): Journal? = runCatching {
        val json = JSONObject(raw)
        val survivor = json.getLong("survivorId")
        val ids = json.getJSONArray("deletedIds").let { array -> (0 until array.length()).map(array::getLong) }
        val locations = json.getJSONArray("locations").let { array ->
            (0 until array.length()).associate { index -> array.getJSONObject(index).let {
                it.getLong("day") to it.getString("place")
            } }
        }
        require(survivor > 0 && ids.isNotEmpty() && ids.distinct().size == ids.size &&
            ids.all { it > 0 && it != survivor } && locations.keys.all { it >= 0 } &&
            locations.values.all { it.isNotBlank() && it.length <= 100 })
        Journal(survivor, ids, if (json.isNull("reminderEnabled")) null else json.getBoolean("reminderEnabled"), locations)
    }.getOrNull()
}
