package com.spop.poverlay.sensor

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optional developer-granted diagnostics. Neither dumpsys nor logcat establishes UART ownership. */
internal class BikeServiceDiagnostics(
    private val context: Context,
    private val log: (String) -> Unit = { Log.i("BikeServices", it) },
    private val runCommand: suspend (List<String>) -> String = ::readDiagnosticCommand,
) {
    fun record(message: String) = log(message)

    suspend fun capture(reason: String) {
        log("$reason; serialOwner=unverified")
        val dump = granted("android.permission.DUMP")
        val logs = granted("android.permission.READ_LOGS")
        val usage = granted("android.permission.PACKAGE_USAGE_STATS")
        log("Diagnostic grants: DUMP=$dump READ_LOGS=$logs PACKAGE_USAGE_STATS=$usage")
        if (dump) {
            try {
                val output = runCommand(listOf("/system/bin/dumpsys", "-t", "3", "activity", "services"))
                summarizeBikeServices(output).forEach(log)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log("Service diagnostics unavailable: ${e.javaClass.simpleName}")
            }
        }
        if (logs) {
            try {
                val output = runCommand(listOf("/system/bin/logcat", "-d", "-t", "100", "-v", "threadtime",
                    "SerialServiceJNI:W", "SerialHandlerManager:W", "*:S"))
                output.lineSequence().filter { it.contains("tty", true) || it.contains("serial", true) ||
                    it.contains("denied", true) }.take(10).forEach {
                    log("Buffered serial diagnostic (may predate this connection): ${it.take(400)}")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log("Serial logs unavailable: ${e.javaClass.simpleName}")
            }
        }
        if (!dump && !logs) log("OpenPelo/ADB can grant optional DUMP, READ_LOGS and usage access for more diagnostics")
    }

    private fun granted(permission: String) =
        context.packageManager.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED
}

/** Keep service identities and binding actions, never full dumps with extras or unrelated app data. */
internal fun summarizeBikeServices(dump: String): List<String> {
    if (dump.contains("Permission Denial", true)) return listOf(
        if (dump.contains("PACKAGE_USAGE_STATS")) "Service dump requires developer-granted PACKAGE_USAGE_STATS/usage access"
        else "Service dump denied by device policy"
    )
    val relevant = Regex("sensor|affernet|metric|bike", RegexOption.IGNORE_CASE)
    val component = Regex("ServiceRecord\\{[^}]* u\\d+ ([^ }]+)")
    val process = Regex("app=ProcessRecord\\{[^ ]+ (\\d+):([^/ }]+)")
    val action = Regex("act=([^ }]+)")
    return dump.split(Regex("(?m)^  \\* ServiceRecord")).mapNotNull { block ->
        // Restore the prefix removed by split. The introductory block contains no matching record.
        val record = if (block.startsWith("{")) "ServiceRecord$block" else block
        val name = component.find(record)?.groupValues?.get(1) ?: return@mapNotNull null
        val actions = action.findAll(record).map { it.groupValues[1] }.distinct().toList()
        if (!relevant.containsMatchIn(name) && actions.none { relevant.containsMatchIn(it) }) return@mapNotNull null
        val proc = process.find(record)
        "Active service=$name pid=${proc?.groupValues?.get(1) ?: "unknown"} " +
            "process=${proc?.groupValues?.get(2) ?: "unknown"} actions=${actions.joinToString()} (not proof of serial ownership)"
    }.take(20).ifEmpty { listOf("No related active service identities visible in the bounded dump") }
}

internal suspend fun readDiagnosticCommand(arguments: List<String>): String = withContext(Dispatchers.IO) {
    coroutineScope {
        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        // Independent of caller cancellation so a blocked pipe read still loses its child process.
        val watchdog = CoroutineScope(Dispatchers.IO).launch { delay(4_000); process.destroy() }
        try {
            process.inputStream.bufferedReader().use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(4096)
                while (output.length < 256 * 1024) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, 256 * 1024 - output.length))
                    if (count < 0) break
                    output.append(buffer, 0, count)
                }
                output.toString()
            }
        } finally {
            process.destroy()
            watchdog.cancel()
        }
    }
}
