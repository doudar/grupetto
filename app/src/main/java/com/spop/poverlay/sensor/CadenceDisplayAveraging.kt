package com.spop.poverlay.sensor

import com.spop.poverlay.sensor.power.BikeDisplayBucketMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

private sealed interface CadenceBucketEvent {
    data class Reading(val rpm: Float) : CadenceBucketEvent
    object Tick : CadenceBucketEvent
}

internal class CadenceDisplayBucket {
    private var total = 0.0
    private var count = 0
    fun add(rpm: Float) {
        if (!rpm.isFinite() || rpm < 0) return
        total += rpm
        count++
    }
    fun take(): Float? {
        if (count == 0) return null
        val mean = (total / count).toFloat()
        total = 0.0
        count = 0
        return mean
    }
}

/** UI only: capture windows and ERG continue to consume their original raw frames. */
internal fun Flow<Float>.averageCadenceBuckets(): Flow<Float> = flow {
    val bucket = CadenceDisplayBucket()
    val ticks = flow { while (true) { delay(BikeDisplayBucketMillis); emit(CadenceBucketEvent.Tick) } }
    merge(this@averageCadenceBuckets.map { CadenceBucketEvent.Reading(it) }, ticks).collect { event ->
        when (event) {
            is CadenceBucketEvent.Reading -> bucket.add(event.rpm)
            CadenceBucketEvent.Tick -> bucket.take()?.let { emit(it) }
        }
    }
}
