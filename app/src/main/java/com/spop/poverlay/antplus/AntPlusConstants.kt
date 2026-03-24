package com.spop.poverlay.antplus

/**
 * ANT+ device profile IDs and message formats
 * Reference: https://www.thisisant.com/developer/ant-plus/device-profiles/
 */
object AntPlusConstants {
    // ANT+ Device Type IDs
    const val DEVICE_TYPE_POWER_METER = 11 // FIXED: Correct ID for Bicycle Power
    const val DEVICE_TYPE_SPEED_CADENCE = 121

    // ANT+ Managed Network Key (Standard for Garmin/Wahoo/etc)
    val ANT_PLUS_NETWORK_KEY = byteArrayOf(
        0xB9.toByte(), 0xA5.toByte(), 0x21.toByte(), 0xFB.toByte(), 
        0xBD.toByte(), 0x72.toByte(), 0xC3.toByte(), 0x45.toByte()
    )

    // Common ANT+ settings
    const val ANT_RF_FREQ = 57 // 2457MHz
    const val TRANSMISSION_TYPE = 0x05 // Independent Supply + Global Data
    // Some head units are stricter; keep a fallback tx type for discovery retries.
    val TRANSMISSION_TYPES_TO_TRY = intArrayOf(0x05, 0x01)
    const val DEVICE_NUMBER = 1 // Device serial/identifier — lower values may display better on Garmin

    // Channel Periods (Hz = 32768 / Period)
    const val POWER_METER_PERIOD = 8182 // ANT+ Bike Power profile period (~4.00Hz)
    const val SPEED_CADENCE_PERIOD = 8086 // ~4.052Hz

    // Message IDs
    const val MESG_BROADCAST_DATA_ID = 0x4E
    const val MESG_ACKNOWLEDGED_DATA_ID = 0x4F
    const val MESG_BURST_DATA_ID = 0x50

    // Power Meter specific Pages
    const val POWER_METER_PAGE_STANDARD = 16
    const val PAGE_MANUFACTURER_INFO = 80
    const val PAGE_PRODUCT_INFO = 81
    const val COMMON_PAGE_ROTATION_INTERVAL = 61
    const val MANUFACTURER_PAGE_SLOT = 15
    const val PRODUCT_PAGE_SLOT = 30

    // Common-page metadata for ANT+ discovery/identification
    const val HARDWARE_REVISION = 1
    // Manufacturer ID: 89 = "Gruppe" phonetic + 0x59 = 89 decimal
    // Or use a value that Garmin might recognize as custom manufacturer
    const val MANUFACTURER_ID = 12345 // Custom ID for Grupetto
    const val MODEL_NUMBER = 1 // Model: "Grupetto Power Meter"
    const val SOFTWARE_REVISION_MAIN = 1
    const val SOFTWARE_REVISION_SUPPLEMENTAL = 0
    const val SERIAL_NUMBER = 1 // Serial: reduced for cleaner display

    // Speed/Cadence sensor specific
    const val SPEED_CADENCE_PAGE_DATA = 1

    // ANT+ message sizes
    const val ANT_MESSAGE_SIZE = 8 // Standard ANT message payload is 8 bytes
}
