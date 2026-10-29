package com.spop.poverlay.sensor.power

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class PowerDisplayAveragingTest {
    @Test fun bikeAndCorrectionShareBucketsIncludingZeroWithNoCarryover() {
        val bucket = PowerDisplayBucket()
        bucket.add(PowerDisplay(90f, DisplayPowerSource.Corrected, 100f))
        bucket.add(PowerDisplay(270f, DisplayPowerSource.Corrected, 300f))
        bucket.add(PowerDisplay(0f, DisplayPowerSource.Corrected, 0f))
        val average = bucket.take()!!
        assertEquals(120f, average.watts, .001f)
        assertEquals(400f / 3, average.nativeWatts, .001f)
        assertNull(bucket.take())
        bucket.add(PowerDisplay(180f, DisplayPowerSource.Corrected, 200f))
        assertEquals(180f, bucket.take()!!.watts, .001f)
    }

    @Test fun sourceChangeDiscardsMixedBucketAndInvalidPacketsDoNotPoisonIt() {
        val bucket = PowerDisplayBucket()
        bucket.add(PowerDisplay(90f, DisplayPowerSource.Corrected, 100f))
        bucket.add(PowerDisplay(200f))
        bucket.add(PowerDisplay(Float.NaN))
        bucket.add(PowerDisplay(20f, nativeWatts = Float.POSITIVE_INFINITY))
        assertEquals(PowerDisplay(200f), bucket.take())
    }

    @Test fun waitsFor900MillisecondsAndAveragesInsteadOfTakingLastPacket() = runBlocking {
        val packets = MutableSharedFlow<PowerDisplay>(extraBufferCapacity = 8)
        val result = async(start = CoroutineStart.UNDISPATCHED) { packets.averagePowerBuckets().first() }
        withTimeout(1000) { packets.subscriptionCount.first { it > 0 } }
        packets.emit(PowerDisplay(90f, DisplayPowerSource.Corrected, 100f))
        delay(250)
        packets.emit(PowerDisplay(270f, DisplayPowerSource.Corrected, 300f))
        delay(350)
        assertFalse(result.isCompleted)
        val average = withTimeout(1000) { result.await() }
        assertEquals(180f, average.watts, .001f)
        assertEquals(200f, average.nativeWatts, .001f)
    }
}
