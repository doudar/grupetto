package com.spop.poverlay.control

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Bike+ power as reported by the mainboard is a near-fixed function of resistance
 * level and cadence: power ≈ A(level) · (cadence / 90)^1.7. The seed curve A was
 * refitted (Nov 2026) from 6,018 steady one-second samples on 14 rides (levels 39–71,
 * 69–110 RPM; mean absolute error 2.7 W). The earlier curve, fitted mostly below
 * level 55, under-predicted power by 11–15 W above it, so big climbs, above all at low
 * cadence, asked for too many levels (alone it asks L73–74 for 362 W at 71 RPM; the refit
 * L70–71, which a warm bike used on Nov 8). The cadence exponent refits to 1.76, so 1.7 is kept. The seed is refined online from
 * steady readings so a particular bike's calibration is learned during the ride.
 */
class PowerModel {
    companion object {
        const val CADENCE_EXPONENT = 1.7f
        const val REFERENCE_CADENCE = 90f
        private const val LEARN_RATE = .2f
        private const val MAX_CORRECTION = 1.5f

        /** Seed watts at 90 RPM: exp(1.45091 + 0.108422·r − 0.000564·r²); per-level bias within ~4 W for levels 43–64. */
        fun seedWatts(level: Int): Float {
            val r = level.coerceIn(0, 100).toFloat()
            return exp(1.45091f + .108422f * r - .000564f * r * r)
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

    /** Watts predicted at [level] and [cadence]. */
    fun wattsAt(level: Int, cadence: Float) = wattsAt90(level) * (cadence / REFERENCE_CADENCE).pow(CADENCE_EXPONENT)

    /**
     * The whole resistance level predicted closest to [watts]. The Bike+ brake only holds
     * whole levels, so rounding a fractional level can land a full step past target.
     */
    fun nearestLevelFor(watts: Float, cadence: Float): Int? {
        val level = levelFor(watts, cadence) ?: return null
        val below = kotlin.math.floor(level).toInt().coerceIn(0, 100)
        val above = (below + 1).coerceAtMost(100)
        return if (abs(wattsAt(below, cadence) - watts) <= abs(wattsAt(above, cadence) - watts)) below else above
    }

    fun isLearned(level: Int) = level in 0..100 && !learned[level].isNaN()
    @Suppress("unused") fun error(level: Int, power: Float, cadence: Float) = abs(normalize(power, cadence) - wattsAt90(level))
}
