package com.spop.poverlay.antplus

/**
 * ANT+ device profile IDs and message formats
 * Reference: https://www.thisisant.com/developer/ant-plus/device-profiles/
 */
object AntPlusConstants {
    // ANT+ Device Type IDs
    const val DEVICE_TYPE_POWER_METER = 11
    const val DEVICE_TYPE_SPEED_CADENCE = 121
    const val DEVICE_TYPE_HRM = 120

    // Common ANT+ settings
    const val ANT_RF_FREQ = 57 // 2457MHz
    // Some head units are stricter; keep a fallback tx type for discovery retries.
    val TRANSMISSION_TYPES_TO_TRY = intArrayOf(0x05, 0x01)
    const val DEVICE_NUMBER = 1

    // Channel Periods (Hz = 32768 / Period)
    const val POWER_METER_PERIOD = 8182 // ANT+ Bike Power profile period (~4.00Hz)
    const val SPEED_CADENCE_PERIOD = 8086 // ~4.052Hz
    const val HRM_PERIOD = 8070 // ANT+ HRM standard period (~4.06Hz)

    // Power Meter specific Pages
    const val POWER_METER_PAGE_STANDARD = 16
    const val PAGE_MANUFACTURER_INFO = 80
    const val PAGE_PRODUCT_INFO = 81
    const val COMMON_PAGE_ROTATION_INTERVAL = 61
    const val MANUFACTURER_PAGE_SLOT = 15
    const val PRODUCT_PAGE_SLOT = 30

    // Common-page metadata for ANT+ discovery/identification
    const val HARDWARE_REVISION = 1
    const val MANUFACTURER_ID = 12345 // Custom ID for Grupetto
    const val SOFTWARE_REVISION_MAIN = 1
    const val SOFTWARE_REVISION_SUPPLEMENTAL = 0

    // HRM specific
    const val HRM_PAGE_DATA = 0 // Main HRM data page (page 0)

    // ANT+ message sizes
    const val ANT_MESSAGE_SIZE = 8 // Standard ANT message payload is 8 bytes
}
