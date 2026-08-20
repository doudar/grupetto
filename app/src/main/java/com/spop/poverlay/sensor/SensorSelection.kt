package com.spop.poverlay.sensor

import android.content.Context
import com.spop.poverlay.util.IsRunningOnPeloton
import com.spop.poverlay.util.isBikePlusModel
import com.spop.poverlay.util.isG700CrossTrainerModel
import com.spop.poverlay.util.isTreadPlatform
import com.spop.poverlay.util.readPelotonPlatform
import android.os.Build

/** The sensor interface implementation chosen for the current device. */
enum class SensorSelection { Tread, BikePlus, BikeV1, Dummy }

/**
 * Pure selection logic for which [com.spop.poverlay.sensor.interfaces.SensorInterface]
 * to construct. Kept free of Android types so it can be unit-tested with plain booleans.
 *
 * Tread is checked FIRST (before the Bike+/V1 branch) because Bike+, Tread and Row all
 * report the same tablet model (`PLTN-TTR01`), so the Bike+ model test also matches a
 * Tread. [isTread] must therefore come from the `peloton_platform` discriminator, never
 * from the model string (research doc section 8).
 */
fun selectSensor(
    isRunningOnPeloton: Boolean,
    isTread: Boolean,
    isBikePlusOrG700: Boolean
): SensorSelection {
    if (!isRunningOnPeloton) return SensorSelection.Dummy
    if (isTread) return SensorSelection.Tread
    return if (isBikePlusOrG700) SensorSelection.BikePlus else SensorSelection.BikeV1
}

/**
 * [selectSensor] driven by the two device facts: the tablet [model] (`Build.MODEL`) and
 * the machine [platform] (`Settings.Global["peloton_platform"]`, nullable). Pure, so the
 * real device combinations are unit-testable.
 */
fun selectSensorForDevice(
    isRunningOnPeloton: Boolean,
    model: String,
    platform: String?
): SensorSelection = selectSensor(
    isRunningOnPeloton = isRunningOnPeloton,
    isTread = isTreadPlatform(platform),
    isBikePlusOrG700 = isG700CrossTrainerModel(model) || isBikePlusModel(model)
)

/** Reads the platform from [context] and applies [selectSensorForDevice]. */
fun selectSensorForCurrentDevice(context: Context): SensorSelection = selectSensorForDevice(
    isRunningOnPeloton = IsRunningOnPeloton,
    model = Build.MODEL,
    platform = readPelotonPlatform(context)
)
