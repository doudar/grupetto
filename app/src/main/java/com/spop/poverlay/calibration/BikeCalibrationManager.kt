package com.spop.poverlay.calibration

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.spop.poverlay.sensor.BoundBikeService
import com.spop.poverlay.sensor.power.ExternalPowerReading
import com.spop.poverlay.sensor.v1new.getV1NewBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CalibrationUiState(val enabled: Boolean = false, val sampleCount: Int = 0,
    val fit: CalibrationFit? = null, val status: String = "Enable to collect steady riding data.",
    val baseline: List<Int> = emptyList(), val progress: CaptureProgress = CaptureProgress(),
    val frame: CalibrationFrame? = null, val pedalWatts: Int? = null,
    val mode: CalibrationMode = CalibrationMode.Guided, val resting: Boolean = false,
    val targets: List<CalibrationTarget> = emptyList(), val target: CalibrationTarget? = null,
    val recent: List<CalibrationSample> = emptyList(), val savedAt: Long = 0,
    // Keep the actual failure separate from routine pause/progress text until capture recovers.
    val error: String? = null)

internal fun CalibrationUiState.captureFailed(message: String) =
    copy(enabled = false, status = message, error = message)

internal fun CalibrationUiState.captureIdentityVerified() = copy(error = null)

class BikeCalibrationManager(
    private val context: Context,
    private val readings: StateFlow<ExternalPowerReading?>,
    val supported: Boolean,
    private val grupettoCalibration: GrupettoCalibrationManager? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = CalibrationStore(context.filesDir)
    private val mutableState = MutableStateFlow(CalibrationUiState())
    val state = mutableState.asStateFlow()
    private var capture: CalibrationCapture? = null
    private var collecting: Job? = null
    private var generation = 0
    private var storageHealthy = true
    private var guide: CalibrationGuide? = null
    private var resetWindow = false
    fun setMode(mode: CalibrationMode) {
        mutableState.value = mutableState.value.copy(mode = mode, resting = false)
        resetWindow = true
    }
    fun rest(resting: Boolean) { mutableState.value = mutableState.value.copy(resting = resting); resetWindow = true }
    fun easier() { guide?.easier(); rest(false) }
    fun harder() { guide?.harder(); rest(false) }
    fun skip() { guide?.skip(mutableState.value.target); rest(false) }
    fun selectTarget(target: CalibrationTarget) { guide?.selected = target.point to target.rpm; rest(false) }
    suspend fun savedCapture(): CalibrationCapture? { initialized.await(); return capture }
    private val initialized = scope.async {
            withContext(Dispatchers.IO) { runCatching { store.load() } }.fold(onSuccess = {
                capture = it
                if (it != null) mutableState.value = CalibrationUiState(sampleCount = it.samples.size,
                    fit = withContext(Dispatchers.Default) { CalibrationFitter.fit(it.baseline, it.samples) },
                    status = "Capture paused", baseline = it.baseline, recent = it.samples.takeLast(30))
            }, onFailure = {
                storageHealthy = false
                mutableState.value = CalibrationUiState().captureFailed("Saved capture is unreadable. Clear it to start again.")
            })
    }

    fun setEnabled(enabled: Boolean) {
        if (!supported) return
        val previous = collecting
        val token = ++generation
        previous?.cancel()
        collecting = scope.launch {
            previous?.join()
            initialized.await()
            if (!enabled) {
                grupettoCalibration?.record(false, null)
                mutableState.value = mutableState.value.copy(enabled = false, status = "Capture paused", frame = null, pedalWatts = null)
                context.stopService(android.content.Intent(context, CalibrationCaptureService::class.java))
                return@launch
            }
            if (com.spop.poverlay.sensor.power.freshExternalPower(readings.value, SystemClock.elapsedRealtime()) == null) {
                grupettoCalibration?.record(false, null)
                mutableState.value = mutableState.value.copy(enabled = false, status = "Connect a power meter to record")
                context.stopService(android.content.Intent(context, CalibrationCaptureService::class.java))
                return@launch
            }
            mutableState.value = mutableState.value.copy(enabled = true, status = "Reading the bike's calibration table…")
            var binding: BoundBikeService? = null
            var service: CalibrationService? = null
            try {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo("com.onepeloton.affernetservice", 0)
                @Suppress("DEPRECATION")
                val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                check(version == 2040490L) { "Calibration capture requires the verified Affernet 2.4.490 interface." }
                check(storageHealthy) { "Saved capture is unreadable. Clear it before collecting." }
                binding = withTimeout(10000) { getV1NewBinder(context) }
                val connection = CalibrationService(binding.binder)
                service = connection
                withContext(Dispatchers.IO) { connection.start() }
                val baseline = withContext(Dispatchers.IO) { connection.readTable() }
                val window = CalibrationWindow(baseline)
                guide = null
                var nextTableRead = 0L
                while (isActive) {
                    val now = SystemClock.elapsedRealtime()
                    connection.failure?.let { error(it) }
                    if (now >= nextTableRead) {
                        check(withContext(Dispatchers.IO) { connection.readTable() } == baseline) {
                            "Bike calibration changed. Clear this capture before collecting again."
                        }
                        nextTableRead = now + 30000
                    }
                    val frame = connection.frame
                    val pedal = readings.value
                    if (resetWindow) { window.reset(); resetWindow = false }
                    if (frame == null || now - frame.timestamp !in 0..1000 ||
                        pedal == null || now - pedal.timestamp !in 0..1500) {
                        window.reset()
                        mutableState.value = mutableState.value.copy(status = "Waiting for bike + pedals",
                            frame = null, pedalWatts = null, progress = window.progress)
                    } else {
                        check(frame.powerSource == 1) { "Bike is not using sensor-board power. No tablet override will be changed." }
                        val current = capture ?: CalibrationCapture(frame.frameSerial, pedal.address, version, baseline, emptyList())
                        check(current.frameSerial == frame.frameSerial && current.meterAddress == pedal.address &&
                            current.serviceVersion == version && current.baseline == baseline) {
                            "Bike, power meter or calibration changed. Clear the saved capture to start a new one."
                        }
                        mutableState.value = mutableState.value.captureIdentityVerified()
                        capture = current
                        if (guide == null) guide = CalibrationGuide(baseline, frame.ad)
                        val cells = guide!!.cells(current.samples)
                        val oldTarget = guide!!.target(cells)
                        mutableState.value = mutableState.value.copy(frame = frame, pedalWatts = pedal.watts,
                            baseline = baseline, targets = cells, target = oldTarget)
                        check(current.samples.size < 3600) { "Capture is full. Review or clear the saved data." }
                        if (mutableState.value.resting) {
                            window.reset()
                            mutableState.value = mutableState.value.copy(status = "Recover · resume when ready", progress = window.progress)
                            delay(1000)
                            continue
                        }
                        val sample = window.add(frame, pedal.watts, pedal.timestamp, pedal.address, now, System.currentTimeMillis())
                        if (sample != null) {
                            val updated = current.copy(samples = current.samples + sample)
                            val fit = withContext(Dispatchers.Default) { CalibrationFitter.fit(baseline, updated.samples) }
                            // A pause must not leave memory behind a completed atomic save.
                            withContext(NonCancellable) {
                                withContext(Dispatchers.IO) { store.save(updated, fit) }
                                capture = updated
                                grupettoCalibration?.accept(updated)
                                val newCells = guide!!.cells(updated.samples)
                                val finishedTarget = oldTarget != null && !oldTarget.complete &&
                                    newCells.any { it.point == oldTarget.point && it.rpm == oldTarget.rpm && it.complete }
                                mutableState.value = mutableState.value.copy(sampleCount = updated.samples.size, fit = fit,
                                    status = "Effort saved", baseline = baseline, recent = updated.samples.takeLast(30),
                                    progress = window.progress, savedAt = SystemClock.elapsedRealtime(), targets = newCells,
                                    target = guide!!.target(newCells), resting = mutableState.value.mode == CalibrationMode.Guided && finishedTarget)
                            }
                        } else mutableState.value = mutableState.value.copy(status = window.progress.phase.cue,
                            progress = window.progress)
                    }
                    delay(1000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == token) mutableState.value = mutableState.value.captureFailed(
                    e.message ?: "Calibration capture stopped.")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { service?.close() }; binding?.close() }
                if (generation == token) {
                    mutableState.value = mutableState.value.copy(enabled = false)
                    withContext(NonCancellable) { grupettoCalibration?.record(false, null) }
                }
                if (generation == token) context.stopService(android.content.Intent(context, CalibrationCaptureService::class.java))
            }
        }
    }

    fun clear() {
        val previous = collecting
        ++generation
        previous?.cancel()
        collecting = scope.launch {
            previous?.join(); initialized.await()
            try {
                withContext(Dispatchers.IO) { store.clear() }
                capture = null
                guide = null
                grupettoCalibration?.record(false, null)
                storageHealthy = true
                mutableState.value = CalibrationUiState(status = "Saved capture cleared. Bike calibration is unchanged.")
            } catch (e: Exception) {
                mutableState.value = mutableState.value.captureFailed("Could not clear saved capture.")
            } finally {
                context.stopService(android.content.Intent(context, CalibrationCaptureService::class.java))
            }
        }
    }
    suspend fun stop() {
        ++generation
        collecting?.cancelAndJoin()
        grupettoCalibration?.record(false, null)
        mutableState.value = mutableState.value.copy(enabled = false, status = "Collection paused. Saved data retained.")
        context.stopService(android.content.Intent(context, CalibrationCaptureService::class.java))
    }
}
