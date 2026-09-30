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
        MigrationStateEntity::class,
        TrashGroupEntity::class,
        OperationRecordEntity::class
    ],
    version = 7,
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
    abstract fun trashGroupDao(): TrashGroupDao
    abstract fun operationRecordDao(): OperationRecordDao

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
                db.execSQL("ALTER TABLE tasks ADD COLUMN trashed_at INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN trash_snapshot TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activity_sessions ADD COLUMN task_id INTEGER")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `trash_groups` (`group_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `deleted_at` INTEGER NOT NULL, `expires_at` INTEGER NOT NULL, `state` TEXT NOT NULL, `members_json` TEXT NOT NULL, PRIMARY KEY(`group_id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trash_groups_deleted_at` ON `trash_groups` (`deleted_at`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `operation_records` (`operation_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `recorded_at` INTEGER NOT NULL, `state` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`operation_id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_operation_records_recorded_at` ON `operation_records` (`recorded_at`)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE course_meeting_rules ADD COLUMN external_school_year_code TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE course_meeting_rules ADD COLUMN external_term_code TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE course_meeting_rules ADD COLUMN external_selection_key_candidate TEXT NOT NULL DEFAULT ''")
            }
        }

        fun create(context: Context): FocusFlowDatabase = Room.databaseBuilder(
            context.applicationContext,
            FocusFlowDatabase::class.java,
            FILE_NAME
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build()
    }
}
