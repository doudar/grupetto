package com.spop.poverlay

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.spop.poverlay.ble.BleServer
import com.spop.poverlay.overlay.OverlayService
import com.spop.poverlay.releases.Release
import com.spop.poverlay.releases.ReleaseChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

class ConfigurationViewModel(
    application: Application,
    private val configurationRepository: ConfigurationRepository,
    private val releaseChecker: ReleaseChecker
) : AndroidViewModel(application) {
    val finishActivity = MutableLiveData<Unit>()
    val requestOverlayPermission = MutableLiveData<Unit>()
    val requestRestart = MutableLiveData<Unit>()
    val requestBluetoothPermissions = MutableLiveData<Array<String>>()
    val showPermissionInfo = mutableStateOf(false)
    val infoPopup = MutableLiveData<String>()

    // Map of release names to if they're the currently installed one
    var latestRelease = mutableStateOf<Release?>(null)

    val showTimerWhenMinimized
        get() = configurationRepository.showTimerWhenMinimized

    val bleEnabled
        get() = configurationRepository.bleEnabled

    val bleDeviceName
        get() = configurationRepository.bleDeviceName

    init {
        updatePermissionState()
    }

    private fun updatePermissionState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            showPermissionInfo.value = !Settings.canDrawOverlays(getApplication())
        } else {
            showPermissionInfo.value = false
        }
    }

    fun onShowTimerWhenMinimizedClicked(isChecked: Boolean) {
        configurationRepository.setShowTimerWhenMinimized(isChecked)
    }

    fun onbleEnabledClicked(isChecked: Boolean) {
        // Always update the state immediately to reflect user intent
        configurationRepository.setbleEnabled(isChecked)
        
        if (isChecked && !hasBluetoothPermissions()) {
            // Request permissions, but keep the checkbox checked to show user intent
            val permissions = getRequiredBluetoothPermissions()
            requestBluetoothPermissions.value = permissions
            return
        }
        
        if (isChecked) {
            startBLEServer()
        } else {
            stopBLEServer()
        }
    }

    fun onBluetoothPermissionsResult(granted: Boolean) {
        if (granted) {
            // Permissions granted - start the service (state is already set to true)
            startBLEServer()
            infoPopup.postValue("Bluetooth permissions granted. BLE server started.")
        } else {
            // Permissions denied - revert the checkbox state
            configurationRepository.setbleEnabled(false)
            infoPopup.postValue("Bluetooth permissions are required for BLE server functionality.")
        }
    }

    private fun getRequiredBluetoothPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        
        // Always required permissions
        permissions.add(android.Manifest.permission.BLUETOOTH)
        permissions.add(android.Manifest.permission.BLUETOOTH_ADMIN)
        permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        
        // Android 12+ permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        return permissions.toTypedArray()
    }

    fun onbleDeviceNameChanged(name: String) {
        configurationRepository.setbleDeviceName(name)
    }

    private fun startBLEServer() {
        if (hasBluetoothPermissions()) {
            try {
                val intent = Intent(getApplication(), BleServer::class.java).apply {
                    action = BleServer.ACTION_START
                }
                ContextCompat.startForegroundService(getApplication(), intent)
                Timber.i("Started BLE FTMS service")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start BLE FTMS service")
                infoPopup.postValue("Failed to start BLE FTMS service: ${e.message}")
            }
        } else {
            infoPopup.postValue("Missing Bluetooth permissions for BLE FTMS")
        }
    }

    private fun stopBLEServer() {
        try {
            val intent = Intent(getApplication(), BleServer::class.java).apply {
                action = BleServer.ACTION_STOP
            }
            getApplication<Application>().startService(intent)
            Timber.i("Stopped BLE Server")
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop BLE Server")
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        val context = getApplication<Application>()
        
        val bluetoothPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.BLUETOOTH
        ) == PackageManager.PERMISSION_GRANTED
        
        val bluetoothAdminPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.BLUETOOTH_ADMIN
        ) == PackageManager.PERMISSION_GRANTED
        
        val locationPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        // Check for Android 12+ permissions
        var bluetoothAdvertisePermission = true
        var bluetoothConnectPermission = true
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothAdvertisePermission = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
            
            bluetoothConnectPermission = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        }
        
        return bluetoothPermission && bluetoothAdminPermission && locationPermission &&
                bluetoothAdvertisePermission && bluetoothConnectPermission
    }

    fun onStartServiceClicked() {
        Timber.i("Starting service")
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), OverlayService::class.java)
        )
        finishActivity.value = Unit
    }

    fun onGrantPermissionClicked() {
        requestOverlayPermission.value = Unit
    }

    fun onRestartClicked() {
        requestRestart.value = Unit
    }

    fun onClickedRelease(release: Release) {
        val browserIntent = Intent(Intent.ACTION_VIEW, release.url)
        getApplication<Application>().startActivity(browserIntent)
    }

    fun onResume() {
        updatePermissionState()
        viewModelScope.launch(Dispatchers.IO) {
            releaseChecker.getLatestRelease()
                .onSuccess { release ->
                    latestRelease.value = release
                }
                .onFailure {
                    Timber.e(it, "failed to fetch release info")
                }
        }
    }

    fun onOverlayPermissionRequestCompleted(wasGranted: Boolean) {
        updatePermissionState()
        val prompt = if (wasGranted) {
            "Permission granted, click 'Start Overlay' to get started"
        } else {
            "Without this permission the app cannot function"
        }
        infoPopup.postValue(prompt)
    }

}