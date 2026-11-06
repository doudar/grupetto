package com.spop.poverlay

import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Real overlay windows need the same special permission in tests as in the app.
 * Keep this outside the Compose rule so permission outlives every preview window,
 * and restore the fixture's original app-op even if an assertion fails.
 */
internal class OverlayPermissionRule : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)) {
                base.evaluate()
                return
            }
            val operation = "${context.packageName} SYSTEM_ALERT_WINDOW"
            fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command)
            ).bufferedReader().use { it.readText() }
            fun mode(): String {
                val result = shell("appops get $operation")
                return Regex("(?m)^SYSTEM_ALERT_WINDOW: (allow|deny|ignore|default|foreground)(?:[; ]|$)")
                    .find(result)?.groupValues?.get(1)
                    ?: Regex("(?m)^Default mode: (allow|deny|ignore|default|foreground)$")
                        .find(result)?.groupValues?.get(1)
                    ?: error("Cannot safely preserve overlay app-op: $result")
            }
            val original = mode()
            try {
                shell("appops set $operation allow")
                assertTrue("Shifter preview fixture requires overlay permission", Settings.canDrawOverlays(context))
                base.evaluate()
            } finally {
                shell("appops set $operation $original")
                assertEquals("Restore the original overlay app-op", original, mode())
            }
        }
    }
}
