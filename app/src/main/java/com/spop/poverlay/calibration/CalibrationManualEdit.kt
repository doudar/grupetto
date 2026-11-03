package com.spop.poverlay.calibration

import kotlin.math.*

internal enum class CalibrationEditScope(val label: String) {
    Cell("Cell"), Cadence("Cadence row"), Resistance("Resistance column"), All("Whole table");

    fun indices(selected: Int): Set<Int> {
        require(selected in 0 until 496)
        return (0 until 496).filterTo(mutableSetOf()) {
            when (this) {
                Cell -> it == selected
                Cadence -> it % 16 == selected % 16
                Resistance -> it / 16 == selected / 16
                All -> true
            }
        }
    }
}

/** Pure editing operations. No fake pedal samples, confidence, or validation scores are produced. */
internal object CalibrationManualEdit {
    private val minimum = ln(.65)
    private val maximum = ln(1.35)
    fun percent(gain: Double) = 100 * expm1(gain)

    private fun change(table: GrupettoCalibrationTable, gains: List<Double>, coverage: Set<Int>): GrupettoCalibrationTable {
        require(gains.size == 496 && gains.all { it.isFinite() })
        val bounded = gains.map { it.coerceIn(minimum, maximum) }
        val manual = table.manualCoverage + coverage
        val counts = table.effectiveCellSampleCounts().mapIndexed { index, count ->
            if (index in coverage) count.coerceAtLeast(1) else count
        }
        return table.copy(gains = bounded, manualCoverage = manual, cellSampleCounts = counts,
            cells = counts.count { it > 0 }, confidence = counts.map { it / 10.0 },
            activeComparison = null, ready = true,
            correctionPercent = bounded.indices.map {
                if (counts[it] > 0) percent(bounded[it]) else null
            })
    }

    /** Set/nudge only the explicitly selected cells. The legacy blend argument is ignored;
     * neighbor changes require the separate, explicit Smooth or Fill gaps operation. */
    @Suppress("UNUSED_PARAMETER")
    fun setPercent(table: GrupettoCalibrationTable, selected: Set<Int>, value: Double, blend: Boolean): GrupettoCalibrationTable {
        require(value.isFinite() && value in -35.0..35.0)
        return paint(table, selected) { ln1p(value / 100) }
    }

    @Suppress("UNUSED_PARAMETER")
    fun nudge(table: GrupettoCalibrationTable, selected: Set<Int>, amount: Double, blend: Boolean): GrupettoCalibrationTable {
        require(amount.isFinite())
        return paint(table, selected) {
            ln1p((percent(table.gains[it]) + amount).coerceIn(-35.0, 35.0) / 100)
        }
    }

    private fun paint(table: GrupettoCalibrationTable, selected: Set<Int>, value: (Int) -> Double): GrupettoCalibrationTable {
        require(selected.isNotEmpty() && selected.all { it in 0 until 496 })
        return change(table, table.gains.mapIndexed { index, gain -> if (index in selected) value(index) else gain }, selected)
    }

    /** Inverse-distance interpolation; outside coverage this extends the nearest trend.
     * Known cells are fixed. Generated cells are marked manual, never measured.
     */
    fun fillGaps(table: GrupettoCalibrationTable): GrupettoCalibrationTable {
        val known = table.correctionPercent.indices.filter { table.correctionPercent[it] != null }
        if (known.isEmpty() || known.size == 496) return table
        val gains = table.gains.mapIndexed { index, gain ->
            if (index in known) gain else {
                val neighbors = known.map { source ->
                    val distance = ((index / 16 - source / 16) / 2.5).pow(2) +
                        ((index % 16 - source % 16) / 3.0).pow(2)
                    source to distance
                }.sortedBy { it.second }.take(8)
                val weight = neighbors.sumOf { 1 / it.second.pow(2) }
                neighbors.sumOf { table.gains[it.first] / it.second.pow(2) } / weight
            }
        }
        return change(table, gains, (0 until 496).toSet() - known.toSet())
    }

    fun smooth(table: GrupettoCalibrationTable, selected: Set<Int>): GrupettoCalibrationTable = change(table,
        table.gains.mapIndexed { index, gain ->
            if (index !in selected) gain else {
                val neighbors = buildList {
                    for (dx in -1..1) for (dy in -1..1) {
                        val x = index / 16 + dx; val y = index % 16 + dy
                        if (x in 0..30 && y in 0..15) add(x * 16 + y)
                    }
                }
                .5 * gain + .5 * neighbors.map { table.gains[it] }.average()
            }
        }, selected)

    fun soften(table: GrupettoCalibrationTable, selected: Set<Int>): GrupettoCalibrationTable = change(table,
        table.gains.mapIndexed { index, gain -> if (index in selected) ln1p(expm1(gain) * .8) else gain }, selected)

    /** Reset the shared correction, not a hidden recorded/manual layer. Undo remains available. */
    fun resetCorrections(table: GrupettoCalibrationTable): GrupettoCalibrationTable =
        change(table, List(496) { 0.0 }, table.gains.indices.toSet())

}

/** Snapshots make tools, cell changes and resets equally reversible. */
internal class CalibrationEditHistory(val initial: GrupettoCalibrationTable) {
    private val undo = ArrayDeque<GrupettoCalibrationTable>()
    private val redo = ArrayDeque<GrupettoCalibrationTable>()
    var current = initial
        private set
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    fun commit(next: GrupettoCalibrationTable): GrupettoCalibrationTable {
        if (next != current) {
            undo.addLast(current)
            if (undo.size > 100) undo.removeFirst()
            current = next; redo.clear()
        }
        return current
    }
    fun undo(): GrupettoCalibrationTable {
        if (canUndo) { redo.addLast(current); current = undo.removeLast() }
        return current
    }
    fun redo(): GrupettoCalibrationTable {
        if (canRedo) { undo.addLast(current); current = redo.removeLast() }
        return current
    }
}
