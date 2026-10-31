package com.spop.poverlay.calibration

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GrupettoCalibrationUiState(val recording: Boolean = false, val active: Boolean = false,
    val verified: Boolean = false, val count: Int = 0, val table: GrupettoCalibrationTable? = null,
    val activeCount: Int = 0, val savedCount: Int = 0, val errors: CalibrationErrorComparison? = null,
    val displayTable: GrupettoCalibrationTable? = null,
    val message: String = "Correct bike power inside Grupetto") {
    val canActivate get() = table?.ready == true || savedCount > 0
}

/** First activation is explicit. While recording, every usable fit replaces the active snapshot. */
class GrupettoCalibrationManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val preferences = context.getSharedPreferences("grupetto-power-calibration", Context.MODE_PRIVATE)
    private val store = CalibrationBackupStore(context.filesDir)
    private val mutableState = MutableStateFlow(GrupettoCalibrationUiState())
    val state = mutableState.asStateFlow()
    private val mutablePosition = MutableStateFlow<CalibrationTablePosition?>(null)
    val livePosition = mutablePosition.asStateFlow()
    private var draft: GrupettoCalibrationProfile? = null
    private var savedProfile: GrupettoCalibrationProfile? = null
    @Volatile private var activeProfile: GrupettoCalibrationProfile? = null
    @Volatile private var verifiedBaseline: Pair<List<Int>, Long>? = null
    private var recordingSince = Long.MAX_VALUE
    private val activationLock = Any()
    private var recordingRequested = false
    private var activationGeneration = 0
    val isActive get() = activeProfile != null
    private val initialized = scope.async {
        try {
            val loaded = withContext(Dispatchers.IO) { store.load() }
            savedProfile = loaded.saved?.takeIf { it.table?.ready == true }
            activeProfile = savedProfile?.takeIf { preferences.getBoolean("active", false) }
            // Re-evaluate old drafts, including fits previously rejected by error comparisons.
            draft = loaded.draft?.let { profile -> profile.copy(table = withContext(Dispatchers.Default) {
                GrupettoCalibrationFitter.fit(profile.capture.baseline, profile.capture.samples,
                    activeProfile?.takeIf { it.matches(profile.capture) }?.table)?.let { CalibrationManualEdit.reapply(it, profile.table) }
            }) }
            mutableState.update { it.copy(count = draft?.capture?.samples?.size ?: 0, table = draft?.table,
                active = activeProfile != null, activeCount = activeProfile?.capture?.samples?.size ?: 0,
                savedCount = compatibleSavedCount(), errors = calibrationErrorComparison(draft, activeProfile, savedProfile),
                displayTable = displayedTable(),
                message = if (activeProfile != null) "Active · waiting for bike verification" else "Record a ride, then activate the table") }
        } catch (_: Exception) { mutableState.update { it.copy(message = "Saved Grupetto table is unreadable. Clear it to start again.") } }
    }
    suspend fun record(enabled: Boolean, existing: CalibrationCapture?) {
        synchronized(activationLock) { recordingRequested = enabled }
        initialized.await()
        mutex.withLock {
            recordingSince = if (enabled) System.currentTimeMillis() else Long.MAX_VALUE
            mutableState.update { it.copy(recording = enabled, message = if (enabled)
                if (activeProfile != null) "Recording · every saved effort updates the table" else "Recording · guide or free ride"
                else "Recording paused") }
            if (enabled) {
                val capture = draft?.capture ?: savedProfile?.capture ?: existing?.takeIf { preferences.getLong("clearedAt", 0) == 0L }
                if (capture != null) fitAndSave(mergeCalibrationCaptures(capture, existing, preferences.getLong("clearedAt", 0)))
            }
        }
    }
    suspend fun accept(capture: CalibrationCapture) {
        initialized.await()
        mutex.withLock {
            if (!mutableState.value.recording) return
            val fresh = capture.samples.filter { it.capturedAt >= recordingSince }
            if (fresh.isEmpty()) return
            val previous = draft?.capture
            if (previous != null && (previous.frameSerial != capture.frameSerial ||
                previous.baseline != capture.baseline || previous.serviceVersion != capture.serviceVersion)) {
                val message = "Bike or board baseline changed. Saved efforts kept; restore the original setup or clear the Grupetto draft."
                mutableState.update { it.copy(recording = false, message = message) }
                error(message)
            }
            val samples = ((previous?.samples ?: emptyList()) + fresh).distinctBy { it.capturedAt }.takeLast(3600)
            fitAndSave(capture.copy(samples = samples))
            check(mutableState.value.recording) { mutableState.value.message }
        }
    }
    private suspend fun fitAndSave(capture: CalibrationCapture) {
        try {
            val (generation, incumbent) = synchronized(activationLock) {
                activationGeneration to activeProfile?.takeIf { it.matches(capture) }
            }
            val table = withContext(Dispatchers.Default) {
                GrupettoCalibrationFitter.fit(capture.baseline, capture.samples, incumbent?.table)?.let {
                    CalibrationManualEdit.reapply(it, draft?.table ?: savedProfile?.table)
                }
            }
            val updated = GrupettoCalibrationProfile(capture, table)
            withContext(NonCancellable + Dispatchers.IO) { store.save(CalibrationBackup(updated, savedProfile)) }
            synchronized(activationLock) {
                draft = updated
                mutableState.update { it.copy(count = capture.samples.size, table = table, savedCount = compatibleSavedCount(),
                errors = calibrationErrorComparison(draft, activeProfile, savedProfile), displayTable = displayedTable(),
                message = if (incumbent != null && table?.ready == true) "Draft saved · applying latest fit"
                    else if (activeProfile != null) "Recording · more coverage needed for the latest fit"
                    else if (table?.ready == true) "Draft ready · activate when ready" else "Collect more nearby resistances and two cadences") }
            }
            if (automaticCalibrationProfile(updated, incumbent) != null) {
                try {
                    publishActive(updated, incumbent, generation, automatic = true)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { mutableState.update { it.copy(message = "Draft saved · active update failed; previous table kept") } }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableState.update { it.copy(recording = false, message = "Could not save the Grupetto draft") } }
    }
    fun activate(enabled: Boolean) {
        if (!enabled) { deactivate("Grupetto correction off"); return }
        val generation = synchronized(activationLock) { ++activationGeneration }
        scope.launch {
            initialized.await()
            mutex.withLock {
                val profile = activationProfile(draft, savedProfile)
                if (profile == null) {
                    mutableState.update { it.copy(message = "More resistances and cadences needed · keep recording") }
                    return@withLock
                }
                if (verifiedBaseline?.first?.let { it != profile.capture.baseline } == true) {
                    mutableState.update { it.copy(message = "Bike table changed · saved correction cannot be used") }
                    return@withLock
                }
                try {
                    publishActive(profile, activeProfile, generation, automatic = false)
                } catch (_: Exception) { mutableState.update { it.copy(message = "Could not activate the Grupetto table") } }
            }
        }
    }
    /** Caller holds mutex, so disk writes cannot interleave with another activation or Clear.
     * The short publication lock also covers Off/board-change calls from sensor callback threads.
     */
    private suspend fun publishActive(profile: GrupettoCalibrationProfile, previous: GrupettoCalibrationProfile?,
        generation: Int, automatic: Boolean) = withContext(NonCancellable) {
        val previousSaved = savedProfile
        fun allowed() = generation == activationGeneration && (!automatic ||
            (recordingRequested && mutableState.value.recording && activeProfile === previous))
        if (!synchronized(activationLock) { allowed() }) return@withContext
        withContext(Dispatchers.IO) { store.save(CalibrationBackup(draft, profile)) }
        var installed = false
        try {
            synchronized(activationLock) {
                if (allowed()) {
                    if (!automatic) check(preferences.edit().putBoolean("active", true).commit())
                    activeProfile = profile
                    savedProfile = profile
                    installed = true
                    mutableState.update { it.copy(active = true, verified = automatic && it.verified,
                        errors = calibrationErrorComparison(draft, activeProfile, savedProfile), displayTable = displayedTable(),
                        activeCount = profile.capture.samples.size, savedCount = profile.capture.samples.size, message = if (automatic)
                            "Auto-updated · ${profile.capture.samples.size} saved efforts"
                        else if (profile !== draft) "Saved table active · latest draft kept"
                        else "Active · waiting for bike verification") }
                }
            }
        } finally {
            // Off or pause may arrive during disk I/O. Keep the previously applied snapshot on disk too.
            if (!installed) withContext(Dispatchers.IO) {
                store.save(CalibrationBackup(draft, previousSaved))
            }
        }
    }
    fun deactivate(message: String) {
        synchronized(activationLock) {
            ++activationGeneration
            activeProfile = null
            preferences.edit().putBoolean("active", false).apply()
            mutableState.update { it.copy(active = false, verified = false, activeCount = 0, message = message,
                errors = calibrationErrorComparison(draft, activeProfile, savedProfile), displayTable = displayedTable()) }
        }
    }
    fun clear() {
        synchronized(activationLock) { recordingRequested = false }
        deactivate("Grupetto table cleared")
        scope.launch {
            initialized.await()
            mutex.withLock {
                synchronized(activationLock) { ++activationGeneration; activeProfile = null }
                try {
                    withContext(Dispatchers.IO) {
                        check(preferences.edit().putBoolean("active", false).putLong("clearedAt", System.currentTimeMillis()).commit())
                        store.save(CalibrationBackup(null, null))
                    }
                    draft = null; savedProfile = null; activeProfile = null; recordingSince = Long.MAX_VALUE
                    mutableState.value = GrupettoCalibrationUiState(message = "Grupetto table cleared · bike unchanged")
                } catch (_: Exception) { mutableState.update { it.copy(recording = false, active = false, message = "Could not clear saved files; correction is off") } }
            }
        }
    }
    suspend fun exportBackup(): String {
        initialized.await()
        return mutex.withLock {
            val backup = CalibrationBackup(draft, savedProfile)
            check(backup.hasData) { "Record calibration data before saving or sharing." }
            withContext(Dispatchers.Default) { backup.encode() }
        }
    }
    /** Edit a frozen preview; reject stale sessions instead of overwriting a newer ride/load/clear. */
    suspend fun saveManualEdit(expected: GrupettoCalibrationTable, edited: GrupettoCalibrationTable) {
        initialized.await()
        mutex.withLock {
            check(!recordingRequested && !mutableState.value.recording) { "Pause recording before saving manual edits." }
            check(displayedTable() == expected) { "The table changed. Close the editor and reopen the latest table." }
            require(edited.baseline == expected.baseline && edited.gains.size == 496 &&
                edited.gains.all { it.isFinite() && it in kotlin.math.ln(.65)..kotlin.math.ln(1.35) })
            val source = (activeProfile ?: activationProfile(draft, savedProfile) ?: draft)
                ?: error("Record a calibration effort before editing.")
            check(verifiedBaseline?.first?.let { it == edited.baseline } != false) { "The bike baseline changed. Reopen calibration." }
            // Preserve any newer real efforts even when the user edited the active snapshot.
            val capture = draft?.takeIf { it.matches(source.capture) }?.capture ?: source.capture
            val fit = withContext(Dispatchers.Default) { GrupettoCalibrationFitter.fit(capture.baseline, capture.samples) }
                ?: error("No usable recorded calibration is available.")
            val table = if (edited.manualOffsets.isEmpty()) fit else fit.copy(
                gains = edited.gains, ready = true, manualCoverage = edited.manualCoverage,
                manualOffsets = edited.gains.indices.map { edited.gains[it] - fit.gains[it] }
            ).withCalibrationCoverage(capture.samples)
            val updated = GrupettoCalibrationProfile(capture, table)
            val generation = synchronized(activationLock) { activationGeneration }
            val incumbent = activeProfile
            // Restoring a sparse recorded fit must not fall back to the manual snapshot just removed.
            val retainedSaved = savedProfile.takeUnless { edited.manualOffsets.isEmpty() && !fit.ready }
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { store.save(CalibrationBackup(updated, retainedSaved)) }
                draft = updated
                savedProfile = retainedSaved
                mutableState.update { it.copy(table = table, count = capture.samples.size,
                    savedCount = compatibleSavedCount(), errors = calibrationErrorComparison(draft, activeProfile, savedProfile),
                    displayTable = displayedTable(), message = "Manual changes saved · activate when ready") }
                if (incumbent != null && table.ready) {
                    try { publishActive(updated, incumbent, generation, automatic = false) }
                    catch (e: Exception) { throw IllegalStateException("Edits saved as a draft; active update failed. Close and activate the draft to retry.", e) }
                } else if (incumbent != null) deactivate("Recorded fit needs more coverage · correction off")
            }
        }
    }
    internal suspend fun importBackup(backup: CalibrationBackup): CalibrationLoadResult {
        require(backup.hasData) { "This file contains no calibration data." }
        initialized.await()
        return mutex.withLock {
            check(!recordingRequested && !mutableState.value.recording) { "Pause recording before loading a table." }
            val capture = (backup.draft ?: backup.saved)!!.capture
            val actual = verifiedBaseline?.first
            if (actual != null && actual != capture.baseline) throw CalibrationBaselineMismatch()
            // Finish disk and memory publication together, even if the file dialog is dismissed.
            withContext(NonCancellable) {
                deactivate("Loading calibration table")
                withContext(Dispatchers.IO) {
                    check(preferences.edit().putBoolean("active", false).commit()) { "Could not disable correction." }
                    store.save(backup)
                }
                synchronized(activationLock) {
                    ++activationGeneration
                    draft = backup.draft
                    savedProfile = backup.saved
                    activeProfile = null
                    recordingSince = Long.MAX_VALUE
                    mutableState.value = GrupettoCalibrationUiState(count = draft?.capture?.samples?.size ?: 0,
                        table = draft?.table, savedCount = compatibleSavedCount(),
                        errors = calibrationErrorComparison(draft, null, savedProfile), displayTable = displayedTable(),
                        message = "Table loaded · correction off · activate when ready")
                }
                CalibrationLoadResult.Loaded
            }
        }
    }
    private fun compatibleSavedCount() = savedProfile?.takeIf { draft == null || it.matches(draft!!.capture) }
        ?.capture?.samples?.size ?: 0
    private fun displayedTable() = (activeProfile ?: activationProfile(draft, savedProfile) ?: draft)?.table
    fun verifyBaseline(baseline: List<Int>) {
        verifiedBaseline = baseline to SystemClock.elapsedRealtime()
        activeProfile?.let { profile ->
            if (profile.capture.baseline != baseline) deactivate("Board table changed · Grupetto correction disabled")
        }
    }
    fun clearVerification() { verifiedBaseline = null; mutablePosition.value = null; mutableState.update { it.copy(verified = false) } }
    /** Null means this frame was not calibrated; never label a fallback as corrected power. */
    fun correctedPower(frame: CalibrationFrame): Float? {
        val baseline = verifiedBaseline
        if (baseline != null && SystemClock.elapsedRealtime() - baseline.second in 0..6500 &&
            (mutablePosition.value?.frame?.timestamp?.let { frame.timestamp - it >= 250 } != false)) {
            mutablePosition.value = CalibrationTablePosition(frame, baseline.first)
        }
        val profile = activeProfile ?: return null
        val verified = verifiedBaseline ?: return null
        if (frame.powerSource != 1 || profile.capture.frameSerial != frame.frameSerial) {
            deactivate("Bike identity or power source changed · correction disabled"); return null
        }
        if (verified.first != profile.capture.baseline || SystemClock.elapsedRealtime() - verified.second !in 0..6500) {
            clearVerification(); return null
        }
        mutableState.update { if (it.verified) it else it.copy(verified = true) }
        return profile.table!!.corrected(frame.nativeWatts, frame.ad.toDouble(), frame.rpm).toFloat()
    }
}
