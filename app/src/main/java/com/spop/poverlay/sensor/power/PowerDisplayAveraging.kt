package com.spop.poverlay.sensor.power

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

private sealed interface PowerBucketEvent {
    data class Reading(val value: PowerDisplay) : PowerBucketEvent
    object Tick : PowerBucketEvent
}

/** Both numbers average the same actual bike packets. Pedal packets/timer ticks must not weight them. */
internal class PowerDisplayBucket {
    private var latest: PowerDisplay? = null
    private var watts = 0.0
    private var nativeWatts = 0.0
    private var count = 0

    fun add(value: PowerDisplay) {
        if (!value.watts.isFinite() || !value.nativeWatts.isFinite()) return
        // Never blend corrected and uncorrected frames or label their mixture as calibrated.
        if (latest?.source != value.source) { watts = 0.0; nativeWatts = 0.0; count = 0 }
        latest = value
        watts += value.watts
        nativeWatts += value.nativeWatts
        count++
    }

    fun take(): PowerDisplay? {
        if (count == 0) return null
        val average = latest!!.copy(watts = (watts / count).toFloat(), nativeWatts = (nativeWatts / count).toFloat())
        watts = 0.0; nativeWatts = 0.0; count = 0
        return average
    }
}

/** Non-overlapping 900 ms display/telemetry buckets; raw capture and ERG remain unaveraged. */
internal fun Flow<PowerDisplay>.averagePowerBuckets(): Flow<PowerDisplay> = flow {
    val bucket = PowerDisplayBucket()
    val ticks = flow { while (true) { delay(900); emit(PowerBucketEvent.Tick) } }
    merge(this@averagePowerBuckets.map { PowerBucketEvent.Reading(it) }, ticks).collect { event ->
        when (event) {
            is PowerBucketEvent.Reading -> bucket.add(event.value)
            PowerBucketEvent.Tick -> bucket.take()?.let { emit(it) }
        }
    }
}
