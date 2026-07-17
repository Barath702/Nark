package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "map_notifications")
data class MapNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val packageName: String,
    val distance: String = "",
    val street: String = "",
    val baseStructure: String = "",
    val action: String = "",
    val icon: String = ""
)
