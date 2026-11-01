package com.spop.poverlay.sensor.discovery

import android.content.Context
import android.os.IBinder
import com.spop.poverlay.sensor.BoundBikeService
import com.spop.poverlay.sensor.bindBikeService
import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.sensor.v1legacy.SensorDataSensor
import com.spop.poverlay.sensor.v1new.V1NewCombinedSensor
import com.spop.poverlay.sensor.v2.BikePlusCombinedSensor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// A remote descriptor query can hang in native Binder, which coroutine cancellation cannot interrupt.
// One worker and one queued request bound this failure across repeated scans. The queue also avoids
// rejecting the next query during the tiny interval between resuming a caller and returning the worker.
private val descriptorWorker = ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1)) { runnable ->
    Thread(runnable, "BikeInterfaceDescriptor").apply { isDaemon = true }
}

internal suspend fun readBikeDescriptor(binder: IBinder): String? = withTimeout(1_500) {
    suspendCancellableCoroutine { continuation ->
        val task = Runnable {
            try { continuation.resume(binder.interfaceDescriptor) }
            catch (e: Exception) { continuation.resumeWithException(e) }
        }
        continuation.invokeOnCancellation { descriptorWorker.remove(task) }
        try {
            descriptorWorker.execute(task)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            continuation.resumeWithException(IllegalStateException("An earlier descriptor query is still blocked"))
        }
    }
}

internal suspend fun <T> withBikeBinding(context: Context, endpoint: BikeServiceEndpoint,
    block: suspend (IBinder) -> T): T {
    var binding: BoundBikeService? = null
    try {
        withTimeout(4_000) { bindBikeService(context, endpoint::intent).also { binding = it } }
        return block(checkNotNull(binding).binder)
    } finally { binding?.close() }
}

/** Only telemetry adapters are available here. Discovered services never enable motor/calibration writes. */
internal suspend fun <T> withKnownBikeTelemetry(binder: IBinder, protocol: BikeProtocol,
    clientName: String, block: suspend (SensorInterface) -> T): T = coroutineScope {
    when (protocol) {
        BikeProtocol.AffernetV1 -> {
            val sensor = V1NewCombinedSensor(binder, clientName)
            try { sensor.start(); block(sensor) } finally { sensor.stop() }
        }
        BikeProtocol.AffernetBikePlus -> {
            val sensor = BikePlusCombinedSensor(binder)
            try { sensor.start(); block(sensor) } finally { sensor.stop() }
        }
        BikeProtocol.SensorData -> {
            val sensor = SensorDataSensor(binder)
            val polling = launch { sensor.run() }
            try { block(sensor) } finally {
                polling.cancel()
                try { withContext(NonCancellable) { polling.join() } } finally { sensor.close() }
            }
        }
    }
}

internal suspend fun firstBikeSample(sensor: SensorInterface): BikeTelemetrySample = withTimeout(5_000) {
    combine(sensor.power, sensor.cadence, sensor.resistance, ::BikeTelemetrySample).first { it.plausible() }
}

/** Restores the explicitly selected endpoint on every launch, including on an unrecognized tablet. */
class DiscoveredBikeSensorInterface(
    private val context: Context,
    private val profile: BikeServiceProfile,
    private val status: (String) -> Unit,
) : SensorInterface {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun stop() = scope.cancel()
    private val sensor = flow {
        try {
            status("Connecting to ${profile.endpoint.component}")
            if (profile.protocol == BikeProtocol.SensorData) {
                check(!shouldSkipSensorData(profile.endpoint, collectBikeServiceInventory(context).services)) {
                    "An enabled Affernet provider may own the sensor connection; run discovery again."
                }
            }
            withBikeBinding(context, profile.endpoint) { binder ->
                val descriptor = readBikeDescriptor(binder)
                check(BikeProtocol.identify(descriptor, profile.endpoint.action) == profile.protocol) {
                    "Service interface changed; run discovery again. Received $descriptor"
                }
                withKnownBikeTelemetry(binder, profile.protocol, "Grupetto") { connected ->
                    firstBikeSample(connected)
                    status("Connected to ${profile.protocol.label}: ${profile.endpoint.packageName}")
                    emit(connected)
                    awaitCancellation()
                }
            }
        } catch (e: TimeoutCancellationException) {
            status("Selected service did not provide bike telemetry. Run discovery or restore automatic selection.")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { status("Selected service unavailable: ${e.message ?: e.javaClass.simpleName}") }
    }.shareIn(scope, SharingStarted.Lazily, 1)
    override val power = sensor.flatMapLatest { it.power }
    override val cadence = sensor.flatMapLatest { it.cadence }
    override val resistance = sensor.flatMapLatest { it.resistance }
}
