package com.spop.poverlay

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow

class ConfigurationRepository(context: Context, lifecycleOwner: LifecycleOwner) : AutoCloseable {

    enum class Preferences(val key: String) {
        ShowTimerWhenMinimized("showTimerWhenMinimized"),
        BleTxEnabled("bleTxEnabled"),
        BleFtmsDeviceName("bleFtmsDeviceName"),
        SerialNumber("serialNumber"),
        ShowOverlay("showOverlay")
    }

    companion object {
        const val SharedPrefsName = "configuration"
        // This workaround is required since SharedPreferences
        // only stores weak references to objects
        val SharedPreferenceListeners =
            mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    }

    private val mutableShowTimerWhenMinimized = MutableStateFlow(true)
    private val mutableBleTxEnabled = MutableStateFlow(true)
    private val mutableBleFtmsDeviceName = MutableStateFlow("Grupetto FTMS")
    private val mutableSerialNumber = MutableStateFlow("")
    private val mutableShowOverlay = MutableStateFlow(true)

    val showTimerWhenMinimized = mutableShowTimerWhenMinimized
    val bleTxEnabled = mutableBleTxEnabled
    val bleFtmsDeviceName = mutableBleFtmsDeviceName
    val serialNumber = mutableSerialNumber
    val showOverlay = mutableShowOverlay

    private val sharedPreferences: SharedPreferences

    // Must be kept as reference, unowned lambda would be garbage collected
    private fun createSharedPreferencesListener() =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updateFromSharedPrefs()
        }

    private val listener : SharedPreferences.OnSharedPreferenceChangeListener

    init {
        sharedPreferences = context.getSharedPreferences(SharedPrefsName, Context.MODE_PRIVATE)
        updateFromSharedPrefs()

        listener = createSharedPreferencesListener()
        SharedPreferenceListeners.add(listener)
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver{
            override fun onStop(owner: LifecycleOwner) {
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

    fun setSerialNumber(serial: String) {
        val normalized = serial.trim().uppercase()
        mutableSerialNumber.value = normalized
        sharedPreferences.edit {
            putString(Preferences.SerialNumber.key, normalized)
        }
    }

    fun setShowOverlay(show: Boolean) {
        mutableShowOverlay.value = show
        sharedPreferences.edit {
            putBoolean(Preferences.ShowOverlay.key, show)
        }
    }

    private fun generateSerialHex(): String {
        val value = kotlin.random.Random.nextInt(0x10000)
        return value.toString(16).padStart(4, '0').uppercase()
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

        mutableShowOverlay.value =
            sharedPreferences
                .getBoolean(Preferences.ShowOverlay.key, true)

        // Ensure a serial number exists and keep it in memory
        val existingSerial = sharedPreferences.getString(Preferences.SerialNumber.key, null)
        val ensuredSerial = if (existingSerial.isNullOrEmpty()) {
            val sn = generateSerialHex()
            sharedPreferences.edit { putString(Preferences.SerialNumber.key, sn) }
            sn
        } else existingSerial
        mutableSerialNumber.value = ensuredSerial
    }

    override fun close() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        SharedPreferenceListeners.remove(listener)
    }
}