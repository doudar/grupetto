package com.spop.poverlay.calibration

import kotlin.math.floor

/** Reference constants extracted read-only from Affernet2.4.490 (APK SHA256
 * 4bbc926f581e6132ad3b92cec0718920a8c3f5ab3670295b013276165117690f).
 * These curves express cadence shape, not a claim about the accuracy of board watts.
 * Raw measurements and displayed pedal power never pass through this class.
 */
class PelotonCadenceReference internal constructor(private val baseline: List<Int>, val model: Model) {
    private val baselineValid = validCalibrationTable(baseline)
    enum class Model(val id: String) { Rexon("rexon_2_4_490"), Tonic("tonic_2_4_490") }
    data class PairAtCell(val rpm: Double, val nativeWatts: Double, val pedalWatts: Double, val factor: Double)

    fun normalize(sample: CalibrationSample, measured: Boolean = false): PairAtCell? {
        val cell = calibrationCellIndex(baseline, sample.ad, sample.rpm) ?: return null
        val target = 45.0 + cell % 16 * 5
        val original = watts(sample.ad, sample.rpm) ?: return null
        val centered = watts(sample.ad, target) ?: return null
        if (original <= 0 || centered <= 0) return null
        val factor = centered / original
        if (!factor.isFinite() || factor !in .5..2.0 || sample.nativeWatts !in CalibrationPowerRange) return null
        val pedal = if (measured) sample.measuredPedalWatts else sample.pedalWatts
        if (!pedal.isFinite() || pedal <= 0) return null
        return PairAtCell(target, sample.nativeWatts * factor, pedal * factor, factor)
    }

    fun watts(ad: Double, rpm: Double): Double? {
        if (!baselineValid || !rpm.isFinite() || rpm !in 45.0..120.0) return null
        val coordinate = calibrationCoordinate(baseline, ad) ?: return null
        return when (model) {
            Model.Tonic -> {
                val row = floor(coordinate).toInt().coerceIn(0, 30)
                val upper = (row + 1).coerceAtMost(30)
                val fraction = coordinate - row
                val c = tonicRpm.indexOfLast { it <= rpm }.coerceIn(0, tonicRpm.lastIndex - 1)
                fun at(column: Int): Double = (tonic[row][column] +
                    (tonic[upper][column] - tonic[row][column]) * fraction) / 10.0
                at(c) + (at(c + 1) - at(c)) * (rpm - tonicRpm[c]) / (tonicRpm[c + 1] - tonicRpm[c])
            }
            Model.Rexon -> {
                // Vendor AD -> integer resistance -> internal row mapping, not displayed level.
                val interval = floor(coordinate).toInt().coerceIn(0, 29)
                val raw = ad.toInt()
                val resistance = ((interval / 3) * 100 + (interval % 3) * 33 +
                    (raw - baseline[interval]) * 33 / (baseline[interval + 1] - baseline[interval])) / 10
                val row = (resistance * 10 / 33).coerceIn(0, 30)
                val fraction = resistance * 10 % 33
                val column = floor(rpm / 10).toInt().coerceIn(0, 16)
                // Preserve vendor integer interpolation across resistance. Extend its cadence
                // interpolation continuously so fractional RPM is not rounded before normalizing.
                fun at(c: Int) = rexon[row][c] + (rexon[row + 1][c] - rexon[row][c]) * fraction / 33
                at(column) + (at(column + 1) - at(column)) * (rpm / 10 - column)
            }
        }
    }

    companion object {
        fun forCapture(capture: CalibrationCapture): PelotonCadenceReference? {
            val serial = capture.frameSerial.trim()
            if (capture.serviceVersion != 2040490L || serial.length != 15 || !validCalibrationTable(capture.baseline)) return null
            // Affernet PowerFactory selection with a validated Lxx table.
            return PelotonCadenceReference(capture.baseline, if (serial.startsWith("R")) Model.Rexon else Model.Tonic)
        }
        private val tonicRpm = doubleArrayOf(0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0, 110.0, 120.0, 130.0, 140.0, 150.0, 160.0)
        private val rexon = arrayOf(
        intArrayOf(0, 0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 23, 26, 29, 32, 35, 35),
        intArrayOf(0, 1, 2, 5, 7, 9, 11, 13, 16, 18, 21, 23, 25, 28, 31, 34, 37, 37),
        intArrayOf(0, 1, 2, 5, 7, 9, 12, 14, 16, 19, 21, 24, 27, 30, 33, 36, 40, 40),
        intArrayOf(0, 1, 2, 5, 7, 9, 12, 14, 17, 20, 22, 25, 27, 31, 35, 39, 43, 43),
        intArrayOf(0, 1, 2, 5, 8, 10, 13, 16, 19, 23, 26, 30, 34, 36, 40, 44, 49, 49),
        intArrayOf(0, 1, 3, 6, 9, 12, 15, 19, 23, 26, 31, 36, 41, 46, 51, 57, 63, 63),
        intArrayOf(0, 1, 3, 7, 10, 14, 18, 23, 28, 33, 40, 45, 52, 59, 66, 73, 81, 81),
        intArrayOf(0, 1, 3, 8, 11, 16, 22, 27, 34, 40, 48, 56, 65, 74, 83, 93, 103, 103),
        intArrayOf(0, 1, 4, 10, 15, 21, 29, 37, 46, 55, 66, 77, 89, 101, 113, 126, 139, 139),
        intArrayOf(0, 1, 5, 12, 18, 26, 35, 46, 57, 69, 82, 96, 110, 114, 129, 144, 161, 161),
        intArrayOf(0, 1, 6, 13, 22, 31, 44, 57, 71, 86, 102, 120, 139, 158, 177, 197, 218, 218),
        intArrayOf(0, 2, 7, 16, 26, 38, 53, 68, 86, 106, 126, 148, 171, 194, 218, 243, 268, 268),
        intArrayOf(0, 2, 9, 20, 32, 47, 65, 87, 110, 135, 161, 187, 216, 245, 274, 306, 338, 338),
        intArrayOf(0, 3, 10, 23, 38, 59, 80, 105, 132, 161, 194, 228, 262, 298, 334, 372, 410, 410),
        intArrayOf(0, 3, 12, 28, 47, 70, 97, 127, 161, 197, 235, 275, 317, 361, 405, 451, 497, 497),
        intArrayOf(0, 3, 14, 32, 54, 81, 113, 149, 188, 231, 276, 323, 372, 423, 476, 529, 584, 584),
        intArrayOf(0, 4, 16, 37, 62, 96, 135, 177, 223, 271, 323, 379, 436, 493, 552, 611, 672, 672),
        intArrayOf(0, 5, 19, 44, 74, 112, 155, 204, 257, 313, 373, 436, 501, 568, 637, 706, 777, 777),
        intArrayOf(0, 6, 22, 50, 86, 129, 179, 237, 298, 364, 433, 506, 580, 655, 732, 811, 892, 892),
        intArrayOf(0, 6, 26, 58, 98, 147, 206, 270, 340, 414, 493, 575, 659, 745, 833, 923, 1013, 1013),
        intArrayOf(0, 7, 30, 67, 113, 170, 235, 308, 387, 471, 560, 651, 746, 845, 948, 1055, 1165, 1165),
        intArrayOf(0, 8, 33, 76, 129, 191, 264, 345, 434, 528, 626, 728, 833, 941, 1052, 1167, 1286, 1286),
        intArrayOf(0, 9, 37, 82, 142, 213, 295, 386, 484, 588, 698, 810, 925, 1043, 1164, 1289, 1418, 1418),
        intArrayOf(0, 10, 42, 93, 159, 236, 326, 427, 535, 649, 769, 892, 1017, 1144, 1273, 1406, 1543, 1543),
        intArrayOf(0, 11, 45, 101, 172, 258, 358, 467, 584, 708, 838, 971, 1105, 1240, 1376, 1514, 1654, 1654),
        intArrayOf(0, 12, 49, 110, 187, 282, 389, 507, 634, 767, 906, 1049, 1193, 1338, 1484, 1632, 1782, 1782),
        intArrayOf(0, 13, 54, 121, 205, 304, 419, 545, 681, 823, 971, 1122, 1274, 1427, 1581, 1737, 1895, 1895),
        intArrayOf(0, 14, 57, 127, 217, 326, 449, 584, 727, 878, 1034, 1194, 1355, 1517, 1680, 1845, 2012, 2012),
        intArrayOf(0, 15, 60, 134, 229, 344, 472, 614, 766, 923, 1086, 1253, 1422, 1593, 1766, 1941, 2119, 2119),
        intArrayOf(0, 16, 63, 143, 242, 362, 497, 645, 803, 968, 1138, 1312, 1487, 1663, 1840, 2019, 2200, 2200),
        intArrayOf(0, 17, 67, 150, 253, 378, 519, 674, 838, 1009, 1186, 1366, 1550, 1738, 1930, 2126, 2326, 2326),
        intArrayOf(0, 17, 67, 150, 253, 378, 519, 674, 838, 1009, 1186, 1366, 1550, 1738, 1930, 2126, 2326, 2326))
        private val tonic = arrayOf(
        intArrayOf(0, 1, 5, 11, 20, 31, 45, 63, 81, 98, 121, 143, 163, 186, 207, 234, 264, 294, 326, 360),
        intArrayOf(0, 1, 5, 11, 20, 32, 47, 69, 87, 109, 129, 151, 174, 196, 223, 243, 264, 284, 304, 325),
        intArrayOf(0, 1, 5, 12, 21, 33, 48, 69, 89, 112, 134, 158, 184, 207, 233, 256, 279, 301, 325, 348),
        intArrayOf(0, 1, 5, 12, 21, 34, 49, 69, 90, 115, 140, 165, 194, 218, 245, 267, 289, 312, 334, 356),
        intArrayOf(0, 1, 5, 13, 24, 37, 54, 77, 102, 131, 161, 194, 228, 261, 298, 336, 377, 419, 463, 509),
        intArrayOf(0, 1, 6, 14, 26, 40, 59, 85, 114, 147, 183, 223, 261, 305, 351, 404, 461, 521, 585, 653),
        intArrayOf(0, 1, 7, 17, 30, 47, 68, 101, 139, 182, 229, 282, 336, 395, 458, 529, 604, 684, 769, 859),
        intArrayOf(0, 2, 8, 19, 34, 53, 77, 116, 164, 218, 275, 340, 410, 485, 565, 652, 744, 842, 947, 1057),
        intArrayOf(0, 2, 10, 24, 43, 68, 98, 150, 214, 287, 366, 453, 547, 650, 760, 875, 999, 1130, 1269, 1415),
        intArrayOf(0, 3, 13, 29, 52, 82, 118, 184, 263, 355, 457, 565, 685, 813, 954, 1100, 1256, 1421, 1597, 1782),
        intArrayOf(0, 3, 15, 34, 61, 95, 138, 222, 322, 439, 569, 712, 870, 1035, 1214, 1402, 1603, 1817, 2045, 2285),
        intArrayOf(0, 4, 17, 39, 70, 109, 158, 259, 381, 522, 681, 860, 1057, 1256, 1474, 1705, 1952, 2215, 2494, 2789),
        intArrayOf(0, 5, 22, 49, 88, 137, 198, 326, 482, 662, 866, 1094, 1341, 1601, 1877, 2166, 2474, 2802, 3150, 3518),
        intArrayOf(0, 6, 26, 59, 106, 166, 239, 393, 583, 802, 1051, 1329, 1626, 1945, 2281, 2628, 2998, 3392, 3808, 4248),
        intArrayOf(0, 7, 30, 69, 123, 193, 279, 464, 697, 965, 1270, 1606, 1966, 2348, 2752, 3171, 3618, 4092, 4595, 5126),
        intArrayOf(0, 8, 35, 79, 141, 221, 318, 536, 812, 1129, 1488, 1883, 2307, 2751, 3225, 3714, 4236, 4790, 5378, 5997),
        intArrayOf(0, 10, 41, 94, 167, 262, 378, 638, 965, 1340, 1762, 2224, 2719, 3242, 3794, 4360, 4963, 5602, 6279, 6993),
        intArrayOf(0, 12, 48, 109, 194, 303, 437, 740, 1119, 1552, 2037, 2565, 3133, 3734, 4363, 5004, 5687, 6410, 7174, 7979),
        intArrayOf(0, 14, 56, 127, 225, 353, 508, 863, 1301, 1803, 2368, 2981, 3639, 4334, 5057, 5796, 6581, 7413, 8291, 9216),
        intArrayOf(0, 16, 64, 144, 257, 402, 579, 987, 1483, 2055, 2698, 3397, 4145, 4934, 5751, 6589, 7480, 8423, 9419, 10467),
        intArrayOf(0, 18, 74, 166, 296, 462, 666, 1130, 1695, 2347, 3075, 3867, 4711, 5597, 6517, 7457, 8456, 9512, 10627, 11799),
        intArrayOf(0, 20, 83, 188, 334, 522, 752, 1274, 1907, 2638, 3452, 4338, 5276, 6261, 7281, 8327, 9436, 10609, 11846, 13146),
        intArrayOf(0, 23, 93, 209, 372, 582, 839, 1422, 2132, 2950, 3859, 4843, 5883, 6975, 8100, 9251, 10472, 11761, 13119, 14547),
        intArrayOf(0, 25, 102, 231, 411, 642, 925, 1570, 2358, 3263, 4265, 5348, 6491, 7689, 8919, 10176, 11508, 12913, 14393, 15948),
        intArrayOf(0, 28, 113, 254, 452, 707, 1018, 1726, 2588, 3576, 4668, 5845, 7083, 8375, 9706, 11050, 12470, 13967, 15541, 17192),
        intArrayOf(0, 30, 123, 277, 493, 771, 1111, 1881, 2818, 3889, 5070, 6341, 7674, 9063, 10492, 11924, 13436, 15029, 16701, 18452),
        intArrayOf(0, 33, 133, 299, 532, 831, 1198, 2031, 3038, 4188, 5454, 6808, 8230, 9705, 11217, 12740, 14346, 16036, 17809, 19667),
        intArrayOf(0, 35, 142, 321, 571, 892, 1285, 2180, 3258, 4487, 5839, 7276, 8784, 10346, 11942, 13556, 15259, 17050, 18929, 20897),
        intArrayOf(0, 37, 150, 338, 602, 941, 1356, 2301, 3437, 4727, 6146, 7654, 9231, 10864, 12530, 14215, 15990, 17856, 19814, 21862),
        intArrayOf(0, 39, 158, 356, 634, 990, 1426, 2422, 3616, 4969, 6453, 8033, 9679, 11383, 13119, 14873, 16721, 18663, 20698, 22827),
        intArrayOf(0, 41, 165, 372, 662, 1035, 1490, 2524, 3775, 5192, 6739, 8383, 10093, 11862, 13664, 15501, 17439, 19475, 21610, 23845))
    }
}

/** Derived input only: never replace CalibrationSample fields or the live pedal reading. */
internal fun calibrationCalculationPair(sample: CalibrationSample, reference: PelotonCadenceReference?,
    measured: Boolean = false): PelotonCadenceReference.PairAtCell =
    reference?.normalize(sample, measured) ?: PelotonCadenceReference.PairAtCell(sample.rpm,
        sample.nativeWatts, if (measured) sample.measuredPedalWatts else sample.pedalWatts, 1.0)
