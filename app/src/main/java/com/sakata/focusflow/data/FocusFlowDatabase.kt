package com.sakata.focusflow.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 4,
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

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN due_at INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN checklist_json TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN plan_bucket TEXT NOT NULL DEFAULT 'near'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN plan_focus INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plans ADD COLUMN deadline_at INTEGER")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN repeat_frequency TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tasks ADD COLUMN repeat_start_day INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN repeat_minute INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE tasks ADD COLUMN repeat_template_id INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN repeat_occurrence_day INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN repeat_paused INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): FocusFlowDatabase = Room.databaseBuilder(
            context.applicationContext,
            FocusFlowDatabase::class.java,
            FILE_NAME
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}
