package com.spop.poverlay.util

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.lifecycle.LifecycleOwner
import com.spop.poverlay.ConfigurationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceSerialIntegrationTest {
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val preferences = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val stored = mutableMapOf<String, String>()
    private val pending = mutableMapOf<String, String>()
    private val owner = mockk<LifecycleOwner>(relaxed = true)

    @Before fun setup() {
        mockkStatic(Settings.Secure::class)
        every { context.applicationContext } returns context
        every { context.contentResolver } returns resolver
        every { context.getSharedPreferences(ConfigurationRepository.SharedPrefsName, Context.MODE_PRIVATE) } returns preferences
        every { Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID) } returns "3f2a9c81d04e7b65"
        every { preferences.getString(any(), any()) } answers {
            stored[firstArg<String>()] ?: secondArg<String?>()
        }
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            pending[firstArg()] = secondArg<String>()
            editor
        }
        every { editor.apply() } answers { stored.putAll(pending); pending.clear() }
        every { editor.commit() } answers { stored.putAll(pending); pending.clear(); true }
    }

    @After fun cleanup() { unmockkStatic(Settings.Secure::class) }

    @Test fun generateUsesAndroidId() {
        assertEquals(DeviceSerial.fromAndroidId("3f2a9c81d04e7b65"), DeviceSerial.generate(context))
    }

    @Test fun missingSharedAndUnreadableIdsFallBackToFourHexDigits() {
        for (id in listOf(null, "", "  ", " 9774D56D682E549C ")) {
            every { Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID) } returns id
            assertTrue(DeviceSerial.generate(context).matches(Regex("[0-9A-F]{4}")))
        }
        every { Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID) } throws SecurityException("unavailable")
        assertTrue(DeviceSerial.generate(context).matches(Regex("[0-9A-F]{4}")))
        every { context.contentResolver } throws IllegalStateException("unavailable")
        assertTrue(DeviceSerial.generate(context).matches(Regex("[0-9A-F]{4}")))
    }

    @Test fun existingSerialIsKeptWithoutReadingAndroidId() {
        stored["serialNumber"] = "ABCD"
        ConfigurationRepository(context, owner).use { assertEquals("ABCD", it.serialNumber.value) }
        verify(exactly = 0) { context.contentResolver }
        verify(exactly = 0) { editor.putString("serialNumber", any()) }
    }

    @Test fun freshSerialIsPersistedAndRecreatedAfterClearingStorage() {
        val expected = DeviceSerial.fromAndroidId("3f2a9c81d04e7b65")
        ConfigurationRepository(context, owner).use { assertEquals(expected, it.serialNumber.value) }
        assertEquals(expected, stored["serialNumber"])
        stored.clear()
        ConfigurationRepository(context, owner).use { assertEquals(expected, it.serialNumber.value) }
    }

    @Test fun savedSerialWinsEvenWhenAndroidIdChanges() {
        var original = ""
        ConfigurationRepository(context, owner).use { original = it.serialNumber.value }
        every { Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID) } returns "different-id"
        ConfigurationRepository(context, owner).use { assertEquals(original, it.serialNumber.value) }
    }

    @Test fun unavailableApplicationContextFallsBackToProvidedContext() {
        every { context.applicationContext } throws IllegalStateException("unavailable")
        ConfigurationRepository(context, owner).use {
            assertEquals(DeviceSerial.fromAndroidId("3f2a9c81d04e7b65"), it.serialNumber.value)
        }
    }
}