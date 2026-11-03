package com.spop.poverlay.sensor

import com.spop.poverlay.sensor.power.BikeDisplayBucketMillis
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class CadenceDisplayAveragingTest {
    @Test fun averagesActualPacketsIncludingZeroAndDoesNotCarryAnEmptyBucket() {
        val bucket = CadenceDisplayBucket()
        listOf(60f, 120f, 0f, Float.NaN, Float.POSITIVE_INFINITY, -1f).forEach(bucket::add)
        assertEquals(60f, bucket.take()!!, .001f)
        assertNull(bucket.take())
        bucket.add(90f)
        assertEquals(90f, bucket.take()!!, .001f)
    }

    @Test fun calibrationReadoutWaitsForTheSame900msPeriodAsBikePower() = runBlocking {
        assertEquals(900L, BikeDisplayBucketMillis)
        val raw = MutableSharedFlow<Float>(extraBufferCapacity = 8)
        val displayed = async(start = CoroutineStart.UNDISPATCHED) { raw.averageCadenceBuckets().first() }
        withTimeout(1000) { raw.subscriptionCount.first { it > 0 } }
        raw.emit(60f)
        delay(250)
        raw.emit(100f)
        delay(350)
        assertFalse(displayed.isCompleted)
        assertEquals(80f, withTimeout(1000) { displayed.await() }, .001f)
    }
}
