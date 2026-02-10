package com.spop.poverlay.util

import android.os.Build
import kotlin.math.pow
import kotlin.math.sqrt

private const val PelotonBrand = "Peloton"

val IsRunningOnPeloton = Build.BRAND == PelotonBrand

/**
 * Check if the device is a G700 CrossTrainer bike.
 * The G700 uses a different sensor interface than the regular Bike+.
 */
val IsG700CrossTrainer = Build.MODEL.contains("G700", ignoreCase = true)

/**
 * All Peloton bikes start with model "PLTN-T". Treadmills start with "PLTN-TR", so this might also
 * apply to them, but it will be interesting if anything works on them here.
 * Note: G700 is handled separately.
 */
val IsBikePlus = Build.MODEL.contains("PLTN-T")


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
