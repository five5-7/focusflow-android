package com.sakata.focusflow.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAll(tasks: List<TaskEntity>)

    @Query("SELECT COUNT(*) FROM tasks")
    fun count(): Int

    @Query("SELECT id FROM tasks ORDER BY id")
    fun allIds(): List<Long>

    @Query("SELECT * FROM tasks ORDER BY source_order, id")
    fun all(): List<TaskEntity>

    @Query("DELETE FROM tasks")
    fun deleteAll()
}

@Dao
interface TaskEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAll(events: List<TaskEventEntity>)

    @Query("SELECT COUNT(*) FROM task_events")
    fun count(): Int

    @Query("SELECT id FROM task_events ORDER BY id")
    fun allIds(): List<Long>

    @Query("SELECT * FROM task_events ORDER BY source_order, id")
    fun all(): List<TaskEventEntity>

    @Query("DELETE FROM task_events")
    fun deleteAll()
}

@Dao
interface PlanDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAll(plans: List<PlanEntity>)

    @Query("SELECT COUNT(*) FROM plans")
    fun count(): Int

    @Query("SELECT id FROM plans ORDER BY id")
    fun allIds(): List<Long>

    @Query("SELECT * FROM plans ORDER BY source_order, id")
    fun all(): List<PlanEntity>

    @Query("DELETE FROM plans")
    fun deleteAll()
}

@Dao
interface RecurrenceRuleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAll(rules: List<RecurrenceRuleEntity>)

    @Query("SELECT id FROM recurrence_rules ORDER BY id")
    fun allIds(): List<Long>

    @Query("SELECT * FROM recurrence_rules ORDER BY id")
    fun all(): List<RecurrenceRuleEntity>
}

@Dao
interface TaskOccurrenceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAll(occurrences: List<TaskOccurrenceEntity>)

    @Query("SELECT id FROM task_occurrences ORDER BY id")
    fun allIds(): List<Long>

    @Query("SELECT * FROM task_occurrences ORDER BY occurrence_epoch_day, id")
    fun all(): List<TaskOccurrenceEntity>
}

@Dao
interface ActivitySessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAll(sessions: List<ActivitySessionEntity>)

    @Query("SELECT id FROM activity_sessions ORDER BY id")
    fun allIds(): List<Long>

    @Query("SELECT * FROM activity_sessions ORDER BY source_order, id")
    fun all(): List<ActivitySessionEntity>

    @Query("DELETE FROM activity_sessions")
    fun deleteAll()
}

@Dao
interface CourseDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAll(courses: List<CourseEntity>)

    @Query("DELETE FROM courses")
    fun deleteAll()

    @Query("SELECT id FROM courses ORDER BY id")
    fun allIds(): List<Long>

    @Query("SELECT * FROM courses ORDER BY source_order, id")
    fun all(): List<CourseEntity>
}

@Dao
interface CourseMeetingRuleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAll(rules: List<CourseMeetingRuleEntity>)

    @Query("DELETE FROM course_meeting_rules")
    fun deleteAll()

    @Query("SELECT id FROM course_meeting_rules ORDER BY id")
    fun allIds(): List<Long>

    @Query("SELECT * FROM course_meeting_rules ORDER BY source_order, id")
    fun all(): List<CourseMeetingRuleEntity>
}

@Dao
interface MigrationStateDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(state: MigrationStateEntity)

    @Query("SELECT * FROM migration_states WHERE migration_key = :key")
    fun find(key: String): MigrationStateEntity?

    @Query(
        """UPDATE migration_states
           SET task_count = :taskCount,
               task_event_count = :taskEventCount,
               plan_count = :planCount,
               activity_session_count = :activitySessionCount,
               course_count = :courseCount,
               course_meeting_rule_count = :courseMeetingRuleCount
           WHERE migration_key = :key"""
    )
    fun updateCounts(
        key: String,
        taskCount: Int,
        taskEventCount: Int,
        planCount: Int,
        activitySessionCount: Int,
        courseCount: Int,
        courseMeetingRuleCount: Int
    ): Int
}
