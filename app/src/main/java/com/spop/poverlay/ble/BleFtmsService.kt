package com.spop.poverlay.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.spop.poverlay.MainActivity
import timber.log.Timber

/**
 * BLE Service - Android Service that manages BLE operations via BleServerManager
 */
class BleFtmsService : Service(), LifecycleOwner {
    
    companion object {
        const val ACTION_START_FTMS = "com.spop.poverlay.START_FTMS"
        const val ACTION_STOP_FTMS = "com.spop.poverlay.STOP_FTMS"
        const val ACTION_TOGGLE_FTMS = "com.spop.poverlay.TOGGLE_FTMS"
        
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "ftms_service_channel"
    }
    
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private var bleServerManager: BleServerManager? = null
    
    override fun getLifecycle(): LifecycleRegistry = lifecycleRegistry
    
    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.CREATED
        
        Timber.i("BleFtmsService created")
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.STARTED
        
        when (intent?.action) {
            ACTION_START_FTMS -> startFtmsService()
            ACTION_STOP_FTMS -> stopFtmsService()
            ACTION_TOGGLE_FTMS -> toggleFtmsService()
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
        stopFtmsService()
        super.onDestroy()
        Timber.i("BleFtmsService destroyed")
    }
    
    private fun startFtmsService() {
        Timber.i("Starting FTMS service")
        
        if (bleServerManager == null) {
            bleServerManager = BleServerManager(
                context = this,
                lifecycleOwner = this,
                deviceName = "Grupetto FTMS"
            )
        }
        
        bleServerManager?.initialize()
        bleServerManager?.startServer()
        
        // Start foreground service
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }
    
    private fun stopFtmsService() {
        Timber.i("Stopping FTMS service")
        
        bleServerManager?.stopServer()
        bleServerManager = null
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun toggleFtmsService() {
        if (bleServerManager == null) {
            startFtmsService()
        } else {
            stopFtmsService()
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Grupetto BLE Service")
            .setContentText("Broadcasting FTMS data via Bluetooth")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "FTMS Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Bluetooth Low Energy FTMS service"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }
}
