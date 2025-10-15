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
import com.spop.poverlay.overlay.OverlayService
import com.spop.poverlay.releases.Release
import com.spop.poverlay.releases.ReleaseChecker
import com.spop.poverlay.sensor.heartrate.HeartRateDevice
import com.spop.poverlay.sensor.heartrate.HeartRateMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

class ConfigurationViewModel(
    application: Application,
    private val configurationRepository: ConfigurationRepository,
    private val releaseChecker: ReleaseChecker,
) : AndroidViewModel(application) {
    val finishActivity = MutableLiveData<Unit>()
    val requestOverlayPermission = MutableLiveData<Unit>()
    val requestRestart = MutableLiveData<Unit>()
    val requestBluetoothPermissions = MutableLiveData<Array<String>>()
    val showPermissionInfo = mutableStateOf(false)
    val infoPopup = MutableLiveData<String>()

    var latestRelease = mutableStateOf<Release?>(null)

    val showTimerWhenMinimized
        get() = configurationRepository.showTimerWhenMinimized

    val bleTxEnabled
        get() = configurationRepository.bleTxEnabled

    val bleFtmsDeviceName
        get() = configurationRepository.bleFtmsDeviceName

    val showHeartRate
        get() = configurationRepository.showHeartRate

    val heartRateDevices
        get() = configurationRepository.heartRateDevices

    val heartRateAvailableDevices
        get() = heartRateMonitor.availableDevices

    val heartRateConnectedDevice
        get() = heartRateMonitor.connectedDevice

    val heartRateConnectionState
        get() = heartRateMonitor.connectionState

    private val bleServer = (application as GrupettoApplication).bleServer
    private val heartRateMonitor = HeartRateMonitor(application.applicationContext)

    private var pendingEnableHeartRate = false

    init {
        updatePermissionState()
        if (bleTxEnabled.value && hasBluetoothPermissions()) {
            bleServer.start()
        }

        viewModelScope.launch(Dispatchers.IO) {
            configurationRepository.heartRateDevices.collectLatest { devices ->
                heartRateMonitor.setPreferredDevices(devices)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            heartRateMonitor.connectedDevice.collectLatest { device ->
                device?.let { configurationRepository.upsertHeartRateDevice(it) }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            configurationRepository.showHeartRate.collectLatest { enabled ->
                if (enabled) {
                    if (hasBluetoothPermissions()) {
                        heartRateMonitor.start()
                        if (heartRateMonitor.connectedDevice.value == null) {
                            configurationRepository.heartRateDevices.value.firstOrNull()?.let { device ->
                                heartRateMonitor.connect(device.address)
                            }
                        }
                    } else {
                        pendingEnableHeartRate = true
                        requestBluetoothPermissions.postValue(getRequiredBluetoothPermissions())
                    }
                } else {
                    pendingEnableHeartRate = false
                    heartRateMonitor.stop()
                }
            }
        }
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

    fun onBleTxEnabledClicked(isChecked: Boolean) {
        configurationRepository.setBleTxEnabled(isChecked)
        if (isChecked) {
            if (hasBluetoothPermissions()) {
                bleServer.start()
            } else {
                requestBluetoothPermissions.value = getRequiredBluetoothPermissions()
            }
        } else {
            bleServer.stop()
        }
    }

    fun onShowHeartRateClicked(isChecked: Boolean) {
        if (isChecked) {
            if (hasBluetoothPermissions()) {
                configurationRepository.setShowHeartRate(true)
            } else {
                pendingEnableHeartRate = true
                requestBluetoothPermissions.value = getRequiredBluetoothPermissions()
            }
        } else {
            configurationRepository.setShowHeartRate(false)
            pendingEnableHeartRate = false
        }
    }

    fun onConnectHeartRateDevice(device: HeartRateDevice) {
        configurationRepository.upsertHeartRateDevice(device)
        heartRateMonitor.connect(device.address)
    }

    fun onDisconnectHeartRateDevice() {
        heartRateMonitor.disconnect()
    }

    fun onForgetHeartRateDevice(device: HeartRateDevice) {
        configurationRepository.removeHeartRateDevice(device.address)
        heartRateMonitor.forgetDevice(device.address)
    }

    fun onRefreshHeartRateDiscovery() {
        heartRateMonitor.refreshDiscovery()
    }

    fun onBluetoothPermissionsResult(granted: Boolean) {
        if (granted) {
            if (bleTxEnabled.value) {
                bleServer.start()
            }
            if (pendingEnableHeartRate) {
                configurationRepository.setShowHeartRate(true)
            }
            pendingEnableHeartRate = false
            val grantedServices = buildList {
                if (bleTxEnabled.value) {
                    add("BLE broadcast")
                }
                if (configurationRepository.showHeartRate.value) {
                    add("heart-rate monitoring")
                }
            }
            if (grantedServices.isNotEmpty()) {
                infoPopup.postValue(
                    "Bluetooth permissions granted. ${grantedServices.joinToString(" and ")} active."
                )
            }
        } else {
            configurationRepository.setBleTxEnabled(false)
            val disabled = mutableListOf<String>()
            disabled.add("BLE broadcast")
            if (configurationRepository.showHeartRate.value || pendingEnableHeartRate) {
                configurationRepository.setShowHeartRate(false)
                heartRateMonitor.stop()
                disabled.add("heart-rate monitoring")
            }
            pendingEnableHeartRate = false
            infoPopup.postValue(
                "Bluetooth permissions are required for ${disabled.joinToString(" and ")}"
            )
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
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    fun onAppResumed() {
        if ((bleTxEnabled.value || configurationRepository.showHeartRate.value) && !hasBluetoothPermissions()) {
            val permissions = getRequiredBluetoothPermissions()
            requestBluetoothPermissions.value = permissions
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

    override fun onCleared() {
        super.onCleared()
        heartRateMonitor.close()
    }
}