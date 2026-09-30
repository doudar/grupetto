package com.spop.poverlay

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.mockk.*
import org.junit.Before
import org.junit.Test

class BootReceiverTest {
    private val context = mockk<Context>()
    private val prefs = mockk<SharedPreferences>()
    private val intent = mockk<Intent>()
    private val permission = mockk<(Context) -> Boolean>()
    private val start = mockk<(Context) -> Unit>(relaxed = true)
    private val receiver = BootReceiver(permission, start)

    @Before fun setup() {
        every { context.getSharedPreferences(ConfigurationRepository.SharedPrefsName, Context.MODE_PRIVATE) } returns prefs
        every { prefs.getBoolean(ConfigurationRepository.Preferences.AutoStartOnBoot.key, false) } returns true
        every { intent.action } returns Intent.ACTION_BOOT_COMPLETED
        every { permission(context) } returns true
    }

    @Test fun `boot starts overlay when opted in and permitted`() {
        receiver.onReceive(context, intent)
        verify(exactly = 1) { start(context) }
    }

    @Test fun `other broadcasts do not start or read preferences`() {
        every { intent.action } returns Intent.ACTION_SCREEN_ON
        receiver.onReceive(context, intent)
        verify { context wasNot Called; start wasNot Called }
    }

    @Test fun `default opt out prevents startup`() {
        every { prefs.getBoolean(ConfigurationRepository.Preferences.AutoStartOnBoot.key, false) } returns false
        receiver.onReceive(context, intent)
        verify { start wasNot Called; permission wasNot Called }
    }

    @Test fun `missing overlay permission prevents startup`() {
        every { permission(context) } returns false
        receiver.onReceive(context, intent)
        verify { start wasNot Called }
    }

    @Test fun `platform startup restrictions do not crash receiver`() {
        for (error in listOf(IllegalStateException("background restriction"), SecurityException("permission revoked"))) {
            every { start(context) } throws error
            receiver.onReceive(context, intent)
        }
        verify(exactly = 2) { start(context) }
    }
}
