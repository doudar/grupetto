package com.spop.poverlay.calibration

import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.spop.poverlay.sensor.BikeCoreData
import com.spop.poverlay.sensor.readBikeCoreData
import com.spop.poverlay.sensor.v1new.V1NewCombinedSensor
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CalibrationLiveTelemetryTest {
    @Test fun repeatedCaptureUnsubscribeKeepsPrimaryCallbackAndFreshNativeMetrics() = runBlocking {
        mockkStatic(Parcel::class, SystemClock::class)
        mockkStatic("com.spop.poverlay.sensor.BikeCoreDataKt")
        val request = mockk<Parcel>(relaxed = true)
        val reply = mockk<Parcel>(relaxed = true)
        val remote = mockk<IBinder>()
        val callbacks = mutableSetOf<IBinder>()
        var sent: IBinder? = null
        var obtains = 0
        var packet = BikeCoreData(80, 20000, 40, 40)
        every { Parcel.obtain() } answers { if (obtains++ % 2 == 0) request else reply }
        every { SystemClock.elapsedRealtime() } returns 1000L
        every { request.writeStrongBinder(any()) } answers { sent = firstArg() }
        every { remote.transact(any(), request, reply, 0) } answers {
            when (firstArg<Int>()) {
                1 -> callbacks.add(sent!!)
                2 -> callbacks.remove(sent!!)
                else -> fail("Unexpected write/control transaction")
            }
            true
        }
        every { readBikeCoreData(any()) } answers { packet }
        val sensor = V1NewCombinedSensor(remote)
        try {
            sensor.start()
            val primary = callbacks.single()
            fun deliver() {
                val data = mockk<Parcel>(relaxed = true)
                every { data.readInt() } returns 1
                val method = primary.javaClass.getDeclaredMethod("onTransact", Int::class.javaPrimitiveType,
                    Parcel::class.java, Parcel::class.java, Int::class.javaPrimitiveType)
                method.isAccessible = true
                assertEquals(true, method.invoke(primary, 1, data, null, IBinder.FLAG_ONEWAY))
            }
            repeat(3) { cycle ->
                val capture = CalibrationService(remote)
                capture.start()
                assertEquals(2, callbacks.size)
                packet = BikeCoreData(80L + cycle, 20000L + cycle * 1000, 40, 40)
                deliver()
                assertEquals(200f + cycle * 10, sensor.nativePower.first(), .001f)
                capture.close()
                assertEquals(setOf(primary), callbacks)
                packet = BikeCoreData(90L + cycle, 30000L + cycle * 1000, 41, 41)
                deliver()
                assertEquals(300f + cycle * 10, sensor.nativePower.first(), .001f)
                assertEquals(90f + cycle, sensor.cadence.first(), .001f)
                assertEquals(41f, sensor.resistance.first(), .001f)
            }
        } finally {
            runCatching { sensor.stop() }
            unmockkStatic(Parcel::class, SystemClock::class)
            unmockkStatic("com.spop.poverlay.sensor.BikeCoreDataKt")
        }
    }
}
