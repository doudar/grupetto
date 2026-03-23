package com.spop.poverlay.ble

import java.util.UUID

object HeartRateConstants {
    val ServiceUUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val MeasurementUUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val BodySensorLocationUUID: UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")
    val ClientCharacteristicConfigurationUUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    object BodySensorLocation {
        const val Chest = 0x01
    }
}
