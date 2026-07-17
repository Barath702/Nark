package com.example.data

import kotlinx.coroutines.flow.Flow

class MapNotificationRepository(private val mapNotificationDao: MapNotificationDao) {
    val allNotifications: Flow<List<MapNotification>> = mapNotificationDao.getAllNotifications()

    suspend fun insert(notification: MapNotification) {
        mapNotificationDao.insertNotification(notification)
    }

    suspend fun clearAll() {
        mapNotificationDao.clearAllNotifications()
    }
}
