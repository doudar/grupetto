package com.spop.poverlay.control

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Bike+ power as reported by the mainboard is a near-fixed function of resistance
 * level and cadence: power ≈ A(level) · (cadence / 90)^1.7. Fitted from 3,662
 * one-second samples of Bike+ telemetry (levels 18–63, 50–110 RPM), the model's
 * mean absolute error is about 2 W. The seed curve is refined online from steady
 * readings so a particular bike's calibration is learned during the ride.
 */
class PowerModel {
    companion object {
        const val CADENCE_EXPONENT = 1.7f
        const val REFERENCE_CADENCE = 90f
        private const val LEARN_RATE = .2f
        private const val MAX_CORRECTION = 1.5f

        /** Seed watts at 90 RPM: exp(1.491 + 0.108267·r − 0.000584·r²), within ~3 W for levels 39–52. */
        fun seedWatts(level: Int): Float {
            val r = level.coerceIn(0, 100).toFloat()
            return exp(1.491016f + .108267f * r - .000584f * r * r)
        }
    }

    /** Learned watts at 90 RPM per level; NaN until observed. */
    private val learned = FloatArray(101) { Float.NaN }

    fun normalize(power: Float, cadence: Float) = power / (cadence / REFERENCE_CADENCE).pow(CADENCE_EXPONENT)

    /** Record a steady reading (resistance unchanged, normal cadence). */
    fun learn(level: Int, power: Float, cadence: Float) {
        if (level !in 0..100 || cadence < 50f || power <= 5f || !power.isFinite()) return
        val observed = normalize(power, cadence)
        val seed = seedWatts(level)
        if (observed !in seed / MAX_CORRECTION..seed * MAX_CORRECTION) return
        val previous = learned[level]
        learned[level] = if (previous.isNaN()) observed else previous + LEARN_RATE * (observed - previous)
    }

    /** Watts at 90 RPM for a level, using the learned value or a seed scaled by the nearest learned level. */
    fun wattsAt90(level: Int): Float {
        val l = level.coerceIn(0, 100)
        if (!learned[l].isNaN()) return learned[l]
        for (distance in 1..5) {
            for (n in intArrayOf(l - distance, l + distance)) {
                if (n in 0..100 && !learned[n].isNaN())
                    return seedWatts(l) * (learned[n] / seedWatts(n)).coerceIn(1 / MAX_CORRECTION, MAX_CORRECTION)
            }
        }
        return seedWatts(l)
    }

    /** Fractional resistance level predicted to produce [watts] at [cadence], or null outside the bike's range. */
    fun levelFor(watts: Float, cadence: Float): Float? {
        if (cadence < 50f || !watts.isFinite() || watts <= 0f) return null
        val wanted = normalize(watts, cadence)
        var previous = wattsAt90(0)
        if (wanted <= previous) return 0f
        for (level in 1..100) {
            val current = wattsAt90(level)
            if (current >= wanted && current > previous)
                return level - 1 + (wanted - previous) / (current - previous)
            previous = maxOf(previous, current)
        }
        return 100f
    }

    fun isLearned(level: Int) = level in 0..100 && !learned[level].isNaN()
    @Suppress("unused") fun error(level: Int, power: Float, cadence: Float) = abs(normalize(power, cadence) - wattsAt90(level))
}
