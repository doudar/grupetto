package com.spop.poverlay.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import timber.log.Timber
import kotlin.math.pow
import kotlin.math.sqrt

private const val PelotonBrand = "Peloton"

val IsRunningOnPeloton by lazy { Build.BRAND == PelotonBrand }

/**
 * Check if the device is a G700 CrossTrainer bike.
 * The G700 uses a different sensor interface than the regular Bike+.
 */
/** G700 model strings include either legacy "G700" or newer "PLTN-ATR" prefixes. */
internal fun isG700CrossTrainerModel(model: String): Boolean {
    return model.contains("G700", ignoreCase = true) || model.startsWith("PLTN-ATR", ignoreCase = true)
}

val IsG700CrossTrainer by lazy { isG700CrossTrainerModel(Build.MODEL) }

/**
 * Check whether the device runs the "Topaz" tablet (`PLTN-TTR01` / `PLTN-TTR01-2`).
 *
 * This identifies the TABLET, **not** the machine. Peloton's own FactoryTest APK defines
 * `isTopaz() { return Build.MODEL.startsWith("PLTN-TTR01"); }`, and
 * `com.peloton.sensor.client.HardwareType.isTopaz()` returns true for TITAN (Bike+),
 * PRISM (Tread) and CAESAR (Row) alike — all three ship the same tablet. The model string
 * therefore CANNOT be used to select the tread code path; use [isTreadPlatform] with
 * [readPelotonPlatform] instead. Kept only for logging/diagnostics.
 */
internal fun isTreadModel(model: String): Boolean {
    return model.startsWith("PLTN-TTR01", ignoreCase = true)
}

/** `Settings.Global` key that `affernetservice` writes after USB VID/PID detection. */
private const val PelotonPlatformSetting = "peloton_platform"

/** Tread (PRISM) platform value; variants such as `prism-l` / `prism-b` also occur. */
private const val TreadPlatform = "prism"

/**
 * The machine discriminator: `Settings.Global["peloton_platform"]`, written by
 * `affernetservice` (`PlatformGlobal.java`) from the USB VID/PID of the attached
 * mainboard. Known values: `prism` (Tread), `titan` (Bike+), `caesar` (Row),
 * `aurora` (Tread+), `v1` (Bike Gen 1). Returns null when unset or unreadable.
 */
fun readPelotonPlatform(context: Context): String? = try {
    Settings.Global.getString(context.contentResolver, PelotonPlatformSetting)
} catch (throwable: Throwable) {
    Timber.w(throwable, "Unable to read %s", PelotonPlatformSetting)
    null
}

/**
 * True only for the Tread (PRISM) platform.
 *
 * A missing, empty or unreadable value is deliberately NOT a Tread: putting a Tread on
 * the bike HUD is cosmetic, whereas binding a Bike+ to `ITreadInterface` is not. We do
 * not fall back to the model string, because the model identifies the shared tablet.
 *
 * Prefixed variants (`prism-l`, `prism-b`) are accepted because some builds write the
 * variant into `peloton_platform` as well as `peloton_platform_variant`; the separator is
 * required so unrelated values that merely start with "prism" do not match.
 */
fun isTreadPlatform(platform: String?): Boolean {
    val value = platform?.trim()?.lowercase() ?: return false
    return value == TreadPlatform || value.startsWith("$TreadPlatform-")
}

/**
 * All Peloton bikes start with model "PLTN-T". Treadmills and Rows share that tablet
 * prefix too, so this is only consulted after [isTreadPlatform] has ruled out a Tread.
 * Note: G700 is handled separately.
 */
internal fun isBikePlusModel(model: String): Boolean = model.contains("PLTN-T")

val IsBikePlus by lazy { isBikePlusModel(Build.MODEL) }


fun calculateSpeedFromPelotonV1Power(power: Float) =
        if (power < 0.1f) {
            0f
        } else {
            // https://ihaque.org/posts/2020/12/25/pelomon-part-ib-computing-speed/
            val pwrSqrt = sqrt(power)
            if (power < 26f) {
                0.057f - (0.172f * pwrSqrt) + (0.759f * pwrSqrt.pow(2)) - (0.079f * pwrSqrt.pow(3))
            } else {
                -1.635f + (2.325f * pwrSqrt) - (0.064f * pwrSqrt.pow(2)) + (0.001f * pwrSqrt.pow(3))
            }
        }
