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
}

@Dao
interface TaskEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAll(events: List<TaskEventEntity>)

    @Query("SELECT COUNT(*) FROM task_events")
    fun count(): Int

    @Query("SELECT id FROM task_events ORDER BY id")
    fun allIds(): List<Long>
}

@Dao
interface PlanDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAll(plans: List<PlanEntity>)

    @Query("SELECT COUNT(*) FROM plans")
    fun count(): Int

    @Query("SELECT id FROM plans ORDER BY id")
    fun allIds(): List<Long>
}

@Dao
interface MigrationStateDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(state: MigrationStateEntity)

    @Query("SELECT * FROM migration_states WHERE migration_key = :key")
    fun find(key: String): MigrationStateEntity?
}
