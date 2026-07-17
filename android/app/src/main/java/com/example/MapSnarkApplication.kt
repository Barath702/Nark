package com.example

import android.app.Application
import com.example.data.AppDatabase
import com.example.data.MapNotificationRepository

class MapSnarkApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { MapNotificationRepository(database.mapNotificationDao()) }

    override fun onCreate() {
        super.onCreate()
    }
}
