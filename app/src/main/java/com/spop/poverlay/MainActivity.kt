package com.spop.poverlay

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.spop.poverlay.DataBase.DBHelper
import com.spop.poverlay.DataBase.GlobalVariables

import com.spop.poverlay.releases.ReleaseChecker
import com.spop.poverlay.ui.theme.PTONOverlayTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ConfigurationViewModel



    public override fun onResume() {
        super.onResume()

        viewModel.onResume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dbHelper = DBHelper(this)
        val userCount = dbHelper.getUserCount()
        val gv = GlobalVariables(this)

        if (userCount == 0) {
            val newUserId = dbHelper.insertUser("Default User", "", "")
            val intent = Intent(this, UserConfigurationActivity::class.java).apply {
                putExtra("USER_ID", newUserId.toInt())
            }
            startActivity(intent)
        } else if (userCount == 1) {
            val user = dbHelper.getUser(1) // Assuming ID 1 for single user setup
            if (user != null) {
                gv.UserIDSet(user.id)
                gv.HRDeviceAddressSet(user.bleId ?: "")
                gv.HRDeviceNameSet(user.bleName ?: "")
            }
        } else {
            val intent = Intent(this, UserListActivity::class.java)
            startActivity(intent)
        }

        viewModel =
            ConfigurationViewModel(
                application, ConfigurationRepository(applicationContext, this),
                ReleaseChecker()
            )
        viewModel.finishActivity.observe(this) {
            finish()
        }
        viewModel.requestOverlayPermission.observe(this) {
            requestScreenPermission()
        }
        viewModel.requestRestart.observe(this) {
            restartGrupetto()
        }
        viewModel.openHistory.observe(this) {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        viewModel.openUserList.observe(this) {
            startActivity(Intent(this, UserListActivity::class.java))
        }
        viewModel.infoPopup.observe(this) {
            Toast.makeText(
                this,
                it,
                Toast.LENGTH_LONG
            ).show()
        }
        setContent {
            PTONOverlayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ConfigurationPage(
                        viewModel
                    )
                }
            }
        }
        lifecycleScope.launchWhenResumed {
            viewModel.onResume()
        }
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

    private val overlayPermissionRequest =
        registerForActivityResult(StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= 23) {
                viewModel.onOverlayPermissionRequestCompleted(
                    Settings.canDrawOverlays(this)
                )
            }
        }

    private fun requestScreenPermission() = Intent(
        "android.settings.action.MANAGE_OVERLAY_PERMISSION",
        Uri.parse("package:${packageName}")
    ).apply {
        overlayPermissionRequest.launch(this)
    }
}


@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    PTONOverlayTheme {
        Configuration()
    }
}
