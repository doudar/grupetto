package com.spop.poverlay.ble

import java.util.UUID

object GenericAttributeConstants {
    // GATT Service (0x1801) and Service Changed characteristic (0x2A05)
    val ServiceUUID: UUID = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")
    val ServiceChangedUUID: UUID = UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb")
    val ClientCharacteristicConfigurationUUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
