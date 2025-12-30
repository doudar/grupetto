package com.spop.poverlay

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConfigurationRepository(context: Context, lifecycleOwner: LifecycleOwner) : AutoCloseable {

    enum class Preferences(val key: String) {
        ShowTimerWhenMinimized("showTimerWhenMinimized"),
        HeartRateDeviceAddress("heartRateDeviceAddress"),

        HeartRateDeviceName("heartRateDeviceName")
    }

    companion object {
        const val SharedPrefsName = "configuration"
        // This workaround is required since SharedPreferences
        // only stores weak references to objects
        val SharedPreferenceListeners =
            mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    }

    private val mutableShowTimerWhenMinimized = MutableStateFlow(true)
    val showTimerWhenMinimized = mutableShowTimerWhenMinimized.asStateFlow()

    private val mutableHeartRateDeviceName = MutableStateFlow<String?>(null)
    val heartRateDeviceName = mutableHeartRateDeviceName.asStateFlow()

    private val mutableHeartRateDeviceAddress = MutableStateFlow<String?>(null)
    val heartRateDeviceAddress = mutableHeartRateDeviceAddress.asStateFlow()

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
        sharedPreferences.edit {
            putBoolean(Preferences.ShowTimerWhenMinimized.key, isShown)
        }
    }

    fun setHeartRateDevice(address: String?, name: String?) {
        sharedPreferences.edit {
            putString(Preferences.HeartRateDeviceAddress.key, address)
            putString(Preferences.HeartRateDeviceName.key, name)
        }
    }

    private fun updateFromSharedPrefs() {
        mutableShowTimerWhenMinimized.value =
            sharedPreferences
                .getBoolean(Preferences.ShowTimerWhenMinimized.key, true)

        mutableHeartRateDeviceAddress.value =
            sharedPreferences.getString(Preferences.HeartRateDeviceAddress.key, null)

        mutableHeartRateDeviceName.value =
            sharedPreferences.getString(Preferences.HeartRateDeviceName.key, null)

    }

    override fun close() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        SharedPreferenceListeners.remove(listener)
    }
}