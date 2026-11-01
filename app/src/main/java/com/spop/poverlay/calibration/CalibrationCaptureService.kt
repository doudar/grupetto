package com.spop.poverlay.calibration

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import com.spop.poverlay.GrupettoApplication
import com.spop.poverlay.MainActivity
import com.spop.poverlay.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Capture has its own foreground lifetime; closing a dialog or ride overlay does not stop it. */
class CalibrationCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager get() = (application as GrupettoApplication).bikeCalibration
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(
            NotificationChannel("calibration", "Calibration capture", NotificationManager.IMPORTANCE_LOW))
        val notification = notification("Starting capture")
        if (Build.VERSION.SDK_INT >= 29) startForeground(2033, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(2033, notification)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "Grupetto:CalibrationCapture").apply {
            setReferenceCounted(false); acquire(15 * 60_000L)
        }
        scope.launch { while (isActive) { delay(10 * 60_000L); wakeLock?.acquire(15 * 60_000L) } }
        scope.launch {
            manager.state.map { "${it.sampleCount} efforts saved · Tap to view" }.distinctUntilChanged().collect {
                notifications.notify(2033, notification(it))
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!manager.state.value.enabled) manager.setEnabled(true)
        return START_NOT_STICKY // A process restart must never silently resume calibration.
    }
    override fun onDestroy() {
        if (manager.state.value.enabled) manager.setEnabled(false)
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
    private fun notification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("showCalibration", true)
        }
        val pending = PendingIntent.getActivity(this, 2033, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return NotificationCompat.Builder(this, "calibration").setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Grupetto · Collecting calibration").setContentText(text)
            .setOnlyAlertOnce(true).setOngoing(true).setContentIntent(pending).build()
    }
}
