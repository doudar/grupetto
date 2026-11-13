package com.spop.poverlay

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.text.HtmlCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.spop.poverlay.overlay.OverlayService
import com.spop.poverlay.sensor.heartrate.HeartRateManager
import com.spop.poverlay.releases.ReleaseChecker
import com.spop.poverlay.ui.theme.PTONOverlayTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ConfigurationViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if ((application as GrupettoApplication).emulatedModel != null) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        viewModel =
            ConfigurationViewModel(
                application, ConfigurationRepository(applicationContext, this,
                    (application as GrupettoApplication).configurationPreferencesName),
                ReleaseChecker()
            )
        viewModel.finishActivity.observe(this) {
            finish()
        }
        viewModel.requestOverlayPermission.observe(this) {
            requestScreenPermission()
        }
        viewModel.requestBluetoothPermissions.observe(this) { permissions ->
            requestBluetoothPermissions(permissions)
        }
        viewModel.requestRestart.observe(this) {
            restartGrupetto()
        }
        viewModel.requestDiscoveryReportExport.observe(this) {
            try { discoveryReportExport.launch("grupetto-bike-services.json") }
            catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "No document picker installed. The report remains saved in Grupetto.", Toast.LENGTH_LONG).show()
            }
        }
        viewModel.requestQuit.observe(this) {
            quitGrupetto()
        }
        viewModel.requestIgnoreBatteryOptimizations.observe(this) {
            requestIgnoreBatteryOptimizations()
        }
        viewModel.requestBackgroundLocationPermission.observe(this) {
            requestBackgroundLocationPermission()
        }
        viewModel.infoPopup.observe(this) {
            Toast.makeText(
                this,
                it,
                Toast.LENGTH_LONG
            ).show()
        }
        viewModel.showCalibration.value = intent.getBooleanExtra("showCalibration", false)
        val upgradeNotice = (application as GrupettoApplication).serialNameUpgradeNotice
        setContent {
            PTONOverlayTheme {
                var showUpgradeNotice by remember { mutableStateOf(upgradeNotice.shouldShow) }
                if (showUpgradeNotice) {
                    SerialNameUpgradeDialog {
                        upgradeNotice.dismiss()
                        showUpgradeNotice = false
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background,
                ) {
                    ConfigurationPage(
                        viewModel
                    )
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.onResume()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppResumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("showCalibration", false)) viewModel.showCalibration.value = true
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppStopped()
    }

    private fun restartGrupetto() {
        Toast.makeText(
            this@MainActivity,
            HtmlCompat.fromHtml("<big>Restarting Grupetto</big>", HtmlCompat.FROM_HTML_MODE_LEGACY),
            Toast.LENGTH_LONG
        )
            .apply { setGravity(Gravity.CENTER, 0, 0) }
            .show()

        CoroutineScope(Dispatchers.IO).launch {
            delay(1500L)
            val pm: PackageManager = applicationContext.packageManager
            val intent = pm.getLaunchIntentForPackage(applicationContext.packageName)
            val mainIntent = Intent.makeRestartActivityTask(intent!!.component)
            applicationContext.startActivity(mainIntent)
            Runtime.getRuntime().exit(0)
        }
    }

    private fun quitGrupetto() {
        Toast.makeText(
            this@MainActivity,
            HtmlCompat.fromHtml("<big>Closing Grupetto</big>", HtmlCompat.FROM_HTML_MODE_LEGACY),
            Toast.LENGTH_LONG
        ).apply { setGravity(Gravity.CENTER, 0, 0) }.show()

        CoroutineScope(Dispatchers.Main).launch {
            // Explicitly stop long-running components before closing the task so Android won't revive it.
            stopService(Intent(this@MainActivity, OverlayService::class.java))
            HeartRateManager.stop()
            (application as GrupettoApplication).bikeCalibration.stop()
            // Radio teardown makes blocking IPC calls (ANT channel release, GATT close); keep it
            // off the main thread so a slow radio service can't trigger an ANR while quitting.
            withContext(Dispatchers.IO) {
                (application as GrupettoApplication).powerMeterManager.stop()
                (application as GrupettoApplication).bleServer.stop()
                (application as GrupettoApplication).antPlusServer.stop()
            }
            delay(750L)
            finishAffinity()
            finishAndRemoveTask()
        }
    }

    private val overlayPermissionRequest =
        registerForActivityResult(StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= 23) {
                viewModel.onOverlayPermissionRequestCompleted(
                    Settings.canDrawOverlays(this)
                )
            }
        }

    private val discoveryReportExport = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportBikeDiscoveryReport(it) } }

    private val bluetoothPermissionRequest =
        registerForActivityResult(RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            viewModel.onBluetoothPermissionsResult(allGranted)
        }

    private val batteryOptimizationRequest =
        registerForActivityResult(StartActivityForResult()) {
            viewModel.onBatteryOptimizationRequestCompleted()
        }

    private var backgroundRequestStartedAt = 0L
    // Set when "while using" was just granted from the location button; Bluetooth starts once the
    // "all the time" step is answered, so the battery prompt does not cover the location page.
    private var startBluetoothAfterLocation = false

    private val foregroundLocationRequest =
        registerForActivityResult(RequestPermission()) { granted ->
            if (granted) {
                startBluetoothAfterLocation = true
                requestBackgroundLocationPermission()
            }
        }

    private val backgroundLocationPermissionRequest =
        registerForActivityResult(RequestPermission()) { granted ->
            viewModel.onBackgroundLocationPermissionResult(granted)
            if (startBluetoothAfterLocation) {
                startBluetoothAfterLocation = false
                viewModel.onBluetoothPermissionsResult(true)
            }
            // If Android answered at once without showing its location page, use the app info page.
            val answeredInstantly = android.os.SystemClock.elapsedRealtime() - backgroundRequestStartedAt < 700
            if (!granted && answeredInstantly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) openAppSettings()
        }

    private fun hasBackgroundLocation() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Opens this app's settings page (Permissions, then Location, has "Allow all the time"). */
    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun requestScreenPermission() = Intent(
        "android.settings.action.MANAGE_OVERLAY_PERMISSION",
        Uri.parse("package:${packageName}")
    ).apply {
        overlayPermissionRequest.launch(this)
    }

    private fun requestBluetoothPermissions(permissions: Array<String>) {
        bluetoothPermissionRequest.launch(permissions)
    }

    private fun requestBackgroundLocationPermission() {
        val hasForeground = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        // Android asks for "while using the app" first; "all the time" can only follow it.
        if (!hasForeground) {
            foregroundLocationRequest.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasBackgroundLocation()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // On Android 11+ asking for background location on its own (once "while using" is granted)
            // should open this app's location page, which has "Allow all the time".
            android.widget.Toast.makeText(
                this,
                "Choose \"Allow all the time\" so heart-rate monitors and power meters reconnect during a ride.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            backgroundRequestStartedAt = android.os.SystemClock.elapsedRealtime()
            backgroundLocationPermissionRequest.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            backgroundLocationPermissionRequest.launch(
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        val packageUri = Uri.parse("package:$packageName")
        val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)

        try {
            batteryOptimizationRequest.launch(requestIntent)
        } catch (_: ActivityNotFoundException) {
            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            batteryOptimizationRequest.launch(fallbackIntent)
        }
    }
}


@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    PTONOverlayTheme {
        // Preview placeholder - actual ConfigurationPage requires ViewModel
    }
}
