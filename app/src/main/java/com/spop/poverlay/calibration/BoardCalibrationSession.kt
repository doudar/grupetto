package com.spop.poverlay.calibration

import kotlinx.coroutines.*

/** Caller serializes operations. Every write requires stopped pedals, exact AD, backup and readback.
 * A lost acknowledgement never triggers a retry. This engine can be tested without hardware.
 */
internal class BoardCalibrationSession(private val port: CalibrationBoardTransport,
    private val store: BoardJournalStore, private val settle: suspend () -> Unit = { delay(400) }) {
    private var journal: BoardWriteJournal? = null
    val nextIndex: Int? get() = journal?.let { j ->
        (1..29).firstOrNull { i -> j.expected[i] != j.target[i] &&
            validCalibrationTable(j.expected.toMutableList().apply { this[i] = j.target[i] }) }
    }
    val targetAd get() = nextIndex?.let { journal!!.target[it] }
    val done get() = journal?.let { it.expected == it.target && it.pendingIndex == null } == true
    private suspend fun checkedTable(): List<Int> {
        val first = port.readTable()
        settle()
        val second = port.readTable()
        check(first.size == 31 && first == second) { "Board table changed between reads. Nothing further written." }
        return first
    }
    suspend fun prepare(capture: CalibrationCapture, fit: CalibrationFit, restore: Boolean = false) {
        check(port.position().rpm == 0) { "Stop pedaling before writing the bike table." }
        val previous = store.load()
        val actual = checkedTable()
        val next = if (restore) {
            val backup = checkNotNull(previous) { "No board backup is available." }
            check(backup.frameSerial == capture.frameSerial && backup.original == capture.baseline) { "Backup belongs to a different capture." }
            check(actual.indices.all { i -> actual[i] == backup.original[i] || actual[i] == backup.target[i] || actual[i] == backup.expected[i] ||
                i == backup.pendingIndex }) { "Board differs from the saved write session. Manual recovery is required." }
            BoardWriteJournal(backup.original, backup.original, actual, capture.frameSerial, restoring = true)
        } else {
            check(previous == null || previous.complete && previous.expected == previous.original) {
                "An earlier board-write session exists. Restore its backup before a new calibration."
            }
            check(fit.ready && fit.supportedPoints.size >= 4 && fit.validationCount >= 12) { "More ride data needed before writing." }
            check(actual == capture.baseline) { "Board table does not match the captured baseline." }
            check(validCalibrationTable(fit.points) && fit.points.first() == actual.first() && fit.points.last() == actual.last()) {
                "Candidate table or factory endpoints are invalid."
            }
            check(fit.points.indices.all { it in fit.supportedPoints || fit.points[it] == actual[it] }) { "Unsupported point changed." }
            BoardWriteJournal(actual, fit.points, actual, capture.frameSerial)
        }
        check(validCalibrationTable(next.original) && validCalibrationTable(next.target)) { "Invalid backup or target table." }
        journal = next.copy(complete = next.expected == next.target)
        store.save(journal!!)
        check(done || nextIndex != null) { "No safe next point. Manual recovery is required." }
    }
    suspend fun writeNext(): Boolean {
        val current = checkNotNull(journal) { "Read and back up the board first." }
        check(current.pendingIndex == null) { "Previous write was not verified. Restore the backup." }
        val index = checkNotNull(nextIndex) { "No point to write." }
        check(checkedTable() == current.expected) { "Board changed since backup. Nothing written." }
        // Check again after the slower table reads, immediately before issuing the command.
        repeat(3) {
            val position = port.position()
            check(position.rpm == 0 && position.ad == current.target[index]) { "Stop pedals and hold the knob exactly on target." }
            if (it < 2) settle()
        }
        val pending = current.copy(pendingIndex = index)
        store.save(pending) // A failed disk write must prevent the hardware write.
        journal = pending
        // Once dispatched, finish bounded readback and journaling even if the dialog is closed.
        return withContext(NonCancellable) {
            runCatching { port.capturePoint(index) } // An acknowledgement is not proof of persistence.
            settle()
            val expected = current.expected.toMutableList().apply { this[index] = current.target[index] }
            val actual = checkedTable()
            check(actual == expected) { "Write not verified. Session stopped; restore the saved backup before retrying." }
            val saved = current.copy(expected = actual, complete = actual == current.target)
            store.save(saved)
            journal = saved
            saved.complete
        }
    }
}
