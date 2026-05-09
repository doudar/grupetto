package com.spop.poverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.spop.poverlay.overlay.OverlayService
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(ConfigurationRepository.SharedPrefsName, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ConfigurationRepository.Preferences.AutoStartOnBoot.key, false)) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Timber.w("Boot: overlay permission not granted, skipping auto-start")
            return
        }

        Timber.i("Boot: starting overlay service")
        ContextCompat.startForegroundService(
            context,
            Intent(context, OverlayService::class.java)
        )
    }
}
