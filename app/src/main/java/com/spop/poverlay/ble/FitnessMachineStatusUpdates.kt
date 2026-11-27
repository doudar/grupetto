package com.spop.poverlay.ble

import com.spop.poverlay.control.ControlMode
import com.spop.poverlay.control.ControlState
import com.spop.poverlay.control.ResistanceLimits

/** FTMS 4.17.1: report local target changes as well as remote procedure results. */
internal class FitnessMachineStatusUpdates(private var previous: ControlState?) {
    fun update(state: ControlState, localTargets: Boolean = true): List<ByteArray> {
        val before = previous
        previous = state
        if (before == null) return emptyList()
        return buildList {
            if (before.permissionLostCount != state.permissionLostCount) add(byteArrayOf(0xff.toByte()))
            // Remote procedures already emit their own status. Initial connection
            // and safety disconnection are not user target adjustments.
            if (!localTargets || !before.connected || !state.connected || state.externalControl != null) return@buildList
            when (state.mode) {
                ControlMode.Manual, ControlMode.Resistance -> if (
                    before.targetResistance != state.targetResistance || before.mode != state.mode
                ) add(ResistanceLimits.targetStatus(state.targetResistance))
                ControlMode.Erg -> if (before.targetWatts != state.targetWatts || before.mode != state.mode) {
                    add(byteArrayOf(8, state.targetWatts.toByte(), (state.targetWatts shr 8).toByte()))
                }
                ControlMode.Simulation -> Unit // Resistance is derived from simulation parameters.
            }
        }
    }
}
