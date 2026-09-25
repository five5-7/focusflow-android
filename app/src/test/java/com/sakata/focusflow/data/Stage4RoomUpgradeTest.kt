package com.sakata.focusflow.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Stage4RoomUpgradeTest {
    @Test fun `existing v1 task database opens as v4 without dropping task or plan tables`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "stage4-upgrade-test.db"
        context.deleteDatabase(name)
        val schemaFile = listOf(
            File("schemas/com.sakata.focusflow.data.FocusFlowDatabase/1.json"),
            File("app/schemas/com.sakata.focusflow.data.FocusFlowDatabase/1.json")
        ).first { it.isFile }
        val definition = JSONObject(schemaFile.readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { sqlite ->
            val entities = definition.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    val index = indices.getJSONObject(j)
                    sqlite.execSQL(index.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
                }
            }
            val setup = definition.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
            sqlite.execSQL("INSERT INTO tasks (id,source_order,title,detail,legacy_kind,status,done,day_only,completion_level,duration_minutes,reschedule_count,priority,capture_route,source_detail,next_action) VALUES (77,0,'原任务','备注','任务','unscheduled',0,0,'',30,0,'mid','inbox','','')")
            insertOldPlan(sqlite)
            sqlite.version = 1
        }
        val database = Room.databaseBuilder(context, FocusFlowDatabase::class.java, name)
            .addMigrations(FocusFlowDatabase.MIGRATION_1_2, FocusFlowDatabase.MIGRATION_2_3,
                FocusFlowDatabase.MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            val item = database.taskDao().all().single().toLegacy()
            assertEquals(77L, item.id)
            assertEquals("原任务", item.title)
            assertNull(item.dueAt)
            assertTrue(item.checklist.isEmpty())
            assertEquals("near", item.planBucket)
            assertFalse(item.planFocus)
            assertEquals("", item.repeatFrequency)
            assertNull(item.repeatTemplateId)
            val plan = database.planDao().all().single().toLegacy()
            assertEquals(8L, plan.id)
            assertEquals("原计划", plan.title)
            assertNull(plan.deadlineAt)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun `existing v2 plan upgrades to optional deadline without data loss`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "stage4-v2-upgrade-test.db"
        context.deleteDatabase(name)
        val schemaFile = listOf(
            File("schemas/com.sakata.focusflow.data.FocusFlowDatabase/2.json"),
            File("app/schemas/com.sakata.focusflow.data.FocusFlowDatabase/2.json")
        ).first { it.isFile }
        val definition = JSONObject(schemaFile.readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { sqlite ->
            val entities = definition.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    val index = indices.getJSONObject(j)
                    sqlite.execSQL(index.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
                }
            }
            val setup = definition.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
            insertOldPlan(sqlite)
            sqlite.version = 2
        }
        val database = Room.databaseBuilder(context, FocusFlowDatabase::class.java, name)
            .addMigrations(FocusFlowDatabase.MIGRATION_2_3, FocusFlowDatabase.MIGRATION_3_4)
            .allowMainThreadQueries().build()
        try {
            val plan = database.planDao().all().single().toLegacy()
            assertEquals(8L, plan.id)
            assertEquals("原计划", plan.title)
            assertNull(plan.deadlineAt)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun `existing v3 task upgrades to empty recurrence without replacing its id`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "stage4-v3-upgrade-test.db"
        context.deleteDatabase(name)
        val schemaFile = listOf(
            File("schemas/com.sakata.focusflow.data.FocusFlowDatabase/3.json"),
            File("app/schemas/com.sakata.focusflow.data.FocusFlowDatabase/3.json")
        ).first { it.isFile }
        val definition = JSONObject(schemaFile.readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { sqlite ->
            val entities = definition.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    val index = indices.getJSONObject(j)
                    sqlite.execSQL(index.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
                }
            }
            val setup = definition.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
            sqlite.execSQL("INSERT INTO tasks (id,source_order,title,detail,legacy_kind,status,done,day_only,completion_level,duration_minutes,reschedule_count,priority,capture_route,source_detail,next_action) VALUES (77,0,'原任务','备注','任务','unscheduled',0,0,'',30,0,'mid','inbox','','')")
            sqlite.version = 3
        }
        val database = Room.databaseBuilder(context, FocusFlowDatabase::class.java, name)
            .addMigrations(FocusFlowDatabase.MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            val item = database.taskDao().all().single().toLegacy()
            assertEquals(77L, item.id)
            assertEquals("原任务", item.title)
            assertEquals("", item.repeatFrequency)
            assertNull(item.repeatTemplateId)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    private fun insertOldPlan(sqlite: SQLiteDatabase) {
        sqlite.execSQL("INSERT INTO plans (id,source_order,title,state,weekly_target,duration_minutes,metric_type,metric_target,minimum_version,resource_title,resource_unit,completed_this_week,minimum_completions_this_week,completion_week_key,desired_outcome,first_action,source_notes) VALUES (8,0,'原计划','active',0,30,'时长','','','','',0,0,0,'','','笔记')")
    }
}
