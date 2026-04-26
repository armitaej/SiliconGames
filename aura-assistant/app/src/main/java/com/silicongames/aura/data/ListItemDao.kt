package com.silicongames.aura.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ListItemDao {

    @Query("SELECT * FROM list_items WHERE listName = :listName ORDER BY timestamp DESC")
    suspend fun getItemsByList(listName: String): List<ListItem>

    @Query("SELECT * FROM list_items WHERE listName = :listName ORDER BY timestamp DESC")
    fun getItemsByListFlow(listName: String): Flow<List<ListItem>>

    @Query("SELECT * FROM list_items WHERE id = :id")
    suspend fun getById(id: Long): ListItem?

    @Query("SELECT DISTINCT listName FROM list_items ORDER BY listName")
    suspend fun getAllListNames(): List<String>

    @Query("SELECT DISTINCT listName FROM list_items ORDER BY listName")
    fun getAllListNamesFlow(): Flow<List<String>>

    @Insert
    suspend fun insert(item: ListItem): Long

    @Update
    suspend fun update(item: ListItem)

    @Delete
    suspend fun delete(item: ListItem)

    @Query("DELETE FROM list_items WHERE listName = :listName AND isChecked = 1")
    suspend fun deleteCheckedItems(listName: String)

    @Query("DELETE FROM list_items WHERE listName = :listName AND answer IS NOT NULL")
    suspend fun deleteResolvedItems(listName: String)

    @Query("DELETE FROM list_items WHERE listName = :listName")
    suspend fun deleteAllFromList(listName: String)
}
