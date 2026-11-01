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
        val offsets = bounded.indices.map { bounded[it] - table.gains[it] + table.manualOffsets.getOrElse(it) { 0.0 } }
        val manual = table.manualCoverage + coverage
        return table.copy(gains = bounded, manualOffsets = offsets, manualCoverage = manual,
            activeComparison = null, ready = true,
            correctionPercent = bounded.indices.map {
                if (it in manual || it in table.recordedCoverage) percent(bounded[it]) else null
            })
    }

    /** The same 2.5-resistance / 15-rpm Gaussian length scales as recorded calibration.
     * The selected value is exact; surrounding changes taper smoothly in log-power space.
     * Multiple selected cells share a normalized brush so a row never amplifies the edit.
     */
    fun setPercent(table: GrupettoCalibrationTable, selected: Set<Int>, value: Double, blend: Boolean): GrupettoCalibrationTable {
        require(value.isFinite() && value in -35.0..35.0)
        return paint(table, selected, blend) { ln1p(value / 100) - table.gains[it] }
    }

    fun nudge(table: GrupettoCalibrationTable, selected: Set<Int>, amount: Double, blend: Boolean): GrupettoCalibrationTable {
        require(amount.isFinite())
        return paint(table, selected, blend) {
            ln1p((percent(table.gains[it]) + amount).coerceIn(-35.0, 35.0) / 100) - table.gains[it]
        }
    }

    private fun paint(table: GrupettoCalibrationTable, selected: Set<Int>, blend: Boolean,
        delta: (Int) -> Double): GrupettoCalibrationTable {
        require(selected.isNotEmpty() && selected.all { it in 0 until 496 })
        val changes = selected.associateWith(delta)
        val coverage = selected.toMutableSet()
        val gains = table.gains.mapIndexed { index, gain ->
            if (index in selected) gain + changes.getValue(index)
            else if (!blend) gain
            else {
                var total = 0.0
                var weight = 0.0
                var strongest = 0.0
                changes.forEach { (source, correction) ->
                    val dx = (index / 16 - source / 16).toDouble()
                    val dy = (index % 16 - source % 16).toDouble()
                    val k = exp(-.5 * ((dx / 2.5).pow(2) + (dy / 3).pow(2)))
                    total += k * correction; weight += k; strongest = max(strongest, k)
                    if (abs(dx) <= 2 && abs(dy) <= 2) coverage += index
                }
                gain + if (weight > 0) strongest * total / weight else 0.0
            }
        }
        return change(table, gains, coverage)
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

    fun resetToRecorded(table: GrupettoCalibrationTable): GrupettoCalibrationTable {
        if (table.manualOffsets.isEmpty()) return table
        val gains = table.gains.indices.map { (table.gains[it] - table.manualOffsets[it]).coerceIn(minimum, maximum) }
        return table.copy(gains = gains, manualOffsets = emptyList(), manualCoverage = emptySet(), activeComparison = null,
            correctionPercent = gains.indices.map { if (it in table.recordedCoverage) percent(gains[it]) else null })
    }

    fun reapply(fitted: GrupettoCalibrationTable, previous: GrupettoCalibrationTable?): GrupettoCalibrationTable {
        if (previous == null || previous.baseline != fitted.baseline || previous.manualOffsets.isEmpty()) return fitted
        return change(fitted, fitted.gains.indices.map { fitted.gains[it] + previous.manualOffsets[it] }, previous.manualCoverage)
    }
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
