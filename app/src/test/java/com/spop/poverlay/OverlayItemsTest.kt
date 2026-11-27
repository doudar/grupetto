package com.spop.poverlay

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LifecycleOwner
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayItemsTest {
    private val stored = mutableMapOf<String, Any>("serialNumber" to "1234")
    private val preferences = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val context = mockk<Context>()

    init {
        every { context.getSharedPreferences(ConfigurationRepository.SharedPrefsName, Context.MODE_PRIVATE) } returns preferences
        every { preferences.getString(any(), any()) } answers { stored[firstArg<String>()] as String? ?: secondArg<String?>() }
        every { preferences.getFloat(any(), any()) } answers { stored[firstArg<String>()] as Float? ?: secondArg<Float>() }
        every { preferences.getBoolean(any(), any()) } answers { stored[firstArg<String>()] as Boolean? ?: secondArg<Boolean>() }
        every { preferences.edit() } returns editor
        every { editor.putBoolean(any(), any()) } answers { stored[firstArg()] = secondArg<Boolean>(); editor }
    }

    private fun repository() = ConfigurationRepository(context, mockk<LifecycleOwner>(relaxed = true))

    @Test
    fun `a new install shows everything but the flywheel temperature`() {
        repository().use { config ->
            assertEquals(OverlayItem.values().toSet() - OverlayItem.FlywheelTemperature, config.overlayItems.value)
        }
    }

    @Test
    fun `a switched item is remembered`() {
        repository().use { config ->
            config.setOverlayItem(OverlayItem.Calories, false)
            config.setOverlayItem(OverlayItem.FlywheelTemperature, true)
            assertFalse(OverlayItem.Calories in config.overlayItems.value)
        }
        repository().use { config ->
            assertFalse(OverlayItem.Calories in config.overlayItems.value)
            assertTrue(OverlayItem.FlywheelTemperature in config.overlayItems.value)
            assertTrue(OverlayItem.Power in config.overlayItems.value)
        }
    }

    @Test
    fun `settings list only what the machine has`() {
        assertEquals(listOf(OverlayItem.Incline, OverlayItem.Speed, OverlayItem.HeartRate, OverlayItem.Chart),
            OverlayItem.forDevice(isTread = true, hasFlywheelEstimate = false))
        assertFalse(OverlayItem.FlywheelTemperature in OverlayItem.forDevice(isTread = false, hasFlywheelEstimate = false))
        assertFalse(OverlayItem.Incline in OverlayItem.forDevice(isTread = false, hasFlywheelEstimate = true))
        assertTrue(OverlayItem.FlywheelTemperature in OverlayItem.forDevice(isTread = false, hasFlywheelEstimate = true))
    }
}
