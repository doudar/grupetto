package com.spop.poverlay.antplus

import android.content.Context
import android.content.pm.PackageManager
import com.spop.poverlay.sensor.heartrate.HeartRateManager
import com.spop.poverlay.sensor.interfaces.DeviceType
import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import timber.log.Timber

/** Optional cycling transmitter. Each start owns its handler, collectors, and retries. */
class AntPlusServer(
    private val context: Context,
    private val sensorInterface: SensorInterface,
    private val heartRate: Flow<Int?> = HeartRateManager.heartRate,
    private val handlerFactory: (CoroutineScope) -> AntPlusHandler = {
        AntPlusHandler(context, "Grupetto ANT+", it)
    }
) {
    private data class Session(val scope: CoroutineScope, val handler: AntPlusHandler)
    private var session: Session? = null

    val isSupported: Boolean get() = sensorInterface.deviceType == DeviceType.Bike

    fun isAntPlusAvailable(): Boolean = try {
        context.packageManager.getPackageInfo("com.dsi.ant.service.socket", 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    @Synchronized
    fun start() {
        if (session != null || !isSupported) return
        if (!isAntPlusAvailable()) {
            Timber.w("ANT Radio Service is unavailable")
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var handler: AntPlusHandler? = null
        try {
            val activeHandler = handlerFactory(scope)
            handler = activeHandler
            activeHandler.initialize()
            session = Session(scope, activeHandler)
            scope.launch {
                combine(sensorInterface.power, sensorInterface.cadence, sensorInterface.speed) {
                    power, cadence, speed -> Triple(power, cadence, speed)
                }.sample(250).collect { (power, cadence, speedMph) ->
                    activeHandler.broadcastPowerData(
                        if (power.isFinite()) power.toInt().coerceAtLeast(0) else 0,
                        if (cadence.isFinite()) cadence.toInt().coerceAtLeast(0) else 0
                    )
                    activeHandler.broadcastSpeedData(
                        if (speedMph.isFinite()) speedMph.coerceAtLeast(0f) * 1.60934f else 0f
                    )
                }
            }
            scope.launch {
                // A disconnect must clear the last value instead of broadcasting stale HR.
                heartRate.collect { activeHandler.broadcastHrmData(it ?: 0) }
            }
            scope.launch {
                repeat(12) {
                    delay(15_000)
                    if (activeHandler.isChannelReady()) return@launch
                    try {
                        activeHandler.retryChannelSetupIfNeeded()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "ANT channel setup retry failed")
                    }
                }
            }
        } catch (e: Exception) {
            scope.cancel()
            runCatching { handler?.shutdown() }
            session = null
            Timber.e(e, "Failed to start ANT+ server")
        }
    }

    @Synchronized
    fun stop() {
        val active = session ?: return
        session = null
        active.scope.cancel()
        runCatching { active.handler.shutdown() }
            .onFailure { Timber.w(it, "Failed to stop ANT+ server") }
    }

    @Synchronized
    fun hasConnectedDevices(): Boolean = session?.handler?.hasConnectedDevices() == true
}
