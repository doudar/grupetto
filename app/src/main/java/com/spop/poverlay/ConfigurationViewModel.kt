package com.spop.poverlay

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
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
import com.spop.poverlay.sensor.heartrate.HeartRateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val requestQuit = MutableLiveData<Unit>()
    val requestBluetoothPermissions = MutableLiveData<Array<String>>()
    val requestIgnoreBatteryOptimizations = MutableLiveData<Unit>()
    val requestBackgroundLocationPermission = MutableLiveData<Unit>()

    private val _backgroundLocationGranted = MutableStateFlow(hasBackgroundLocationPermission())
    val backgroundLocationGranted: StateFlow<Boolean> = _backgroundLocationGranted
    val showPermissionInfo = mutableStateOf(false)
    val infoPopup = MutableLiveData<String>()

    val hrConnectedDevice = HeartRateManager.connectedDevice
    val hrDiscoveredDevices = HeartRateManager.discoveredDevices
    val hrSavedDevices = HeartRateManager.savedDevices
    val hrIsScanning = HeartRateManager.isScanning
    val hrMatchByName = HeartRateManager.matchByName
    val isOverlayRunning: StateFlow<Boolean> = OverlayService.isRunning

    var latestRelease = mutableStateOf<Release?>(null)

    val autoStartOnBoot
        get() = configurationRepository.autoStartOnBoot

    val showTimerWhenMinimized
        get() = configurationRepository.showTimerWhenMinimized

    val bleTxEnabled
        get() = configurationRepository.bleTxEnabled

    val dirConEnabled
        get() = configurationRepository.dirConEnabled

    val bleFtmsDeviceName
        get() = configurationRepository.bleFtmsDeviceName

    val antPlusTxEnabled
        get() = configurationRepository.antPlusTxEnabled


    private val bleServer = (application as GrupettoApplication).bleServer
    private val antPlusServer = (application as GrupettoApplication).antPlusServer
    private var batteryOptimizationPromptShownThisSession = false

    init {
        updatePermissionState()
        HeartRateManager.start(getApplication())
        syncOutboundTransports()
        syncAntPlusTransport()
    }

    private fun updatePermissionState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            showPermissionInfo.value = !Settings.canDrawOverlays(getApplication())
        } else {
            showPermissionInfo.value = false
        }
        _backgroundLocationGranted.value = hasBackgroundLocationPermission()
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            getApplication(),
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun onBackgroundLocationPermissionResult(granted: Boolean) {
        _backgroundLocationGranted.value = hasBackgroundLocationPermission()
        val msg = if (granted) {
            "Background location granted. HRM will auto-connect at boot."
        } else {
            "Background location not granted. Open the app once after boot for HRM to connect."
        }
        infoPopup.postValue(msg)
    }

    fun onAutoStartOnBootClicked(isChecked: Boolean) {
        configurationRepository.setAutoStartOnBoot(isChecked)
        if (isChecked && !hasBackgroundLocationPermission()) {
            requestBackgroundLocationPermission.value = Unit
        }
    }

    fun onShowTimerWhenMinimizedClicked(isChecked: Boolean) {
        configurationRepository.setShowTimerWhenMinimized(isChecked)
    }

    fun onBleTxEnabledClicked(isChecked: Boolean) {
        configurationRepository.setBleTxEnabled(isChecked)
        if (isChecked) {
            if (hasBluetoothPermissions()) {
                syncOutboundTransports()
                requestBatteryOptimizationExemptionIfNeeded()
            } else {
                syncOutboundTransports()
                requestBluetoothPermissions.value = getRequiredBluetoothPermissions()
            }
        } else {
            syncOutboundTransports()
            batteryOptimizationPromptShownThisSession = false
        }
    }

    val antPlusSupported: Boolean get() = antPlusServer.isSupported

    fun onAntPlusTxEnabledClicked(isChecked: Boolean) {
        if (isChecked && (!antPlusSupported || !antPlusServer.isAntPlusAvailable())) {
            infoPopup.postValue("ANT+ requires a supported bike and ANT Radio Service.")
            return
        }
        configurationRepository.setAntPlusTxEnabled(isChecked)
        syncAntPlusTransport()
        requestBatteryOptimizationExemptionIfNeeded()
    }

    private fun syncAntPlusTransport() {
        if (antPlusTxEnabled.value && antPlusSupported) antPlusServer.start()
        else antPlusServer.stop()
    }
    fun onDirConEnabledClicked(isChecked: Boolean) {
        configurationRepository.setDirConEnabled(isChecked)
        syncOutboundTransports()
        requestBatteryOptimizationExemptionIfNeeded()
    }

    fun onBluetoothPermissionsResult(granted: Boolean) {
        if (granted) {
            syncOutboundTransports()
            requestBatteryOptimizationExemptionIfNeeded()
            infoPopup.postValue("Bluetooth permissions granted. BLE service started.")
        } else {
            configurationRepository.setBleTxEnabled(false)
            syncOutboundTransports()
            infoPopup.postValue("Bluetooth permissions are required for BLE functionality.")
        }

        syncAntPlusTransport()
    }

    private fun syncOutboundTransports() {
        val shouldRunBle = bleTxEnabled.value && hasBluetoothPermissions()
        if (shouldRunBle) {
            bleServer.setDirConTransportEnabled(dirConEnabled.value)
            bleServer.start()
        } else {
            bleServer.stop()
            bleServer.setDirConTransportEnabled(dirConEnabled.value)
        }
    }

    private fun getRequiredBluetoothPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        permissions.add(android.Manifest.permission.BLUETOOTH)
        permissions.add(android.Manifest.permission.BLUETOOTH_ADMIN)
        permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
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
        var bluetoothAdvertisePermission = true
        var bluetoothConnectPermission = true
        var bluetoothScanPermission = true


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothAdvertisePermission = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
            bluetoothConnectPermission = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            bluetoothScanPermission = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        }
        return bluetoothPermission && bluetoothAdminPermission && locationPermission &&
                bluetoothAdvertisePermission && bluetoothConnectPermission && bluetoothScanPermission
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

    fun onQuitClicked() {
        requestQuit.value = Unit
    }

    fun onClickedRelease(release: Release) {
        val browserIntent = Intent(Intent.ACTION_VIEW, release.url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(browserIntent)
    }

    fun startHeartRateDiscovery() {
        HeartRateManager.startDiscovery()
    }

    fun stopHeartRateDiscovery() {
        HeartRateManager.stopDiscovery()
    }

    fun connectHeartRateDevice(device: HeartRateDevice) {
        HeartRateManager.connectTo(device)
    }

    fun forgetHeartRateDevice(address: String) {
        HeartRateManager.forgetDevice(address)
    }

    fun disconnectHeartRateDevice() {
        HeartRateManager.disconnectCurrent()
    }

    fun setHrMatchByName(enabled: Boolean) {
        HeartRateManager.setMatchByName(enabled)
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
        if (isOverlayRunning.value) {
            ContextCompat.startForegroundService(
                getApplication(),
                Intent(getApplication(), OverlayService::class.java).apply {
                    action = OverlayService.ActionMinimizeOverlay
                }
            )
        }
        syncOutboundTransports()
        if (bleTxEnabled.value && !hasBluetoothPermissions()) {
            val permissions = getRequiredBluetoothPermissions()
            requestBluetoothPermissions.value = permissions
        } else if ((bleTxEnabled.value || dirConEnabled.value) &&
            (!bleTxEnabled.value || hasBluetoothPermissions())
        ) {
            requestBatteryOptimizationExemptionIfNeeded()
        }
        syncAntPlusTransport()
    }

    fun onAppStopped() {
        if (isOverlayRunning.value) {
            ContextCompat.startForegroundService(
                getApplication(),
                Intent(getApplication(), OverlayService::class.java).apply {
                    action = OverlayService.ActionRestoreOverlay
                }
            )
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        val context = getApplication<Application>()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    private fun requestBatteryOptimizationExemptionIfNeeded() {
        if ((!bleTxEnabled.value && !dirConEnabled.value && !antPlusTxEnabled.value) || batteryOptimizationPromptShownThisSession) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizations()) {
            batteryOptimizationPromptShownThisSession = true
            requestIgnoreBatteryOptimizations.postValue(Unit)
        }
    }

    fun onBatteryOptimizationRequestCompleted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        val prompt = if (isIgnoringBatteryOptimizations()) {
            "Battery optimization disabled for Grupetto. BLE reliability should improve while idle."
        } else {
            "Battery optimization is still enabled. If BLE drops after idle, set Grupetto battery to Unrestricted."
        }
        infoPopup.postValue(prompt)
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
