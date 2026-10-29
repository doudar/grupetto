package com.spop.poverlay.calibration

import android.content.Intent
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CalibrationFileControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun saveLoadPreviewCancelAndConfirmationUsePortableBackup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "calibration-shares").apply { mkdirs() }
        val file = File.createTempFile("file-controls-test-", ".json", directory)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.calibration-files", file)
        val baseline = List(31) { 1000 + it * 100 }
        val sample = CalibrationSample(2000.0, 80.0, 200.0, 220.0, 1234)
        val profile = GrupettoCalibrationProfile(CalibrationCapture("test-bike", "test-meter", 2040490,
            baseline, listOf(sample)), GrupettoCalibrationFitter.fit(baseline, listOf(sample)))
        val backup = CalibrationBackup(profile, null)
        val actions = mutableListOf<String>()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                    input: I, options: ActivityOptionsCompat?) {
                    actions += contract.createIntent(context, input).action!!
                    dispatchResult(requestCode, android.app.Activity.RESULT_OK, Intent().setData(uri))
                }
            }
        }
        val canLoad = mutableStateOf(false)
        var loaded: CalibrationBackup? = null
        var rejectLoad = false
        var mismatchLoad = false
        var chooser: Intent? = null
        val sharingContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { chooser = intent }
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalContext provides sharingContext) {
                MaterialTheme { CalibrationFileControls(true, canLoad.value, { backup.encode() }, {
                    check(!rejectLoad) { "This backup belongs to an older bike table." }
                    if (mismatchLoad) throw CalibrationBaselineMismatch()
                    loaded = it
                    CalibrationLoadResult.Loaded
                }) }
            }
        }
        try {
            compose.onNodeWithText("Load").assertIsNotEnabled()
            compose.onNodeWithText("Save").assertIsEnabled().performClick()
            compose.waitUntil(10000) { file.length() > 0 }
            compose.waitForIdle()
            assertEquals(profile.capture, file.inputStream().use(CalibrationBackup::read).draft!!.capture)
            compose.onNodeWithText("Calibration backup saved.").assertExists()
            compose.onNodeWithText("Share").assertIsEnabled().performClick()
            compose.waitUntil(10000) { chooser != null }
            assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
            @Suppress("DEPRECATION")
            val send = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("application/json", send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            @Suppress("DEPRECATION")
            val attachment = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", attachment.scheme)
            assertEquals(attachment, send.clipData!!.getItemAt(0).uri)
            assertEquals(profile.capture, context.contentResolver.openInputStream(attachment)!!.use(CalibrationBackup::read).draft!!.capture)
            compose.runOnIdle { canLoad.value = true }
            compose.onNodeWithText("Load").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Load calibration backup?").fetchSemanticsNodes().isNotEmpty() }
            assertNull(loaded)
            compose.onNodeWithText("Cancel").performClick()
            assertNull(loaded)
            compose.onNodeWithText("Load").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Load table").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Load table").performClick()
            compose.waitUntil(10000) { loaded != null }
            assertEquals(profile.capture, loaded!!.draft!!.capture)
            assertEquals(listOf(Intent.ACTION_CREATE_DOCUMENT, Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_OPEN_DOCUMENT), actions)
            compose.onNodeWithText("Table loaded · correction off").assertIsDisplayed()
            compose.onNodeWithText("OK").performClick()
            compose.runOnIdle { rejectLoad = false; mismatchLoad = true; loaded = null }
            compose.onNodeWithText("Load").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Load table").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Load table").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Table not loaded").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(CalibrationBaselineMismatch().message!!).assertIsDisplayed()
            assertNull(loaded)
            compose.onNodeWithText("OK").performClick()
            compose.runOnIdle { rejectLoad = true; loaded = null }
            compose.onNodeWithText("Load").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Load table").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Load table").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Table not loaded").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Table not loaded").assertIsDisplayed()
            compose.onNodeWithText("This backup belongs to an older bike table.").assertIsDisplayed()
            assertNull(loaded)
            compose.onNodeWithText("OK").performClick()
            assertTrue(runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.calibration-files", File(context.filesDir, "private.json"))
            }.isFailure)
        } finally { file.delete() }
    }
}
