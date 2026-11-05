package com.spop.poverlay.calibration

import android.util.AtomicFile
import org.json.JSONObject
import org.json.JSONException
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.FileOutputStream
import java.util.UUID

/** Bound persisted and imported JSON before allocating its complete text. I/O errors propagate. */
internal fun boundedCalibrationText(input: InputStream): String {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= CalibrationBackup.MAX_BYTES) {
            "Calibration file is too large (maximum 32 MB)."
        }
        output.write(buffer, 0, count)
    }
    return output.toString("UTF-8")
}

/** Reject a backup recorded against a different onboard baseline. */
internal class CalibrationBaselineMismatch : IllegalStateException(
    "This backup was recorded against a different onboard bike table. Its correction cannot be used with the current bike table. Your current app tables have been kept.")

internal enum class CalibrationLoadResult { Loaded }

/** A single atomic snapshot keeps an unfinished draft and the last usable table together. */
internal data class CalibrationBackup(
    val draft: GrupettoCalibrationProfile?,
    val saved: GrupettoCalibrationProfile?,
    // Durable startup-reset marker survives a crash before calibration preferences are updated.
    val resetAt: Long = 0,
) {
    val hasData get() = draft != null || saved != null
    fun encode(): String = JSONObject().put("format", "grupetto-calibration-backup").put("schema", 2)
        .put("draft", draft?.let(GrupettoCalibrationStore::encode) ?: JSONObject.NULL)
        .put("saved", saved?.let(GrupettoCalibrationStore::encode) ?: JSONObject.NULL)
        .also { if (resetAt > 0) it.put("resetAt", resetAt) }.toString()

    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024

        fun read(input: InputStream): CalibrationBackup {
            return decode(boundedCalibrationText(input)).also {
                require(it.hasData) { "This file contains no calibration data." }
            }
        }

        fun decode(text: String): CalibrationBackup {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Calibration file is too large." }
            val json = JSONObject(text)
            val schema = json.getInt("schema")
            require(json.getString("format") == "grupetto-calibration-backup" && schema in 1..2) {
                "Unsupported calibration file format."
            }
            fun profile(key: String): GrupettoCalibrationProfile? {
                require(json.has(key)) { "Incomplete calibration file." }
                return if (json.isNull(key)) null else GrupettoCalibrationStore.decode(json.getJSONObject(key))
            }
            val draft = profile("draft")
            val saved = profile("saved")
            require(saved == null || saved.canActivate) { "Saved activation table needs a valid recorded effort." }
            require(draft == null || saved == null || saved.matches(draft.capture)) {
                "Draft and saved table belong to different calibrations."
            }
            require(schema == 2 || !json.has("resetAt")) { "Unsupported reset marker." }
            val resetAt = if (json.has("resetAt")) json.getLong("resetAt") else 0L
            require(resetAt >= 0 && (resetAt == 0L || draft == null && saved == null)) { "Invalid reset marker." }
            return CalibrationBackup(draft, saved, resetAt)
        }
    }
}

internal class CalibrationBackupStore(private val directory: File) {
    private val file = AtomicFile(File(directory, "grupetto-calibration.json"))

    /** Startup recovery is separate from explicit imports, which must leave current data alone. */
    var resetOnLoad: Boolean = false
        private set

    fun load(): CalibrationBackup {
        resetOnLoad = false
        val stream = try { file.openRead() } catch (e: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw e
            return loadSeparateFiles()
        }
        val (backup, legacy) = try {
            // Only format errors trigger quarantine. Transient read/close failures propagate.
            val text = stream.use(::boundedCalibrationText)
            val json = JSONObject(text)
            val backup = CalibrationBackup.decode(text)
            val legacy = json.getInt("schema") == 1 || listOf("draft", "saved").any { key ->
                !json.isNull(key) && json.getJSONObject(key).getInt("schema") < 4
            }
            backup to legacy
        } catch (e: Exception) {
            if (!isInvalidCalibration(e)) throw e
            return discardUnusableCalibration()
        }
        // Keep this write outside the format-error catch. A failed upgrade must preserve the
        // valid old snapshot via AtomicFile, rather than misclassifying it as corrupt.
        if (legacy) {
            preserveCalibrationMigrationSource(file.baseFile)
            save(backup)
        }
        resetOnLoad = backup.resetAt > 0
        return backup
    }

    private fun loadSeparateFiles(): CalibrationBackup {
        val backup = try {
            CalibrationBackup(
                GrupettoCalibrationStore(directory, "grupetto-power-draft.json").load(),
                GrupettoCalibrationStore(directory, "grupetto-power-active.json").load()).also {
                require(it.saved == null || it.saved.canActivate)
                require(it.draft == null || it.saved == null || it.saved.matches(it.draft.capture))
            }
        } catch (e: Exception) {
            if (!isInvalidCalibration(e)) throw e
            return discardUnusableCalibration()
        }
        // Separate files stay available for recovery after the unified snapshot is committed.
        if (backup.hasData) save(backup)
        return backup
    }

    private fun isInvalidCalibration(error: Exception) = error is JSONException ||
        error is IllegalArgumentException || error is IllegalStateException

    private fun calibrationFiles(): List<File> = listOf("grupetto-calibration.json",
        "grupetto-power-draft.json", "grupetto-power-active.json").flatMap { name ->
        listOf("", ".bak", ".new").map { suffix -> File(directory, name + suffix) }
    }

    private fun discardUnusableCalibration(): CalibrationBackup {
        val originals = calibrationFiles().filter { it.exists() }
        val suffix = ".unusable-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        // Copy every source before replacing anything. Partial copy failure leaves all original
        // files intact, and a failed atomic tombstone write cannot resurrect a stale legacy table.
        originals.forEach { original ->
            val recovery = File(original.path + suffix)
            check(recovery.createNewFile()) { "Could not reserve calibration recovery file." }
            original.inputStream().use { input ->
                FileOutputStream(recovery).use { output -> input.copyTo(output); output.fd.sync() }
            }
        }
        val empty = CalibrationBackup(null, null, resetAt = System.currentTimeMillis())
        save(empty)
        // The committed empty snapshot is authoritative even if a stale source cannot be removed.
        // Recovery copies are deliberately retained and are never considered by the loader.
        originals.filter { it != file.baseFile }.forEach { original ->
            if (original.exists()) original.delete()
        }
        resetOnLoad = true
        return empty
    }

    fun save(backup: CalibrationBackup) {
        val stream = file.startWrite()
        try { stream.write(backup.encode().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
