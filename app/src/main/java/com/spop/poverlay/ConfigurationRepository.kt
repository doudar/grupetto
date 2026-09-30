package com.spop.poverlay

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.spop.poverlay.overlay.OverlayLocation
import kotlinx.coroutines.flow.MutableStateFlow

class ConfigurationRepository(context: Context, lifecycleOwner: LifecycleOwner, preferencesName: String = SharedPrefsName) : AutoCloseable {

    enum class Preferences(val key: String) {
        ShowTimerWhenMinimized("showTimerWhenMinimized"),
        ShowShifters("showShifters"),
        ShifterInset("shifterInset"),
        BleTxEnabled("bleTxEnabled"),
        DirConEnabled("dirConEnabled"),
        BleFtmsDeviceName("bleFtmsDeviceName"),
        OverlayHorizontalOffset("overlayHorizontalOffset"),
        OverlayLocation("overlayLocation"),
        AntPlusTxEnabled("antPlusTxEnabled"),
        AutoStartOnBoot("autoStartOnBoot"),
        SerialNumber("serialNumber")
    }

    companion object {
        const val SharedPrefsName = "configuration"
        // This workaround is required since SharedPreferences
        // only stores weak references to objects
        val SharedPreferenceListeners =
            mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    }

    private val mutableShowTimerWhenMinimized = MutableStateFlow(true)
    val showShifters = MutableStateFlow(true)
    val shifterInset = MutableStateFlow(0f)
    private val mutableBleTxEnabled = MutableStateFlow(true)
    private val mutableDirConEnabled = MutableStateFlow(true)
    private val mutableBleFtmsDeviceName = MutableStateFlow("Grupetto FTMS")
    private val mutableAntPlusTxEnabled = MutableStateFlow(false)
    private val mutableAutoStartOnBoot = MutableStateFlow(false)
    private val mutableSerialNumber = MutableStateFlow("")

    val showTimerWhenMinimized = mutableShowTimerWhenMinimized
    val bleTxEnabled = mutableBleTxEnabled
    val dirConEnabled = mutableDirConEnabled
    val bleFtmsDeviceName = mutableBleFtmsDeviceName
    val antPlusTxEnabled = mutableAntPlusTxEnabled
    val autoStartOnBoot = mutableAutoStartOnBoot
    val serialNumber = mutableSerialNumber

    private val sharedPreferences: SharedPreferences

    // Must be kept as reference, unowned lambda would be garbage collected
    private fun createSharedPreferencesListener() =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updateFromSharedPrefs()
        }

    private val listener : SharedPreferences.OnSharedPreferenceChangeListener

    init {
        sharedPreferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
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

    fun setShowShifters(shown: Boolean) {
        showShifters.value = shown
        sharedPreferences.edit { putBoolean(Preferences.ShowShifters.key, shown) }
    }

    fun setShifterInset(inset: Float) {
        val normalized = com.spop.poverlay.overlay.normalizedShifterInset(inset)
        shifterInset.value = normalized
        sharedPreferences.edit { putFloat(Preferences.ShifterInset.key, normalized) }
    }

    fun setBleTxEnabled(enabled: Boolean) {
        mutableBleTxEnabled.value = enabled
        sharedPreferences.edit {
            putBoolean(Preferences.BleTxEnabled.key, enabled)
        }
    }

    fun setDirConEnabled(enabled: Boolean) {
        mutableDirConEnabled.value = enabled
        sharedPreferences.edit {
            putBoolean(Preferences.DirConEnabled.key, enabled)
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

    fun setAutoStartOnBoot(enabled: Boolean) {
        mutableAutoStartOnBoot.value = enabled
        sharedPreferences.edit {
            putBoolean(Preferences.AutoStartOnBoot.key, enabled)
        }
    }

    fun setSerialNumber(serial: String) {
        val normalized = serial.trim().uppercase()
        mutableSerialNumber.value = normalized
        sharedPreferences.edit {
            putString(Preferences.SerialNumber.key, normalized)
        }
    }

    val overlayHorizontalOffset: Float
        get() = sharedPreferences.getFloat(Preferences.OverlayHorizontalOffset.key, 0f)
            .takeIf { it.isFinite() } ?: 0f

    val overlayLocation: OverlayLocation
        get() = OverlayLocation.values().firstOrNull {
            it.name == sharedPreferences.getString(Preferences.OverlayLocation.key, null)
        } ?: OverlayLocation.Bottom

    fun setOverlayPosition(horizontalOffset: Float, location: OverlayLocation) {
        sharedPreferences.edit {
            putFloat(Preferences.OverlayHorizontalOffset.key, horizontalOffset)
            putString(Preferences.OverlayLocation.key, location.name)
        }
    }

    private fun generateSerialHex(): String {
        val value = kotlin.random.Random.nextInt(0x10000)
        return value.toString(16).padStart(4, '0').uppercase()
    }

    private fun updateFromSharedPrefs() {
        showShifters.value = sharedPreferences.getBoolean(Preferences.ShowShifters.key, true)
        shifterInset.value = com.spop.poverlay.overlay.normalizedShifterInset(
            sharedPreferences.getFloat(Preferences.ShifterInset.key, 0f))
        mutableShowTimerWhenMinimized.value =
            sharedPreferences
                .getBoolean(Preferences.ShowTimerWhenMinimized.key, true)

        mutableBleTxEnabled.value =
            sharedPreferences
                .getBoolean(Preferences.BleTxEnabled.key, true)

        mutableDirConEnabled.value =
            sharedPreferences
                .getBoolean(Preferences.DirConEnabled.key, true)

        mutableBleFtmsDeviceName.value =
            sharedPreferences
                .getString(Preferences.BleFtmsDeviceName.key, "Grupetto FTMS") ?: "Grupetto FTMS"

        mutableAntPlusTxEnabled.value =
            sharedPreferences
                .getBoolean(Preferences.AntPlusTxEnabled.key, false)

        mutableAutoStartOnBoot.value =
            sharedPreferences.getBoolean(Preferences.AutoStartOnBoot.key, false)

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
