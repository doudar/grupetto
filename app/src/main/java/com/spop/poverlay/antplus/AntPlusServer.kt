package com.spop.poverlay.antplus

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ANT+ Server that broadcasts cycling power and cadence metrics via ANT+ protocol
 * alongside the existing BLE implementation.
 */
class AntPlusServer(
    private val context: Context,
    private val sensorInterface: SensorInterface,
    private val deviceName: String = "Grupetto ANT+"
) : CoroutineScope {

    companion object {
        // ANT+ Power Meter standard update rate is 4Hz (250ms)
        private const val SENSOR_UPDATE_INTERVAL_MS = 250L
        private const val MPH_TO_KMH = 1.60934f
    }

    override val coroutineContext = SupervisorJob() + Dispatchers.IO

    private var sensorDataJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    // ANT+ protocol handler
    private var antPlusHandler: AntPlusHandler? = null
    private var isAntRadioServiceAvailable = false


    /**
     * Check if ANT+ Radio Service is available on the device
     */
    fun isAntPlusAvailable(): Boolean {
        return try {
            val packageManager = context.packageManager
            packageManager.getPackageInfo("com.dsi.ant.service.socket", PackageManager.GET_META_DATA)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            Timber.w("ANT+ Radio Service not installed on device")
            false
        }
    }

    /**
     * Start the ANT+ server and begin broadcasting sensor data
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            Timber.d("ANT+ server already started")
            return
        }

        // Check if ANT+ Radio Service is available
        isAntRadioServiceAvailable = isAntPlusAvailable()
        if (!isAntRadioServiceAvailable) {
            Timber.w("Cannot start ANT+ server: ANT+ Radio Service not found")
            // We'll still try to initialize the handler which will log its own bind result
        }

        // Check for required permissions - BYPASSED for debug
        /*
        if (!hasRequiredPermissions()) {
            Timber.w("Cannot start ANT+ server: missing required permissions")
            isRunning.set(false)
            return
        }
        */

        try {
            // Initialize ANT+ handler in coroutine scope
            antPlusHandler = AntPlusHandler(context, deviceName)
            launch {
                try {
                    antPlusHandler?.initialize()
                    // Start sensor data updates after initialization
                    startSensorDataUpdates()
                    Timber.d("ANT+ server started successfully")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to initialize ANT+ server")
                    isRunning.set(false)
                    stop()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start ANT+ server")
            isRunning.set(false)
            stop()
        }
    }

    /**
     * Stop the ANT+ server
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        try {
            stopSensorDataUpdates()
            launch {
                try {
                    antPlusHandler?.shutdown()
                    antPlusHandler = null
                    Timber.d("ANT+ server stopped")
                } catch (e: Exception) {
                    Timber.e(e, "Error stopping ANT+ server")
                    antPlusHandler = null
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error stopping ANT+ server")
        }
    }

    /**
     * Check if the app has all required permissions for ANT+
     */
    private fun hasRequiredPermissions(): Boolean {
        val requiredPermissions = listOf(
            "com.dsi.ant.permission.ANT_COMMUNICATION",
            "com.dsi.ant.permission.ANT_ADMIN"
        )

        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Start collecting sensor data and broadcasting via ANT+
     */
    private fun startSensorDataUpdates() {
        sensorDataJob?.cancel()
        sensorDataJob = launch {
            try {
                var lastUpdateTime = 0L
                
                // Combine power, cadence, and speed flows for the ANT+ broadcast
                combine(
                    sensorInterface.power,
                    sensorInterface.cadence,
                    sensorInterface.speed
                ) { power, cadence, speed ->
                    Triple(power, cadence, speed)
                }.collect { (power, cadence, speed) ->
                    val currentTime = System.currentTimeMillis()
                    // Broadcast at ~4Hz as per ANT+ spec for Power Meters
                    if (currentTime - lastUpdateTime >= SENSOR_UPDATE_INTERVAL_MS) {
                        lastUpdateTime = currentTime
                        antPlusHandler?.broadcastPowerData(power.toInt(), cadence.toInt())
                        // SensorInterface speed is mph; CSC encoding expects km/h.
                        antPlusHandler?.broadcastSpeedData(speed * MPH_TO_KMH)
                    }
                }
            } catch (e: Exception) {
                if (!(e.message?.contains("Job was cancelled") ?: false)) {
                    Timber.e(e, "Error in ANT+ sensor data collection")
                }
            }
        }
    }

    /**
     * Stop collecting sensor data
     */
    private fun stopSensorDataUpdates() {
        sensorDataJob?.cancel()
        sensorDataJob = null
    }

    /**
     * Check if the server has any active connections
     */
    fun hasConnectedDevices(): Boolean {
        return antPlusHandler?.hasConnectedDevices() ?: false
    }

    /**
     * Update ANT+ device name (requires restart)
     */
    fun updateDeviceName(newName: String) {
        if (isRunning.get()) {
            Timber.w("Cannot update ANT+ device name while running; restart required")
            return
        }
    }
}
