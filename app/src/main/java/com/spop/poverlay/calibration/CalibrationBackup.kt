package com.spop.poverlay.calibration

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/** Reject a backup recorded against a different onboard baseline. */
internal class CalibrationBaselineMismatch : IllegalStateException(
    "This backup was recorded against a different onboard bike table. Its correction cannot be used with the current bike table. Your current app tables have been kept.")

internal enum class CalibrationLoadResult { Loaded }

/** A single atomic snapshot keeps an unfinished draft and the last usable table together. */
internal data class CalibrationBackup(
    val draft: GrupettoCalibrationProfile?,
    val saved: GrupettoCalibrationProfile?,
) {
    val hasData get() = draft != null || saved != null
    fun encode(): String = JSONObject().put("format", "grupetto-calibration-backup").put("schema", 1)
        .put("draft", draft?.let(GrupettoCalibrationStore::encode) ?: JSONObject.NULL)
        .put("saved", saved?.let(GrupettoCalibrationStore::encode) ?: JSONObject.NULL).toString(2)

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024

        fun read(input: InputStream): CalibrationBackup {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "Calibration file is too large (maximum 4 MB)." }
                output.write(buffer, 0, count)
            }
            return decode(output.toString("UTF-8")).also {
                require(it.hasData) { "This file contains no calibration data." }
            }
        }

        fun decode(text: String): CalibrationBackup {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Calibration file is too large." }
            val json = JSONObject(text)
            require(json.getString("format") == "grupetto-calibration-backup" && json.getInt("schema") == 1) {
                "Unsupported calibration file format."
            }
            fun profile(key: String): GrupettoCalibrationProfile? {
                require(json.has(key)) { "Incomplete calibration file." }
                return if (json.isNull(key)) null else GrupettoCalibrationStore.decode(json.getJSONObject(key))
            }
            val draft = profile("draft")
            val saved = profile("saved")
            require(saved == null || saved.table?.ready == true) { "Saved activation table is not ready." }
            require(draft == null || saved == null || saved.matches(draft.capture)) {
                "Draft and saved table belong to different calibrations."
            }
            return CalibrationBackup(draft, saved)
        }
    }
}

internal class CalibrationBackupStore(private val directory: File) {
    private val file = AtomicFile(File(directory, "grupetto-calibration.json"))

    fun load(): CalibrationBackup {
        val stream = try { file.openRead() } catch (e: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw e
            // Migrate on the next write. Old files stay available until the new snapshot is committed.
            return CalibrationBackup(
                GrupettoCalibrationStore(directory, "grupetto-power-draft.json").load(),
                GrupettoCalibrationStore(directory, "grupetto-power-active.json").load())
        }
        return stream.bufferedReader().use { CalibrationBackup.decode(it.readText()) }
    }

    fun save(backup: CalibrationBackup) {
        val stream = file.startWrite()
        try { stream.write(backup.encode().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
