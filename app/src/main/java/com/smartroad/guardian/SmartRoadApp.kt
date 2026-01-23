package com.smartroad.guardian

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.Configuration
import androidx.work.WorkManager

/**
 * SmartRoad Guardian Application
 * 
 * Main application class that handles:
 * - WorkManager initialization for background email sending
 * - Notification channel setup for detection service
 * - Global app configuration
 */
class SmartRoadApp : Application(), Configuration.Provider {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "detection_service"
        const val TAG = "SmartRoadApp"
        
        @Volatile
        private var INSTANCE: SmartRoadApp? = null
        
        fun getInstance(): SmartRoadApp {
            return INSTANCE ?: throw IllegalStateException("Application not initialized")
        }
    }

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        
        createNotificationChannels()
        initializeWorkManager()
    }

    /**
     * Create notification channels for Android O+
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Initialize WorkManager with custom configuration
     */
    private fun initializeWorkManager() {
        WorkManager.initialize(this, workManagerConfiguration)
    }

    /**
     * WorkManager configuration with logging
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
