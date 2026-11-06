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
    val canActivate get() = table?.canActivate == true || savedCount > 0
}

/** Frozen preview token. Only the manager holding its matching snapshot can apply or undo it. */
data class CalibrationRefitPreview(val id: Long, val result: CalibrationRefitResult)

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
    private val refitRevision = java.util.concurrent.atomic.AtomicLong()
    private var nextRefitId = 0L
    private data class RefitSession(val preview: CalibrationRefitPreview, val revision: Long,
        val generation: Int, val baseline: List<Int>?,
        val draft: GrupettoCalibrationProfile?, val saved: GrupettoCalibrationProfile?,
        val active: GrupettoCalibrationProfile?, val capture: CalibrationCapture)
    private var pendingRefit: RefitSession? = null
    private var undoRefitSession: RefitSession? = null
    private var refitAppliedDraft: GrupettoCalibrationProfile? = null
    private var refitAppliedSaved: GrupettoCalibrationProfile? = null
    private var refitAppliedActive: GrupettoCalibrationProfile? = null

    private val initialized = scope.async {
        try {
            val loaded = withContext(Dispatchers.IO) { store.load() }
            if (store.resetOnLoad) withContext(Dispatchers.IO) {
                // Only correction state is reset. The tombstone and cutoff prevent old capture
                // files from silently restoring the discarded table on the next Record action.
                check(preferences.edit().putBoolean("active", false)
                    .putLong("clearedAt", maxOf(preferences.getLong("clearedAt", 0), loaded.resetAt)).commit())
            }
            savedProfile = loaded.saved?.takeIf { it.canActivate }
            activeProfile = savedProfile?.takeIf { preferences.getBoolean("active", false) }
            // The migrated/saved surface is authoritative. Replaying old observations here would
            // undo manual edits and make restart change the correction.
            draft = loaded.draft?.let { profile ->
                if (profile.table != null) profile else withContext(Dispatchers.Default) {
                    updateUnifiedCalibration(profile.capture, null)
                }
            }
            mutableState.update { it.copy(count = draft?.capture?.samples?.size ?: 0, table = draft?.table,
                active = activeProfile != null, activeCount = activeProfile?.capture?.samples?.size ?: 0,
                savedCount = compatibleSavedCount(), errors = calibrationErrorComparison(draft, activeProfile, savedProfile),
                displayTable = displayedTable(),
                message = if (store.resetOnLoad) "Saved calibration could not be upgraded and was reset. A recovery copy was kept; record a new table."
                    else if (activeProfile != null) "Active · waiting for bike verification" else "Record a valid effort, then activate the table") }
        } catch (_: Exception) { mutableState.update { it.copy(message = "Saved Grupetto table is unreadable. Clear it to start again.") } }
    }
    suspend fun record(enabled: Boolean, existing: CalibrationCapture?) {
        synchronized(activationLock) { recordingRequested = enabled; refitRevision.incrementAndGet() }
        initialized.await()
        mutex.withLock {
            recordingSince = if (enabled) System.currentTimeMillis() else Long.MAX_VALUE
            mutableState.update { it.copy(recording = enabled, message = if (enabled)
                if (activeProfile != null) "Recording · paired observations update this cell" else "Recording · guide or free ride"
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
            val samples = retainCalibrationObservations(capture.baseline, (previous?.samples ?: emptyList()) + fresh)
            fitAndSave(capture.copy(samples = samples))
            check(mutableState.value.recording) { mutableState.value.message }
        }
    }
    private suspend fun fitAndSave(capture: CalibrationCapture) {
        try {
            val (generation, incumbent) = synchronized(activationLock) {
                activationGeneration to activeProfile?.takeIf { it.matches(capture) }
            }
            val previous = draft?.takeIf { it.matches(capture) } ?: savedProfile?.takeIf { it.matches(capture) }
            val updated = withContext(Dispatchers.Default) {
                updateUnifiedCalibration(capture, previous, incumbent?.table)
            }
            val table = updated.table
            withContext(NonCancellable + Dispatchers.IO) { store.save(CalibrationBackup(updated, savedProfile)) }
            synchronized(activationLock) {
                draft = updated
                mutableState.update { it.copy(count = capture.samples.size, table = table, savedCount = compatibleSavedCount(),
                errors = calibrationErrorComparison(draft, activeProfile, savedProfile), displayTable = displayedTable(),
                message = if (incumbent != null && updated.canActivate) "Draft saved · applying latest fit"
                    else if (activeProfile != null) "Recording · no usable point in the latest fit"
                    else if (updated.canActivate) "Draft ready · activate when ready" else "Record at least one valid effort to activate") }
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
        val generation = synchronized(activationLock) { refitRevision.incrementAndGet(); ++activationGeneration }
        scope.launch {
            initialized.await()
            mutex.withLock {
                val profile = activationProfile(draft, savedProfile)
                if (profile == null) {
                    mutableState.update { it.copy(message = "Record at least one valid effort to activate") }
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
        check(profile.canActivate) { "Record at least one valid effort to activate" }
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
            refitRevision.incrementAndGet()
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
            refitRevision.incrementAndGet()
            check(displayedTable() == expected) { "The table changed. Close the editor and reopen the latest table." }
            require(edited.baseline == expected.baseline && edited.gains.size == 496 &&
                edited.gains.all { it.isFinite() && it in CalibrationCorrectionLimits.logRange })
            val source = (activeProfile ?: activationProfile(draft, savedProfile) ?: draft)
                ?: error("Record a calibration effort before editing.")
            check(verifiedBaseline?.first?.let { it == edited.baseline } != false) { "The bike baseline changed. Reopen calibration." }
            // Preserve any newer real efforts even when the user edited the active snapshot.
            val editBase = draft?.takeIf { it.matches(source.capture) } ?: source
            val capture = editBase.capture
            val updated = withContext(Dispatchers.Default) {
                applyUnifiedManualEdit(editBase, edited, System.currentTimeMillis())
            }
            val table = updated.table!!
            val generation = synchronized(activationLock) { activationGeneration }
            val incumbent = activeProfile
            val retainedSaved = savedProfile
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { store.save(CalibrationBackup(updated, retainedSaved)) }
                draft = updated
                savedProfile = retainedSaved
                mutableState.update { it.copy(table = table, count = capture.samples.size,
                    savedCount = compatibleSavedCount(), errors = calibrationErrorComparison(draft, activeProfile, savedProfile),
                    displayTable = displayedTable(), message = "Manual changes saved · activate when ready") }
                if (incumbent != null && updated.canActivate) {
                    try { publishActive(updated, incumbent, generation, automatic = false) }
                    catch (e: Exception) { throw IllegalStateException("Edits saved as a draft; active update failed. Close and activate the draft to retry.", e) }
                } else if (incumbent != null) deactivate("No valid recorded point · correction off")
            }
        }
    }
    internal suspend fun importBackup(backup: CalibrationBackup): CalibrationLoadResult {
        require(backup.hasData) { "This file contains no calibration data." }
        require(backup.saved == null || backup.saved.canActivate) { "Saved activation table needs a valid recorded effort." }
        val normalized = backup.copy(draft = backup.draft?.withActivationReadiness(), saved = backup.saved?.withActivationReadiness())
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
                    store.save(normalized)
                }
                synchronized(activationLock) {
                    ++activationGeneration
                    draft = normalized.draft
                    savedProfile = normalized.saved
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
    /** Explicit offline operation only. No sample targets, activation gates, or live watermarks change. */
    suspend fun prepareRefit(): CalibrationRefitPreview {
        initialized.await()
        return mutex.withLock {
            check(!recordingRequested && !mutableState.value.recording) { "Pause recording before refitting saved data." }
            val source = activeProfile ?: activationProfile(draft, savedProfile) ?: draft
                ?: error("Record calibration data before refitting.")
            val table = source.table ?: error("Record a usable calibration table first.")
            val capture = draft?.capture ?: source.capture
            check(source.matches(capture)) { "The bike baseline changed. Reopen calibration." }
            check(verifiedBaseline?.first?.let { it == table.baseline } != false) { "The bike baseline changed. Reopen calibration." }
            val revision = refitRevision.get()
            val generation = activationGeneration
            val beforeDraft = draft; val beforeSaved = savedProfile; val beforeActive = activeProfile
            val baseline = verifiedBaseline?.first
            val fitted = withContext(Dispatchers.Default) { refitCalibrationTable(table, capture.samples, PelotonCadenceReference.forCapture(capture)) }
                ?: error("No original pedal measurements are available for populated cells.")
            // The displayed active snapshot can lag the draft. A refit consumes the retained
            // capture, so do not rewind its watermark and replay those efforts on next Record.
            val through = maxOf(table.recordedThrough, draft?.table?.recordedThrough ?: Long.MIN_VALUE,
                eligibleCalibrationErrorSamples(capture.baseline, capture.samples).maxOfOrNull { it.capturedAt } ?: Long.MIN_VALUE)
            val result = fitted.copy(table = fitted.table.copy(recordedThrough = through))
            check(revision == refitRevision.get() && generation == activationGeneration && !recordingRequested) {
                "Calibration changed. Create a new refit preview."
            }
            val preview = CalibrationRefitPreview(++nextRefitId, result)
            pendingRefit = RefitSession(preview, revision, generation, baseline,
                beforeDraft, beforeSaved, beforeActive, capture)
            preview
        }
    }

    private fun refitStillCurrent(session: RefitSession, applied: Boolean): Boolean =
        session.revision == refitRevision.get() && session.generation == activationGeneration &&
            session.baseline == verifiedBaseline?.first && !recordingRequested && !mutableState.value.recording &&
            if (applied) draft === refitAppliedDraft && savedProfile === refitAppliedSaved && activeProfile === refitAppliedActive
            else draft === session.draft && savedProfile === session.saved && activeProfile === session.active

    private fun publishRefitState(message: String) {
        mutableState.update { it.copy(count = draft?.capture?.samples?.size ?: 0, table = draft?.table,
            active = activeProfile != null, activeCount = activeProfile?.capture?.samples?.size ?: 0,
            savedCount = compatibleSavedCount(), errors = calibrationErrorComparison(draft, activeProfile, savedProfile),
            displayTable = displayedTable(), message = message) }
    }

    suspend fun applyRefit(preview: CalibrationRefitPreview) {
        initialized.await()
        mutex.withLock {
            val session = pendingRefit?.takeIf { it.preview === preview }
                ?: error("This refit preview is no longer current. Create a new preview.")
            check(refitStillCurrent(session, applied = false)) { "Calibration changed. Create a new refit preview." }
            val updated = GrupettoCalibrationProfile(session.capture, preview.result.table)
            val saved = if (session.active != null) updated else session.saved
            val before = CalibrationBackup(session.draft, session.saved)
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { store.save(CalibrationBackup(updated, saved)) }
                val published = synchronized(activationLock) {
                    if (!refitStillCurrent(session, applied = false)) false else {
                        draft = updated; savedProfile = saved
                        if (session.active != null) activeProfile = updated
                        refitAppliedDraft = draft; refitAppliedSaved = savedProfile; refitAppliedActive = activeProfile
                        undoRefitSession = session; pendingRefit = null
                        publishRefitState(if (activeProfile != null) "Saved-data refit applied · Undo available" else "Refit draft saved · correction remains off")
                        true
                    }
                }
                if (!published) {
                    restoreRefitBackup(before)
                    error("Calibration changed while saving. Previous table kept; create a new preview.")
                }
            }
        }
    }

    /** Reversible until another calibration operation changes the saved/applied snapshot. */
    suspend fun undoRefit(preview: CalibrationRefitPreview) {
        initialized.await()
        mutex.withLock {
            val session = undoRefitSession?.takeIf { it.preview === preview }
                ?: error("This refit can no longer be undone.")
            check(refitStillCurrent(session, applied = true)) { "Calibration changed after refitting. Undo is no longer available." }
            val applied = CalibrationBackup(draft, savedProfile)
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { store.save(CalibrationBackup(session.draft, session.saved)) }
                val restored = synchronized(activationLock) {
                    if (!refitStillCurrent(session, applied = true)) false else {
                        draft = session.draft; savedProfile = session.saved; activeProfile = session.active
                        undoRefitSession = null
                        publishRefitState("Refit undone · previous table restored")
                        true
                    }
                }
                if (!restored) {
                    restoreRefitBackup(applied)
                    error("Calibration changed while saving. Undo was not applied.")
                }
            }
        }
    }

    private suspend fun restoreRefitBackup(backup: CalibrationBackup) {
        try { withContext(Dispatchers.IO) { store.save(backup) } }
        catch (e: Exception) {
            deactivate("Saved-state recovery failed · correction off")
            pendingRefit = null; undoRefitSession = null
            throw IllegalStateException("Calibration changed and saved-state recovery failed. Correction is off; reload a backup before continuing.", e)
        }
    }

    private fun compatibleSavedCount() = savedProfile?.takeIf { it.canActivate && (draft == null || it.matches(draft!!.capture)) }
        ?.capture?.samples?.size ?: 0
    private fun displayedTable() = (activeProfile ?: activationProfile(draft, savedProfile) ?: draft)?.table
    fun verifyBaseline(baseline: List<Int>) {
        if (verifiedBaseline?.first != baseline) refitRevision.incrementAndGet()
        verifiedBaseline = baseline to SystemClock.elapsedRealtime()
        activeProfile?.let { profile ->
            if (profile.capture.baseline != baseline) deactivate("Board table changed · Grupetto correction disabled")
        }
    }
    fun clearVerification() { refitRevision.incrementAndGet(); verifiedBaseline = null; mutablePosition.value = null; mutableState.update { it.copy(verified = false) } }
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
