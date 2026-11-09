package com.spop.poverlay.ble

import com.spop.poverlay.control.BikeControl
import com.spop.poverlay.control.BikeSample
import com.spop.poverlay.control.ControlMode
import com.spop.poverlay.control.ControlState
import org.junit.Assert.*
import org.junit.Test

class FitnessMachineStatusUpdatesTest {
    private val initial = ControlState(connected = true, targetResistance = 40)

    @Test fun `slider and shifters notify signed tenths without waiting for motor motion`() {
        val control = BikeControl(true, { 1000L }) { error("No motor tick") }
        control.acceptSample(BikeSample(250f, 90f, 40, 1000))
        val updates = FitnessMachineStatusUpdates(control.state.value)
        assertTrue(control.localResistance(55))
        assertArrayEquals(byteArrayOf(7, 38, 2), updates.update(control.state.value).single())
        control.shift(1)
        assertArrayEquals(byteArrayOf(7, 58, 2), updates.update(control.state.value).single())
        assertTrue(updates.update(control.state.value).isEmpty())
        assertTrue(control.localResistance(0))
        assertArrayEquals(byteArrayOf(7, 0, 0), updates.update(control.state.value).single())
        assertTrue(control.localResistance(100))
        assertArrayEquals(byteArrayOf(7, 0xe8.toByte(), 3), updates.update(control.state.value).single())
    }

    @Test fun `physical knob changes in manual mode notify clients`() {
        val updates = FitnessMachineStatusUpdates(initial)
        assertArrayEquals(byteArrayOf(7, 0xf4.toByte(), 1), updates.update(initial.copy(targetResistance = 50)).single())
    }

    @Test fun `connection and disconnection do not invent local targets`() {
        val updates = FitnessMachineStatusUpdates(ControlState())
        assertTrue(updates.update(initial).isEmpty())
        assertTrue(updates.update(initial.copy(connected = false)).isEmpty())
        assertTrue(updates.update(initial.copy(targetResistance = 55)).isEmpty())
    }

    @Test fun `remote target and reset status are not duplicated by state collection`() {
        val updates = FitnessMachineStatusUpdates(initial)
        val remote = initial.copy(mode = ControlMode.Resistance, targetResistance = 55, externalControl = "Bluetooth")
        assertTrue(updates.update(remote, localTargets = false).isEmpty())
        assertTrue(updates.update(remote).isEmpty())
        assertTrue(updates.update(initial, localTargets = false).isEmpty())
        assertTrue(updates.update(initial).isEmpty())
        // Local control still notifies after a remote procedure.
        assertArrayEquals(byteArrayOf(7, 88, 2), updates.update(initial.copy(mode = ControlMode.Resistance, targetResistance = 60)).single())
    }

    @Test fun `lost permission is retained even when local targets are suppressed`() {
        val updates = FitnessMachineStatusUpdates(initial)
        val stopped = initial.copy(connected = false, permissionLostCount = 1)
        assertArrayEquals(byteArrayOf(0xff.toByte()), updates.update(stopped, localTargets = false).single())
        assertTrue(updates.update(stopped).isEmpty())
    }

    @Test fun `ERG local target reports power rather than resistance`() {
        val updates = FitnessMachineStatusUpdates(initial)
        assertArrayEquals(byteArrayOf(8, 0x2c, 1), updates.update(initial.copy(mode = ControlMode.Erg, targetWatts = 300)).single())
    }
}
