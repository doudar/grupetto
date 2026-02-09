package com.spop.poverlay.sensor.g700

import android.os.IBinder
import android.os.Parcel
import com.spop.poverlay.sensor.BikeData
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Counts consecutive errors, and throws an exception if the limit is reached
 * This is used to stop the sensor if it is not responding.
 */
class ConsecutiveErrorCounter(private val limit: Int = 5) {
    private var consecutiveErrors = 0

    fun increment() {
        consecutiveErrors++
        if (consecutiveErrors >= limit) {
            throw Exception("Too many consecutive errors")
        }
    }

    fun reset() {
        consecutiveErrors = 0
    }
}

private const val READ_DELAY = 100L

/**
 * Base class for G700 CrossTrainer sensors.
 * The G700 uses a different service architecture (MetricsService) than the regular Bike+ (affernetservice).
 * Based on stellarhopper's findings in issue #38.
 */
abstract class G700Sensor(private val binder: IBinder) {
    private val mutableSensorValue = MutableSharedFlow<Float>(
        replay = 2,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val errorCounter = ConsecutiveErrorCounter()

    val sensorValue = mutableSensorValue.asSharedFlow()

    private var threadRunning = AtomicBoolean(false)

    abstract val sensorType: String
    protected abstract fun mapValue(value: Float): Float

    fun start() {
        thread {
            threadRunning.set(true)
            Timber.i("G700 $sensorType sensor started")
            while (threadRunning.get()) {
                try {
                    val parcel = Parcel.obtain()
                    val parcel2 = Parcel.obtain()
                    
                    // TODO: Verify transact code for G700 MetricsService
                    // Currently using the same transact code (14) as Bike+ affernetservice
                    // This may need to be adjusted based on actual G700 protocol testing
                    // See issue #38 for more details
                    binder.transact(14, parcel, parcel2, 0)
                    parcel2.readException()
                    // Skip the first integer
                    parcel2.readInt()
                    val bikeData = BikeData.CREATOR.createFromParcel(parcel2)
                    
                    val rawValue = when (sensorType) {
                        "RPM" -> bikeData.rPM.toFloat()
                        "Power" -> bikeData.power.toFloat()
                        "Resistance" -> bikeData.targetResistance.toFloat()
                        else -> 0f
                    }
                    
                    mutableSensorValue.tryEmit(mapValue(rawValue))
                    parcel.recycle()
                    parcel2.recycle()
                    errorCounter.reset()
                    Thread.sleep(READ_DELAY)
                } catch (e: Exception) {
                    Timber.e(e, "G700 $sensorType sensor error")
                    try {
                        errorCounter.increment()
                    } catch (e: Exception) {
                        stop()
                        throw e
                    }
                }
            }
        }
    }

    private fun stop() {
        threadRunning.set(false)
        Timber.i("G700 $sensorType sensor stopped")
    }
}
