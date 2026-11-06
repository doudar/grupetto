package com.spop.poverlay

import android.content.SharedPreferences
import androidx.core.content.edit

/** One migration notice, recorded before any component creates a serial for a new install. */
class SerialNameUpgradeNotice(private val preferences: SharedPreferences) {
    private companion object {
        const val PendingKey = "serialNameUpgradeNoticeV1Pending"
    }

    fun initialize(hasExistingSerial: Boolean) {
        // A boot receiver may start the app before its first visible activity. Keep an
        // upgrade pending until the user dismisses it, and remember fresh installs too.
        if (!preferences.contains(PendingKey)) {
            preferences.edit { putBoolean(PendingKey, hasExistingSerial) }
        }
    }

    val shouldShow: Boolean
        get() = preferences.getBoolean(PendingKey, false)

    fun dismiss() {
        preferences.edit { putBoolean(PendingKey, false) }
    }
}
