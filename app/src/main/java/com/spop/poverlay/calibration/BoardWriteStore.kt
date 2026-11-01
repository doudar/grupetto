package com.spop.poverlay.calibration

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

internal class BoardWriteStore(directory: File) : BoardJournalStore {
    private val file = AtomicFile(File(directory, "bike-board-write-journal.json"))
    override fun load(): BoardWriteJournal? {
        val stream = try { file.openRead() } catch (e: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw e
            return null
        }
        val json = stream.bufferedReader().use { JSONObject(it.readText()) }
        check(json.getInt("schema") == 1)
        fun table(key: String) = json.getJSONArray(key).let { a -> List(a.length()) { a.getInt(it) } }
        return BoardWriteJournal(table("original"), table("target"), table("expected"), json.getString("frameSerial"),
            if (json.isNull("pendingIndex")) null else json.getInt("pendingIndex"),
            json.getBoolean("complete"), json.getBoolean("restoring")).also {
            check(validCalibrationTable(it.original) && validCalibrationTable(it.target) && it.expected.size == 31 &&
                (it.pendingIndex == null || it.pendingIndex in 1..29) && it.frameSerial.isNotBlank())
        }
    }
    override fun save(journal: BoardWriteJournal) {
        val json = JSONObject().put("schema", 1).put("original", JSONArray(journal.original))
            .put("target", JSONArray(journal.target)).put("expected", JSONArray(journal.expected))
            .put("frameSerial", journal.frameSerial).put("pendingIndex", journal.pendingIndex ?: JSONObject.NULL)
            .put("complete", journal.complete).put("restoring", journal.restoring)
        val stream = file.startWrite()
        try { stream.write(json.toString(2).toByteArray()); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
