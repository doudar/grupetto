package com.spop.poverlay.calibration

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BoardWriteUiState(val active: Boolean = false, val busy: Boolean = false,
    val message: String = "Experimental board setup · after the ride", val index: Int? = null,
    val target: Int? = null, val ad: Int? = null, val rpm: Int? = null, val canWrite: Boolean = false,
    val backupAvailable: Boolean = false)

class BoardWriteController(private val context: Context, private val capture: BikeCalibrationManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = BoardWriteStore(context.filesDir)
    private val mutableState = MutableStateFlow(BoardWriteUiState(backupAvailable = runCatching { store.load() != null }.getOrDefault(false)))
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var port: SensorDataBoardPort? = null
    private var session: BoardCalibrationSession? = null
    private var polling: Job? = null
    private var operation: Job? = null
    fun begin(restore: Boolean = false) {
        if (!capture.supported || mutableState.value.busy || mutableState.value.active) return
        mutableState.value = mutableState.value.copy(busy = true, canWrite = false, message = "Reading board · no write yet")
        operation = scope.launch {
            try {
                capture.stop()
                val saved = if (restore) {
                    val backup = withContext(Dispatchers.IO) { checkNotNull(store.load()) { "No board backup is available." } }
                    CalibrationCapture(backup.frameSerial, "restore", 2040490, backup.original, emptyList())
                } else checkNotNull(capture.savedCapture()) { "Collect ride data first." }
                val fit = withContext(Dispatchers.Default) { CalibrationFitter.fit(saved.baseline, saved.samples) }
                check(restore || fit.ready) { "More ride data needed before writing." }
                withTimeout(90_000) {
                    port = SensorDataBoardPort.connect(context)
                    session = BoardCalibrationSession(port!!, object : BoardJournalStore {
                        override fun load() = store.load()
                        override fun save(journal: BoardWriteJournal) = store.save(journal)
                    })
                    // File writes are small, but IPC/disk verification stays off the UI thread.
                    withContext(Dispatchers.IO) { session!!.prepare(saved, fit, restore) }
                }
                mutableState.value = mutableState.value.copy(active = !session!!.done, busy = false,
                    backupAvailable = true, message = if (session!!.done) "Board already matches backup" else "Stop pedals · turn knob to target")
                if (session!!.done) releasePort() else startPolling()
            } catch (e: Exception) { fail(e) }
        }
    }
    private fun startPolling() {
        polling = scope.launch {
            try {
                while (isActive && mutableState.value.active) {
                    mutex.withLock {
                        val position = port!!.position()
                        val target = session!!.targetAd
                        mutableState.value = mutableState.value.copy(ad = position.ad, rpm = position.rpm,
                            index = session!!.nextIndex, target = target,
                            canWrite = !mutableState.value.busy && position.rpm == 0 && position.ad == target)
                    }
                    delay(500)
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; fail(e) }
        }
    }
    fun writePoint() {
        if (!mutableState.value.canWrite || mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, canWrite = false, message = "Checking position · writing · verifying")
        operation = scope.launch {
            try {
                val done = mutex.withLock { withContext(Dispatchers.IO) { session!!.writeNext() } }
                mutableState.value = mutableState.value.copy(busy = false, active = !done, canWrite = false,
                    message = if (done) "Table written and read back. Validate power on your next ride." else "Point verified · turn to next target")
                if (done) { polling?.cancelAndJoin(); releasePort() }
            } catch (e: Exception) { fail(e) }
        }
    }
    fun close() {
        if (!mutableState.value.active && !mutableState.value.busy) return
        scope.launch {
            polling?.cancelAndJoin()
            operation?.cancelAndJoin() // A dispatched write finishes its bounded readback first.
            releasePort()
            mutableState.value = mutableState.value.copy(active = false, busy = false, canWrite = false,
                message = "Board setup closed · backup retained")
        }
    }
    private fun releasePort() { port?.close(); port = null; session = null }
    private fun fail(error: Exception) {
        polling?.cancel()
        releasePort()
        mutableState.value = mutableState.value.copy(active = false, busy = false, canWrite = false,
            backupAvailable = runCatching { store.load() != null }.getOrDefault(false),
            message = error.message ?: "Board setup stopped; backup retained")
    }
}
