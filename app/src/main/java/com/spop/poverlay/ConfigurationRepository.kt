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
        bleEnabled("bleEnabled"),
        bleDeviceName("bleDeviceName")
    }

    companion object {
        const val SharedPrefsName = "configuration"
        // This workaround is required since SharedPreferences
        // only stores weak references to objects
        val SharedPreferenceListeners =
            mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    }

    private val mutableShowTimerWhenMinimized = MutableStateFlow(true)
    private val mutablebleEnabled = MutableStateFlow(false)
    private val mutablebleDeviceName = MutableStateFlow("Grupetto")

    val showTimerWhenMinimized = mutableShowTimerWhenMinimized
    val bleEnabled = mutablebleEnabled
    val bleDeviceName = mutablebleDeviceName

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

    fun setbleEnabled(enabled: Boolean) {
        mutablebleEnabled.value = enabled
        sharedPreferences.edit {
            putBoolean(Preferences.bleEnabled.key, enabled)
        }
    }

    fun setbleDeviceName(name: String) {
        mutablebleDeviceName.value = name
        sharedPreferences.edit {
            putString(Preferences.bleDeviceName.key, name)
        }
    }

    private fun updateFromSharedPrefs() {
        mutableShowTimerWhenMinimized.value =
            sharedPreferences
                .getBoolean(Preferences.ShowTimerWhenMinimized.key, true)

        mutablebleEnabled.value =
            sharedPreferences
                .getBoolean(Preferences.bleEnabled.key, false)

        mutablebleDeviceName.value =
            sharedPreferences
                .getString(Preferences.bleDeviceName.key, "Grupetto") ?: "Grupetto"
    }

    override fun close() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        SharedPreferenceListeners.remove(listener)
    }
}