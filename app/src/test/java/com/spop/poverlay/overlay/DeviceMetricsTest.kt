package com.spop.poverlay.overlay

import com.spop.poverlay.sensor.interfaces.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMetricsTest {

    @Test
    fun `bike shows the full power cadence resistance speed set`() {
        assertEquals(
            listOf(
                MetricType.POWER,
                MetricType.CADENCE,
                MetricType.RESISTANCE,
                MetricType.SPEED
            ),
            deviceMetrics(DeviceType.Bike)
        )
    }

    @Test
    fun `tread shows only speed and incline`() {
        assertEquals(
            listOf(MetricType.SPEED, MetricType.INCLINE),
            deviceMetrics(DeviceType.Tread)
        )
    }

    @Test
    fun `tread hides cadence resistance and power`() {
        val tread = deviceMetrics(DeviceType.Tread)
        assertFalse(MetricType.CADENCE in tread)
        assertFalse(MetricType.RESISTANCE in tread)
        assertFalse(MetricType.POWER in tread)
    }

    @Test
    fun `tread shows incline that the bike hides`() {
        assertTrue(MetricType.INCLINE in deviceMetrics(DeviceType.Tread))
        assertFalse(MetricType.INCLINE in deviceMetrics(DeviceType.Bike))
    }

    @Test
    fun `default metric is power on a bike and speed on a tread`() {
        assertEquals(MetricType.POWER, defaultMetricFor(DeviceType.Bike))
        assertEquals(MetricType.SPEED, defaultMetricFor(DeviceType.Tread))
    }
}
