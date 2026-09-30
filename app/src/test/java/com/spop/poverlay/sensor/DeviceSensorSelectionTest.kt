package com.spop.poverlay.sensor

import android.content.Context
import android.provider.Settings
import com.spop.poverlay.util.readPelotonPlatform
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the Bike+-misdetected-as-Tread bug: PLTN-TTR01 is the shared
 * Topaz *tablet* model shipped on Bike+ (TITAN), Tread (PRISM) and Row (CAESAR), so the
 * model string alone must never select the tread path.
 */
class DeviceSensorSelectionTest {
    private val bikeModels = mapOf(
        "PLTN-RB1VQ" to SensorSelection.BikeV1,
        "PLTN-RB1VO" to SensorSelection.BikeV1,
        "PLTN-TTR01" to SensorSelection.BikePlus,
        "PLTN-TTR01-2" to SensorSelection.BikePlus,
        "G700" to SensorSelection.BikePlus,
        "g700-cross" to SensorSelection.BikePlus,
        "PLTN-ATR01" to SensorSelection.BikePlus,
        "pltn-atr99" to SensorSelection.BikePlus
    )

    @Test
    fun `bike plus on the shared topaz tablet selects bike plus not tread`() {
        assertEquals(
            SensorSelection.BikePlus,
            selectSensorForDevice(true, model = "PLTN-TTR01", platform = "titan")
        )
        assertEquals(
            SensorSelection.BikePlus,
            selectSensorForDevice(true, model = "PLTN-TTR01-2", platform = "titan")
        )
    }

    @Test
    fun `tread selects tread`() {
        assertEquals(
            SensorSelection.Tread,
            selectSensorForDevice(true, model = "PLTN-TTR01", platform = "prism")
        )
        assertEquals(
            SensorSelection.Tread,
            selectSensorForDevice(true, model = "PLTN-TTR01", platform = "prism-l")
        )
    }

    @Test
    fun `row does not select tread`() {
        assertEquals(
            SensorSelection.BikePlus,
            selectSensorForDevice(true, model = "PLTN-TTR01", platform = "caesar")
        )
    }

    @Test
    fun `missing or unrecognized platform preserves every bike model route`() {
        for ((model, expected) in bikeModels) {
            for (platform in listOf(null, "", "   ", "unknown", "prismatic", "notprism")) {
                assertEquals(
                    "model=$model platform=$platform",
                    expected,
                    selectSensorForDevice(true, model, platform)
                )
            }
        }
    }

    @Test
    fun `unreadable platform setting preserves every bike model route`() {
        val context = mockk<Context>(relaxed = true)
        mockkStatic(Settings.Global::class)
        try {
            every {
                Settings.Global.getString(context.contentResolver, "peloton_platform")
            } throws SecurityException("Platform setting unavailable")

            val platform = readPelotonPlatform(context)
            assertNull(platform)
            for ((model, expected) in bikeModels) {
                assertEquals(model, expected, selectSensorForDevice(true, model, platform))
            }
        } finally {
            unmockkStatic(Settings.Global::class)
        }
    }

    @Test
    fun `bike gen 1 is unchanged`() {
        for (model in listOf("PLTN-RB1VQ", "PLTN-RB1VO")) {
            assertEquals(model, SensorSelection.BikeV1, selectSensorForDevice(true, model, "v1"))
        }
    }

    @Test
    fun `g700 cross trainer with newer model prefix uses bike plus sensors`() {
        for (model in listOf("PLTN-ATR01", "pltn-atr99")) {
            assertEquals(model, SensorSelection.BikePlus, selectSensorForDevice(true, model, null))
        }
    }

    @Test
    fun `legacy g700 cross trainer uses bike plus sensors`() {
        for (model in listOf("G700", "g700-cross")) {
            assertEquals(model, SensorSelection.BikePlus, selectSensorForDevice(true, model, null))
        }
    }

    @Test
    fun `unknown peloton model retains legacy bike fallback`() {
        for (model in listOf("", "unknown")) {
            assertEquals(model, SensorSelection.BikeV1, selectSensorForDevice(true, model, null))
        }
    }

    @Test
    fun `off peloton selects dummy regardless of platform`() {
        for (model in bikeModels.keys) {
            for (platform in listOf(null, "v1", "titan", "prism")) {
                assertEquals(
                    "model=$model platform=$platform",
                    SensorSelection.Dummy,
                    selectSensorForDevice(false, model, platform)
                )
            }
        }
    }
}
