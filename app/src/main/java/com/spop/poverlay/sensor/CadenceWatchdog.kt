package com.spop.poverlay.sensor

import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.sensor.interfaces.DeviceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.minutes

/**
 * Monitors bike cadence or Tread belt speed and restarts after prolonged inactivity.
 * This helps address BLE issues that occur after extended running time.
 */
class CadenceWatchdog(
    private val sensorInterface: SensorInterface,
    override val coroutineContext: CoroutineContext,
    private val inactivityThreshold: kotlin.time.Duration = 30.minutes
) : CoroutineScope {

    private val mutableRestartTriggered = MutableSharedFlow<Unit>(replay = 0)
    
    /**
     * Emits when the watchdog determines a restart is needed
     */
    val restartTriggered = mutableRestartTriggered.asSharedFlow()

    private var lastCadenceTime: Long = System.currentTimeMillis()
    private var monitoringJob: Job? = null
    private var cadenceCollectionJob: Job? = null
    
    fun start() {
        stop() // Ensure no duplicate jobs
        
        lastCadenceTime = System.currentTimeMillis()
        
        // Monitor cadence updates
        cadenceCollectionJob = launch(Dispatchers.IO) {
            watchdogActivity(sensorInterface).collect { active ->
                if (active) {
                    lastCadenceTime = System.currentTimeMillis()
                    Timber.d("Watchdog: Movement detected")
                }
            }
        }
        
        // Check periodically for inactivity
        monitoringJob = launch(Dispatchers.IO) {
            while (true) {
                delay(60_000) // Check every minute
                
                val inactivityDuration = System.currentTimeMillis() - lastCadenceTime
                val thresholdMillis = inactivityThreshold.inWholeMilliseconds
                
                Timber.d("Watchdog: Inactivity duration: ${inactivityDuration / 1000}s / ${thresholdMillis / 1000}s")
                
                if (inactivityDuration >= thresholdMillis) {
                    Timber.w("Watchdog: Inactivity threshold reached. Triggering restart.")
                    mutableRestartTriggered.emit(Unit)
                    // Only trigger once, then stop monitoring
                    stop()
                    break
                }
            }
        }
        
        Timber.i("Cadence watchdog started with ${inactivityThreshold.inWholeMinutes} minute threshold")
    }

    fun stop() {
        cadenceCollectionJob?.cancel()
        cadenceCollectionJob = null
        monitoringJob?.cancel()
        monitoringJob = null
        Timber.i("Cadence watchdog stopped")
    }
}

/** Keep the bike's existing 20 RPM threshold; a Tread has no cadence sensor. */
internal fun watchdogActivity(sensor: SensorInterface) =
    if (sensor.deviceType == DeviceType.Tread) {
        sensor.speed.map { it >= 0.1f }
    } else {
        sensor.cadence.map { it >= 20f }
    }
