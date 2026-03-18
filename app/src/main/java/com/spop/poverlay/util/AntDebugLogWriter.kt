package com.spop.poverlay.util

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AntDebugLogWriter(private val context: Context) {
    companion object {
        const val FileName = "grupetto-ant-debug.log"
        private const val Tag = "AntDebugLogWriter"
        private const val RelativeDownloadsPath = "Download/"
    }

    private val lock = Any()
    private val timestampFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile
    private var mediaStoreUriString: String? = null

    fun append(level: String, message: String, throwable: Throwable? = null) {
        val line = buildString {
            append(timestampFormatter.format(Date()))
            append(" [")
            append(level)
            append("] ")
            append(message)
            if (throwable != null) {
                append(" | ")
                append(throwable::class.java.simpleName)
                append(": ")
                append(throwable.message ?: "<no message>")
            }
            append('\n')
        }

        synchronized(lock) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appendScopedStorage(line)
                } else {
                    appendLegacy(line)
                }
            }.onFailure {
                Log.e(Tag, "Failed to append ANT debug log", it)
            }
        }
    }

    fun describeLocation(): String = "Downloads/$FileName"

    private fun appendLegacy(line: String) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }

        val logFile = File(downloadsDir, FileName)
        FileOutputStream(logFile, true).bufferedWriter().use { writer ->
            writer.append(line)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun appendScopedStorage(line: String) {
        val uri = resolveOrCreateDownloadsUri() ?: error("Unable to resolve MediaStore downloads Uri")
        context.contentResolver.openOutputStream(uri, "wa")?.bufferedWriter()?.use { writer ->
            writer.append(line)
        } ?: error("Unable to open output stream for $uri")
    }

    @SuppressLint("InlinedApi")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun resolveOrCreateDownloadsUri() = mediaStoreUriString?.let(android.net.Uri::parse) ?: run {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(FileName, RelativeDownloadsPath)

        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return@run ContentUris.withAppendedId(collection, id).also {
                    mediaStoreUriString = it.toString()
                }
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RelativeDownloadsPath)
        }

        resolver.insert(collection, values)?.also {
            mediaStoreUriString = it.toString()
        }
    }
}

