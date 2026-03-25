package com.spop.poverlay

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow

class ConfigurationRepository(context: Context, lifecycleOwner: LifecycleOwner) : AutoCloseable {

    enum class Preferences(val key: String) {
        ShowTimerWhenMinimized("showTimerWhenMinimized"),
        BleTxEnabled("bleTxEnabled"),
        BleFtmsDeviceName("bleFtmsDeviceName"),
        AntPlusTxEnabled("antPlusTxEnabled"),
        AntPlusDeviceName("antPlusDeviceName"),
        SerialNumber("serialNumber")
    }

    companion object {
        const val SharedPrefsName = "configuration"
        private const val LegacyBrokenAndroidId = "9774d56d682e549c"
        // This workaround is required since SharedPreferences
        // only stores weak references to objects
        val SharedPreferenceListeners =
            mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        fun generateDeviceSerialHex(context: Context): String {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            val source = if (!androidId.isNullOrBlank() && androidId != LegacyBrokenAndroidId) {
                androidId
            } else {
                "${Build.FINGERPRINT}:${context.packageName}"
            }
            val value = source.hashCode() and 0xFFFF
            return value.toString(16).padStart(4, '0').uppercase()
        }

        fun ensureSerialNumber(
            context: Context,
            preferences: SharedPreferences
        ): String {
            val key = Preferences.SerialNumber.key
            val existing = preferences.getString(key, null)
            if (!existing.isNullOrBlank()) {
                return existing
            }
            val serial = generateDeviceSerialHex(context)
            preferences.edit { putString(key, serial) }
            return serial
        }
    }

    private val appContext = context.applicationContext
    private val mutableShowTimerWhenMinimized = MutableStateFlow(true)
    private val mutableBleTxEnabled = MutableStateFlow(true)
    private val mutableBleFtmsDeviceName = MutableStateFlow("Grupetto FTMS")
    private val mutableAntPlusTxEnabled = MutableStateFlow(false)
    private val mutableAntPlusDeviceName = MutableStateFlow("Grupetto ANT+")
    private val mutableSerialNumber = MutableStateFlow("")

    val showTimerWhenMinimized = mutableShowTimerWhenMinimized
    val bleTxEnabled = mutableBleTxEnabled
    val bleFtmsDeviceName = mutableBleFtmsDeviceName
    val antPlusTxEnabled = mutableAntPlusTxEnabled
    val antPlusDeviceName = mutableAntPlusDeviceName
    val serialNumber = mutableSerialNumber

    private val sharedPreferences: SharedPreferences

    // Must be kept as reference, unowned lambda would be garbage collected
    private fun createSharedPreferencesListener() =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updateFromSharedPrefs()
        }

    private val listener : SharedPreferences.OnSharedPreferenceChangeListener

    init {
        sharedPreferences = appContext.getSharedPreferences(SharedPrefsName, Context.MODE_PRIVATE)
        updateFromSharedPrefs()

        listener = createSharedPreferencesListener()
        SharedPreferenceListeners.add(listener)
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        lifecycleOwner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                close()
            }
        })
    }

    fun setShowTimerWhenMinimized(isShown: Boolean) {
        mutableShowTimerWhenMinimized.value = isShown
        sharedPreferences.edit {
            putBoolean(Preferences.ShowTimerWhenMinimized.key, isShown)
        }
    }

    fun setBleTxEnabled(enabled: Boolean) {
        mutableBleTxEnabled.value = enabled
        sharedPreferences.edit {
            putBoolean(Preferences.BleTxEnabled.key, enabled)
        }
    }

    fun setBleFtmsDeviceName(name: String) {
        mutableBleFtmsDeviceName.value = name
        sharedPreferences.edit {
            putString(Preferences.BleFtmsDeviceName.key, name)
        }
    }

    fun setAntPlusTxEnabled(enabled: Boolean) {
        mutableAntPlusTxEnabled.value = enabled
        sharedPreferences.edit {
            putBoolean(Preferences.AntPlusTxEnabled.key, enabled)
        }
    }

    fun setAntPlusDeviceName(name: String) {
        mutableAntPlusDeviceName.value = name
        sharedPreferences.edit {
            putString(Preferences.AntPlusDeviceName.key, name)
        }
    }

    fun setSerialNumber(serial: String) {
        val normalized = serial.trim().uppercase()
        mutableSerialNumber.value = normalized
        sharedPreferences.edit {
            putString(Preferences.SerialNumber.key, normalized)
        }
    }

    private fun updateFromSharedPrefs() {
        mutableShowTimerWhenMinimized.value =
            sharedPreferences
                .getBoolean(Preferences.ShowTimerWhenMinimized.key, true)

        mutableBleTxEnabled.value =
            sharedPreferences
                .getBoolean(Preferences.BleTxEnabled.key, true)

        mutableBleFtmsDeviceName.value =
            sharedPreferences
                .getString(Preferences.BleFtmsDeviceName.key, "Grupetto FTMS") ?: "Grupetto FTMS"

        mutableAntPlusTxEnabled.value =
            sharedPreferences
                .getBoolean(Preferences.AntPlusTxEnabled.key, false)

        mutableAntPlusDeviceName.value =
            sharedPreferences
                .getString(Preferences.AntPlusDeviceName.key, "Grupetto ANT+") ?: "Grupetto ANT+"

        mutableSerialNumber.value = ensureSerialNumber(appContext, sharedPreferences)
    }

    override fun close() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        SharedPreferenceListeners.remove(listener)
    }
}
