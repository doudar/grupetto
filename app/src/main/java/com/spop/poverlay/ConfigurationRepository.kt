package com.spop.poverlay

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.spop.poverlay.sensor.heartrate.HeartRateDevice
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

class ConfigurationRepository(context: Context, lifecycleOwner: LifecycleOwner) : AutoCloseable {

    enum class Preferences(val key: String) {
        ShowTimerWhenMinimized("showTimerWhenMinimized"),
        BleTxEnabled("bleTxEnabled"),
        BleFtmsDeviceName("bleFtmsDeviceName"),
        SerialNumber("serialNumber"),
        ShowHeartRate("showHeartRate"),
        HeartRateDevices("heartRateDevices")
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
    private val mutableShowHeartRate = MutableStateFlow(false)
    private val mutableHeartRateDevices = MutableStateFlow<List<HeartRateDevice>>(emptyList())

    val showTimerWhenMinimized = mutableShowTimerWhenMinimized
    val bleTxEnabled = mutableBleTxEnabled
    val bleFtmsDeviceName = mutableBleFtmsDeviceName
    val serialNumber = mutableSerialNumber
    val showHeartRate = mutableShowHeartRate
    val heartRateDevices = mutableHeartRateDevices

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

    fun setShowHeartRate(show: Boolean) {
        mutableShowHeartRate.value = show
        sharedPreferences.edit {
            putBoolean(Preferences.ShowHeartRate.key, show)
        }
    }

    fun upsertHeartRateDevice(device: HeartRateDevice) {
        val updated = mutableHeartRateDevices.value
            .filterNot { it.address.equals(device.address, true) } + device
        mutableHeartRateDevices.value = updated
        sharedPreferences.edit {
            putString(Preferences.HeartRateDevices.key, encodeHeartRateDevices(updated))
        }
    }

    fun removeHeartRateDevice(address: String) {
        val updated = mutableHeartRateDevices.value
            .filterNot { it.address.equals(address, true) }
        mutableHeartRateDevices.value = updated
        sharedPreferences.edit {
            putString(Preferences.HeartRateDevices.key, encodeHeartRateDevices(updated))
        }
    }

    fun setSerialNumber(serial: String) {
        val normalized = serial.trim().uppercase()
        mutableSerialNumber.value = normalized
        sharedPreferences.edit {
            putString(Preferences.SerialNumber.key, normalized)
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

        // Ensure a serial number exists and keep it in memory
        val existingSerial = sharedPreferences.getString(Preferences.SerialNumber.key, null)
        val ensuredSerial = if (existingSerial.isNullOrEmpty()) {
            val sn = generateSerialHex()
            sharedPreferences.edit { putString(Preferences.SerialNumber.key, sn) }
            sn
        } else existingSerial
        mutableSerialNumber.value = ensuredSerial

        mutableShowHeartRate.value =
            sharedPreferences
                .getBoolean(Preferences.ShowHeartRate.key, false)

        mutableHeartRateDevices.value =
            decodeHeartRateDevices(
                sharedPreferences.getString(Preferences.HeartRateDevices.key, null)
            )
    }

    private fun encodeHeartRateDevices(devices: List<HeartRateDevice>): String {
        val array = JSONArray()
        devices.forEach { device ->
            val obj = JSONObject()
            if (device.name == null) {
                obj.put("name", JSONObject.NULL)
            } else {
                obj.put("name", device.name)
            }
            obj.put("address", device.address)
            array.put(obj)
        }
        return array.toString()
    }

    private fun decodeHeartRateDevices(raw: String?): List<HeartRateDevice> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val address = obj.optString("address", "")
                    if (address.isBlank()) {
                        continue
                    }
                    val name = if (obj.isNull("name")) {
                        null
                    } else {
                        obj.optString("name", null)
                    }
                    add(HeartRateDevice(name = name, address = address))
                }
            }
        } catch (ex: Exception) {
            Timber.w(ex, "Failed to decode heart rate devices from preferences")
            emptyList()
        }
    }

    override fun close() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        SharedPreferenceListeners.remove(listener)
    }
}