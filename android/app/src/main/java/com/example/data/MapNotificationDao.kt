package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MapNotificationDao {
    @Query("SELECT * FROM map_notifications ORDER BY timestamp DESC")
    fun getAllNotifications(): Flow<List<MapNotification>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: MapNotification)

    @Query("DELETE FROM map_notifications")
    suspend fun clearAllNotifications()
}
