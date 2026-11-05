package com.spop.poverlay.calibration

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.spop.poverlay.UiTestDiagnostics
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class CalibrationActivationUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(compose).around(UiTestDiagnostics(compose))
    private val baseline = List(31) { 1000 + it * 100 }
    private fun isolated(block: suspend (GrupettoCalibrationManager, Context) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "activation-${System.nanoTime()}"
        val directory = File(context.cacheDir, id).apply { mkdirs() }
        val fixture = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$id-$name", mode)
        }
        val manager = withContext(Dispatchers.Main) { GrupettoCalibrationManager(fixture) }
        try { block(manager, fixture) }
        finally {
            withContext(Dispatchers.Main) { manager.record(false, null); manager.deactivate("Fixture finished") }
            directory.deleteRecursively()
            fixture.getSharedPreferences("grupetto-power-calibration", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    private fun show(manager: GrupettoCalibrationManager) {
        compose.setContent { MaterialTheme {
            GrupettoCalibrationPanel(manager.state.collectAsState().value, {}, manager::activate, {}, {})
        } }
    }

    @Test fun firstRecordedPointEnablesExplicitActivationAndSurvivesRestart() = isolated { manager, context ->
        withContext(Dispatchers.Main) { manager.record(true, null); manager.verifyBaseline(baseline) }
        show(manager)
        compose.onNodeWithContentDescription("Activate calibration").assertIsNotEnabled().assertIsOff()
        val point = CalibrationSample(2000.0, 80.0, 200.0, 220.0, System.currentTimeMillis() + 1000)
        val capture = CalibrationCapture("activation-bike", "meter", 2040490L, baseline, listOf(point))
        withContext(Dispatchers.Main) { manager.accept(capture) }
        assertFalse(manager.state.value.active)
        compose.onNodeWithContentDescription("Activate calibration").assertIsEnabled().performClick()
        compose.waitUntil(5000) { manager.state.value.active }
        compose.onNodeWithContentDescription("Activate calibration").assertIsOn()
        withContext(Dispatchers.Main) { manager.record(false, null) }
        assertEquals(220f, manager.correctedPower(CalibrationFrame(SystemClock.elapsedRealtime(),
            4000, 120.0, 200.0, capture.frameSerial, 1))!!, .001f)
        val saved = CalibrationBackup.decode(manager.exportBackup())
        assertEquals(listOf(point), saved.saved!!.capture.samples)
        val restarted = withContext(Dispatchers.Main) { GrupettoCalibrationManager(context) }
        restarted.exportBackup() // Wait for initialization.
        assertTrue(restarted.state.value.active)
        assertTrue(restarted.state.value.canActivate)
        assertEquals(1, restarted.state.value.activeCount)
        withContext(Dispatchers.Main) { restarted.deactivate("Off remains explicit") }
        val off = withContext(Dispatchers.Main) { GrupettoCalibrationManager(context) }
        off.exportBackup()
        assertFalse(off.state.value.active)
        assertTrue(off.state.value.canActivate)
    }

    @Test fun zeroPointsStayDisabledAndRuntimeRejectsStaleReadyTable() = isolated { manager, _ ->
        val point = CalibrationSample(2000.0, 80.0, 200.0, 220.0, 1)
        val table = GrupettoCalibrationFitter.fit(baseline, listOf(point))!!.copy(sampleCount = 0, ready = true)
        val empty = GrupettoCalibrationProfile(CalibrationCapture("activation-bike", "meter", 2040490L,
            baseline, emptyList()), table)
        withContext(Dispatchers.Main) { manager.importBackup(CalibrationBackup(empty, null)) }
        show(manager)
        compose.onNodeWithContentDescription("Activate calibration").assertIsNotEnabled().assertIsOff()
        withContext(Dispatchers.Main) { manager.activate(true) }
        compose.waitUntil(5000) { manager.state.value.message == "Record at least one valid effort to activate" }
        assertFalse(manager.state.value.active)
        assertFalse(manager.state.value.canActivate)
        assertNull(CalibrationBackup.decode(manager.exportBackup()).saved)
    }

    @Test fun anExistingSparseDraftCanActivateWithoutASecondRecording() = isolated { manager, _ ->
        val point = CalibrationSample(2000.0, 80.0, 200.0, 220.0, 1)
        val old = GrupettoCalibrationProfile(CalibrationCapture("activation-bike", "meter", 2040490L,
            baseline, listOf(point)), GrupettoCalibrationFitter.fit(baseline, listOf(point))!!.copy(ready = false))
        val decoded = CalibrationBackup.decode(CalibrationBackup(old, null).encode())
        withContext(Dispatchers.Main) { manager.importBackup(decoded) }
        show(manager)
        compose.onNodeWithContentDescription("Activate calibration").assertIsEnabled().performClick()
        compose.waitUntil(5000) { manager.state.value.active }
        assertEquals(listOf(point), CalibrationBackup.decode(manager.exportBackup()).saved!!.capture.samples)
        assertEquals(old.table!!.gains, manager.state.value.displayTable!!.gains)
    }
}
