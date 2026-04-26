package com.silicongames.aura.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    @Query("SELECT * FROM reminders ORDER BY triggerTime ASC")
    suspend fun getAll(): List<ReminderItem>

    @Query("SELECT * FROM reminders ORDER BY triggerTime ASC")
    fun getAllFlow(): Flow<List<ReminderItem>>

    @Query("SELECT * FROM reminders WHERE isTriggered = 0 ORDER BY triggerTime ASC")
    suspend fun getUpcoming(): List<ReminderItem>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): ReminderItem?

    @Insert
    suspend fun insert(item: ReminderItem): Long

    @Update
    suspend fun update(item: ReminderItem)

    @Delete
    suspend fun delete(item: ReminderItem)

    @Query("DELETE FROM reminders WHERE isTriggered = 1")
    suspend fun deleteTriggered()
}
