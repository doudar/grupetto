package com.spop.poverlay.sensor

import org.junit.Assert.*
import org.junit.Test

class SensorDiscoveryPolicyTest {
    @Test fun noSavedSensorOnlyDiscoversWhileItsPickerIsOpen() {
        assertFalse(shouldDiscoverSensor(true, false, false, false, false))
        assertTrue(shouldDiscoverSensor(true, true, false, false, false))
        assertFalse(shouldDiscoverSensor(true, false, false, false, false))
    }

    @Test fun pendingOrConnectedGattAlwaysStopsDiscovery() {
        for (picker in listOf(false, true)) for (saved in listOf(false, true)) {
            assertFalse(shouldDiscoverSensor(true, picker, saved, false, true))
        }
    }

    @Test fun savedDisconnectedSensorReconnectsWithPickerClosed() {
        assertTrue(shouldDiscoverSensor(true, false, true, false, false))
        assertFalse(shouldDiscoverSensor(true, false, true, false, true))
        assertTrue(shouldDiscoverSensor(true, false, true, false, false))
        // Forgetting the last saved target removes background scan demand immediately.
        assertFalse(shouldDiscoverSensor(true, false, false, false, false))
    }

    @Test fun explicitDisconnectSuppressesReconnectButAllowsPairing() {
        assertFalse(shouldDiscoverSensor(true, false, true, true, false))
        assertTrue(shouldDiscoverSensor(true, true, true, true, false))
    }

    @Test fun stoppedManagerNeverScans() {
        for (picker in listOf(false, true)) for (saved in listOf(false, true)) {
            assertFalse(shouldDiscoverSensor(false, picker, saved, false, false))
        }
    }

    @Test fun eachSensorTypeHasIndependentDemand() {
        val heartRate = shouldDiscoverSensor(true, false, false, false, false)
        val power = shouldDiscoverSensor(true, true, false, false, false)
        assertFalse(heartRate)
        assertTrue(power)
    }
}
