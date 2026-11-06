package com.spop.poverlay.sensor

/** A pending GATT owns this sensor type just like a connected GATT does. */
internal fun shouldDiscoverSensor(
    running: Boolean,
    pickerOpen: Boolean,
    hasSavedTarget: Boolean,
    manuallyDisconnected: Boolean,
    hasGatt: Boolean
): Boolean = running && !hasGatt &&
    (pickerOpen || (hasSavedTarget && !manuallyDisconnected))
