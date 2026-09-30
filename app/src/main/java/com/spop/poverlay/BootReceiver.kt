package com.spop.poverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.spop.poverlay.overlay.OverlayService
import timber.log.Timber

class BootReceiver internal constructor(
    private val canDrawOverlay: (Context) -> Boolean,
    private val startOverlay: (Context) -> Unit
) : BroadcastReceiver() {
    constructor() : this(
        { Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(it) },
        { ContextCompat.startForegroundService(it, Intent(it, OverlayService::class.java)) }
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(ConfigurationRepository.SharedPrefsName, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ConfigurationRepository.Preferences.AutoStartOnBoot.key, false)) {
            return
        }

        if (!canDrawOverlay(context)) {
            Timber.w("Boot: overlay permission not granted, skipping auto-start")
            return
        }

        Timber.i("Boot: starting overlay service")
        try {
            startOverlay(context)
        } catch (e: IllegalStateException) {
            Timber.w(e, "Boot: foreground service start is restricted")
        } catch (e: SecurityException) {
            Timber.w(e, "Boot: required service permission is unavailable")
        }
    }
}
