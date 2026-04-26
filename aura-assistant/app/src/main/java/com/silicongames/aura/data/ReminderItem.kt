package com.silicongames.aura.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminders")
data class ReminderItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val triggerTime: Long,
    val createdTime: Long = System.currentTimeMillis(),
    val isTriggered: Boolean = false
)
