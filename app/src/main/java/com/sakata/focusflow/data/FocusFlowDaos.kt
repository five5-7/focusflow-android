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
interface MigrationStateDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(state: MigrationStateEntity)

    @Query("SELECT * FROM migration_states WHERE migration_key = :key")
    fun find(key: String): MigrationStateEntity?

    @Query(
        """UPDATE migration_states
           SET task_count = :taskCount,
               task_event_count = :taskEventCount,
               plan_count = :planCount
           WHERE migration_key = :key"""
    )
    fun updateCounts(key: String, taskCount: Int, taskEventCount: Int, planCount: Int): Int
}
