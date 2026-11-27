package com.spop.poverlay.openbikecontrol

import android.content.SharedPreferences
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OpenBikeControlManagerTest {
    private val key = "openBikeControlEnabled"
    private var enabled = false
    private val preferences = mockk<SharedPreferences>(relaxed = true)
    private val listener = slot<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val network = mockk<ObcNetworkClient>(relaxed = true)
    private val bluetooth = mockk<ObcBluetoothClient>(relaxed = true)
    private val shifts = mutableListOf<Int>()

    @Before fun setup() {
        every { preferences.getBoolean(key, false) } answers { enabled }
        every { preferences.registerOnSharedPreferenceChangeListener(capture(listener)) } just Runs
    }

    private fun manager(shift: ((Int) -> Unit)? = { shifts += it }) =
        OpenBikeControlManager(preferences, shift, { network }, { bluetooth })

    private fun toggle(value: Boolean) { enabled = value; listener.captured.onSharedPreferenceChanged(preferences, key) }

    @Test fun `transports follow the setting`() {
        val manager = manager()
        manager.start()
        verify(exactly = 0) { network.start(); bluetooth.start() }
        assertFalse(manager.status.value.enabled)

        toggle(true)
        verify(exactly = 1) { network.start(); bluetooth.start() }
        assertTrue(manager.status.value.enabled)
        assertTrue(manager.status.value.active)

        toggle(false)
        verify(exactly = 1) { network.stop(); bluetooth.stop() }
        assertFalse(manager.status.value.enabled)
        assertFalse(manager.status.value.active)
    }

    @Test fun `an enabled setting starts with the service and stops with it`() {
        enabled = true
        val manager = manager()
        manager.start()
        verify(exactly = 1) { network.start(); bluetooth.start() }
        manager.stop()
        verify(exactly = 1) { network.stop(); bluetooth.stop() }
        verify { preferences.unregisterOnSharedPreferenceChangeListener(listener.captured) }
    }

    @Test fun `a bike Grupetto cannot control never connects`() {
        enabled = true
        val manager = manager(shift = null)
        manager.start()
        assertFalse(manager.supported)
        verify(exactly = 0) { network.start(); bluetooth.start() }
    }

    @Test fun `presses from either transport shift the bike`() {
        val manager = manager()
        manager.networkMessage(ObcMessage.Buttons(listOf(0x01 to 1)))
        manager.networkMessage(ObcMessage.Buttons(listOf(0x01 to 0)))
        manager.bluetoothMessage(ObcMessage.Buttons(listOf(0x31 to 1)))
        assertEquals(listOf(1, -1), shifts)
        assertEquals("Shift down", manager.status.value.lastShift)
    }

    @Test fun `transports track held buttons separately`() {
        val manager = manager()
        manager.networkMessage(ObcMessage.Buttons(listOf(0x01 to 1)))
        manager.bluetoothMessage(ObcMessage.Buttons(listOf(0x01 to 1)))
        assertEquals(listOf(1, 1), shifts)
    }

    @Test fun `a dropped connection forgets held buttons`() {
        val manager = manager()
        manager.networkMessage(ObcMessage.Buttons(listOf(0x02 to 1)))
        manager.networkLink(ObcLink(ObcLink.Phase.Searching, "BikeControl", "Connection closed · reconnecting"))
        manager.networkLink(ObcLink(ObcLink.Phase.Connected, "BikeControl"))
        manager.networkMessage(ObcMessage.Buttons(listOf(0x02 to 1)))
        assertEquals(listOf(-1, -1), shifts)
        assertEquals(ObcLink(ObcLink.Phase.Connected, "BikeControl"), manager.status.value.network)
    }

    @Test fun `a connected Wi-Fi remote pauses the Bluetooth search`() {
        val manager = manager()
        manager.networkLink(ObcLink(ObcLink.Phase.Connected, "BikeControl"))
        verify { bluetooth.pauseSearch(true) }
        manager.networkLink(ObcLink(ObcLink.Phase.Searching, "BikeControl", "Connection closed · reconnecting"))
        verify { bluetooth.pauseSearch(false) }
    }

    @Test fun `setting on before the overlay starts is not active yet`() {
        enabled = true
        val manager = manager()
        assertTrue(manager.status.value.enabled)
        assertFalse(manager.status.value.active)
    }

    @Test fun `device status reports the remote battery`() {
        val manager = manager()
        manager.bluetoothMessage(ObcMessage.DeviceStatus(72, true))
        assertEquals(72, manager.status.value.battery)
        assertTrue(shifts.isEmpty())
    }
}
