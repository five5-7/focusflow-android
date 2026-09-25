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
    @Test fun `existing v1 task database opens as v2 without dropping task or plan tables`() {
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
            sqlite.version = 1
        }
        val database = Room.databaseBuilder(context, FocusFlowDatabase::class.java, name)
            .addMigrations(FocusFlowDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            val item = database.taskDao().all().single().toLegacy()
            assertEquals(77L, item.id)
            assertEquals("原任务", item.title)
            assertNull(item.dueAt)
            assertTrue(item.checklist.isEmpty())
            assertEquals("near", item.planBucket)
            assertFalse(item.planFocus)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
