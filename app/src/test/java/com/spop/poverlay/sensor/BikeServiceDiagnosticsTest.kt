package com.spop.poverlay.sensor

import android.content.Context
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BikeServiceDiagnosticsTest {
    private val dump = """
        ACTIVITY MANAGER SERVICES
          * ServiceRecord{21863e2 u0 vendor.newbike/.SensorService}
            intent={act=vendor.bike.METRICS cmp=vendor.newbike/.SensorService}
            app=ProcessRecord{bb53271 9938:vendor.newbike/1000}
            extras={private=value}
            Bindings:
              intent={act=android.intent.action.peloton.SensorData}
          * ServiceRecord{123 u0 unrelated.app/.SyncService}
            intent={act=unrelated.sync}
            app=ProcessRecord{111 5678:unrelated.app/1001}
    """.trimIndent()

    @Test fun `snapshot identifies new service and PID but never asserts UART ownership`() {
        val lines = summarizeBikeServices(dump)
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("vendor.newbike/.SensorService"))
        assertTrue(lines.single().contains("pid=9938"))
        assertTrue(lines.single().contains("android.intent.action.peloton.SensorData"))
        assertTrue(lines.single().contains("not proof of serial ownership"))
        assertFalse(lines.single().contains("private=value"))
        assertFalse(lines.single().contains("unrelated.app"))
    }

    @Test fun `device denying dump is distinguished from finding no related service`() {
        assertEquals(listOf("Service dump denied by device policy"), summarizeBikeServices("Permission Denial: missing permission"))
        assertTrue(summarizeBikeServices("").single().contains("No related active service"))
        assertTrue(summarizeBikeServices("Permission Denial: missing android.permission.PACKAGE_USAGE_STATS")
            .single().contains("PACKAGE_USAGE_STATS/usage access"))
    }

    private fun context(dumpGranted: Boolean, logsGranted: Boolean): Context = mockk<Context>().also { context ->
        val pm = mockk<PackageManager>()
        every { context.packageName } returns "test.app"
        every { context.packageManager } returns pm
        every { pm.checkPermission("android.permission.DUMP", "test.app") } returns if (dumpGranted) 0 else -1
        every { pm.checkPermission("android.permission.READ_LOGS", "test.app") } returns if (logsGranted) 0 else -1
        every { pm.checkPermission("android.permission.PACKAGE_USAGE_STATS", "test.app") } returns if (dumpGranted) 0 else -1
    }

    @Test fun `ungranted permissions do not run shell diagnostics`() = runBlocking {
        val logs = mutableListOf<String>()
        BikeServiceDiagnostics(context(false, false), logs::add) { error("Must not execute") }.capture("No telemetry")
        assertTrue(logs.any { it.contains("DUMP=false READ_LOGS=false") })
        assertTrue(logs.any { it.contains("serialOwner=unverified") })
    }

    @Test fun `developer grants enable fixed read-only diagnostic commands`() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val logs = mutableListOf<String>()
        BikeServiceDiagnostics(context(true, true), logs::add) { arguments ->
            commands += arguments
            if (arguments.first().endsWith("dumpsys")) dump else "SerialServiceJNI: could not open /dev/ttyO0"
        }.capture("No telemetry")
        assertEquals(listOf("/system/bin/dumpsys", "-t", "3", "activity", "services"), commands[0])
        assertEquals("/system/bin/logcat", commands[1][0])
        assertTrue(commands[1].contains("*:S"))
        assertTrue(logs.any { it.contains("pid=9938") })
        assertTrue(logs.any { it.contains("may predate this connection") })
    }
}
