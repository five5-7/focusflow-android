package com.sakata.focusflow.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TaskEntity::class,
        TaskEventEntity::class,
        PlanEntity::class,
        RecurrenceRuleEntity::class,
        TaskOccurrenceEntity::class,
        ActivitySessionEntity::class,
        CourseEntity::class,
        CourseMeetingRuleEntity::class,
        MigrationStateEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class FocusFlowDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun taskEventDao(): TaskEventDao
    abstract fun planDao(): PlanDao
    abstract fun recurrenceRuleDao(): RecurrenceRuleDao
    abstract fun taskOccurrenceDao(): TaskOccurrenceDao
    abstract fun activitySessionDao(): ActivitySessionDao
    abstract fun courseDao(): CourseDao
    abstract fun courseMeetingRuleDao(): CourseMeetingRuleDao
    abstract fun migrationStateDao(): MigrationStateDao

    companion object {
        const val FILE_NAME = "focusflow.db"

        fun create(context: Context): FocusFlowDatabase = Room.databaseBuilder(
            context.applicationContext,
            FocusFlowDatabase::class.java,
            FILE_NAME
        ).build()
    }
}
