package com.spop.poverlay.calibration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CalibrationModelsTest {
    private val baseline = List(31) { 1000 + it * 100 }
    @Test fun localModelLearnsCadenceDependentCorrectionAndFadesOutsideCoverage() {
        val samples = (8..16 step 2).flatMap { x -> listOf(65, 80, 95).map { rpm ->
            val watts = 80.0 + x * 8
            CalibrationSample(baseline[x].toDouble(), rpm.toDouble(), watts, watts * (1.10 + (rpm - 80) * .001), (x * 100 + rpm).toLong())
        } }
        val table = GrupettoCalibrationFitter.fit(baseline, samples)!!
        assertTrue("${table.beforeRmse} -> ${table.afterRmse}", table.ready)
        assertTrue(table.corrected(200.0, 2200.0, 95.0) > table.corrected(200.0, 2200.0, 65.0))
        assertEquals(200.0, table.corrected(200.0, 4000.0, 80.0), .1)
        assertEquals(0.0, table.corrected(0.0, 2200.0, 80.0), 0.0)
        assertEquals(200.0, table.corrected(200.0, 2200.0, 10.0), 0.0)
        assertEquals(496, table.gains.size)
        assertTrue(table.confidence.all { it in 0.0..1.0 })
    }
    @Test fun sparseAndAlreadyAccurateDataCannotActivateAnUnsupportedModel() {
        val sample = CalibrationSample(2000.0, 80.0, 200.0, 220.0, 1)
        assertFalse(GrupettoCalibrationFitter.fit(baseline, listOf(sample))!!.ready)
        assertFalse(GrupettoCalibrationFitter.fit(baseline, List(20) { sample.copy(pedalWatts = 200.0, capturedAt = it.toLong()) })!!.ready)
    }
    @Test fun guidedTargetsAcceptOffCenterShiftsWithoutChangingMeasurements() {
        val guide = CalibrationGuide(baseline, 2000)
        val samples = List(3) { CalibrationSample(1875.0, 75.0, 150.0, 160.0, it.toLong()) }
        val cells = guide.cells(samples)
        assertEquals(6, cells.size)
        assertTrue(cells.first { it.point == 8 && it.rpm == 75 }.complete)
        assertEquals(1875.0, samples.first().ad, 0.0)
        assertFalse(guide.target(cells)!!.complete)
        guide.skip(guide.target(cells))
        assertEquals(12, guide.target(cells)!!.point)
    }
    @Test fun broadTargetBoundariesCountOnceAndMatchTheGreenBandOnUnevenTables() {
        val uneven = baseline.mapIndexed { i, value -> if (i == 8) value - 40 else value }
        val guide = CalibrationGuide(uneven, 2000)
        val samples = listOf(1700.0, 1899.0, 1900.0, 2099.0, 2100.0, 2299.0, 2300.0).mapIndexed { i, ad ->
            CalibrationSample(ad, 75.0, 150.0, 160.0, i.toLong())
        }
        val cells = guide.cells(samples)
        assertEquals(listOf(2, 2, 2), cells.filter { it.rpm == 75 }.map { it.repeats })
        assertEquals(6, cells.sumOf { it.repeats })
        val target = cells.first()
        assertEquals(-.5f, target.markerPosition(1700.0), 0f)
        assertEquals(0f, target.markerPosition(1760.0), 0f)
        assertEquals(.5f, target.markerPosition(1900.0), 0f)
        assertTrue(target.containsResistance(1899.0))
        assertFalse(target.containsResistance(1900.0))
        assertTrue(target.containsCadence(72.5))
        assertFalse(target.containsCadence(77.5))
        for (startAd in listOf(0, 5000)) {
            assertEquals(6, CalibrationGuide(baseline, startAd).cells(emptyList()).size)
        }
    }
    @Test fun actualRideDataProducesFiniteBoundedSurfaceWithoutInventingBoardPoints() {
        val actual = listOf(173,178,187,201,238,303,363,416,478,540,592,632,676,716,748,780,807,828,853,868,890,909,922,933,946,958,966,976,983,991,996)
        val values = listOf(listOf(559.0,76.0,57.0,47.0), listOf(559.0,78.0,59.0,51.0),
            listOf(559.0,91.0,76.0,66.5), listOf(559.0,81.0,63.5,55.5), listOf(629.0,79.0,84.0,93.0),
            listOf(720.0,80.0,130.5,128.0), listOf(733.0,80.5,150.0,166.5), listOf(733.0,91.0,186.0,207.5), listOf(830.0,92.0,329.0,363.5))
        val samples = values.mapIndexed { i, v -> CalibrationSample(v[0],v[1],v[2],v[3],i.toLong()) }
        val table = GrupettoCalibrationFitter.fit(actual, samples)!!
        println("Rider local fit: ${table.sampleCount} efforts, ${table.cells} cells; CV RMSE ${table.beforeRmse} -> ${table.afterRmse}; ready=${table.ready}")
        assertTrue(table.ready)
        assertTrue(table.beforeRmse - table.afterRmse < 1.0)
        assertTrue(table.afterRmse > table.beforeRmse * .95)
        assertTrue(table.gains.all { it.isFinite() })
        for (ad in 173..996 step 7) for (rpm in 45..120 step 5) assertTrue(table.corrected(200.0,ad.toDouble(),rpm.toDouble()) in 129.99..270.01)
        assertFalse(CalibrationFitter.fit(actual, samples).ready)
    }
    private class MemoryStore : BoardJournalStore {
        var value: BoardWriteJournal? = null
        var fail = false
        override fun load() = value
        override fun save(journal: BoardWriteJournal) { check(!fail) { "disk full" }; value = journal }
    }
    private inner class Port : CalibrationBoardTransport {
        var table = baseline.toMutableList()
        var ad = 2020
        var rpm = 0
        var ignoreWrite = false
        val commands = mutableListOf<Int>()
        override suspend fun position() = BoardPosition(ad, rpm)
        override suspend fun readTable() = table.toList()
        override suspend fun capturePoint(index: Int) { commands += index; if (!ignoreWrite) table[index] = ad }
        override fun close() {}
    }
    private val capture get() = CalibrationCapture("test-bike", "test-meter", 2040490, baseline, emptyList())
    private val fit get() = CalibrationFit(baseline.mapIndexed { i, p -> if (i in 10..13) p + 20 else p }, (10..13).toList(), validationCount = 12, ready = true)
    @Test fun boardWriterRequiresStoppedExactPositionBackupAndFullReadback() = runBlocking {
        val port = Port(); val store = MemoryStore(); val session = BoardCalibrationSession(port, store) {}
        session.prepare(capture, fit)
        assertEquals(baseline, store.value!!.original)
        port.rpm = 1
        assertTrue(runCatching { session.writeNext() }.isFailure)
        assertTrue(port.commands.isEmpty())
        port.rpm = 0; port.ad = 2019
        assertTrue(runCatching { session.writeNext() }.isFailure)
        assertTrue(port.commands.isEmpty())
        port.ad = 2020
        assertFalse(session.writeNext())
        assertEquals(listOf(10), port.commands)
        assertEquals(2020, store.value!!.expected[10])
        assertNull(store.value!!.pendingIndex)
    }
    @Test fun unverifiedWriteStopsWithoutRetryAndKeepsRecoveryJournal() = runBlocking {
        val port = Port(); val store = MemoryStore(); val session = BoardCalibrationSession(port, store) {}
        session.prepare(capture, fit)
        port.ignoreWrite = true
        assertTrue(runCatching { session.writeNext() }.isFailure)
        assertEquals(10, store.value!!.pendingIndex)
        assertTrue(runCatching { session.writeNext() }.isFailure)
        assertEquals(listOf(10), port.commands)
        val restore = BoardCalibrationSession(port, store) {}
        restore.prepare(capture, fit, restore = true)
        assertTrue(restore.done)
        assertTrue(store.value!!.complete)
    }
    @Test fun diskFailurePreventsHardwareWriteAndPacketsCannotWriteEndpoints() = runBlocking {
        val port = Port(); val store = MemoryStore(); val session = BoardCalibrationSession(port, store) {}
        session.prepare(capture, fit); store.fail = true
        assertTrue(runCatching { session.writeNext() }.isFailure)
        assertTrue(port.commands.isEmpty())
        assertEquals("FC,0A,06,F6", BoardCalibrationProtocol.capturePoint(10))
        assertEquals("F7,00,F7,F6", BoardCalibrationProtocol.readPoint(0))
        assertTrue(runCatching { BoardCalibrationProtocol.capturePoint(0) }.isFailure)
        assertTrue(runCatching { BoardCalibrationProtocol.capturePoint(30) }.isFailure)
        assertEquals(556, BoardCalibrationProtocol.decode("F1 4A 04 36 35 35 30 0F F6",0x4A,4))
    }
}
