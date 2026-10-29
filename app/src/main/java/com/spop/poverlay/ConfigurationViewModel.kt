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
    val emulatedModel get() = (getApplication<Application>() as GrupettoApplication).emulatedModel
    val isPreview get() = emulatedModel != null
    fun emulateModel(model: com.spop.poverlay.sensor.interfaces.EmulatedModel?) {
        val app = getApplication<Application>() as GrupettoApplication
        app.stopService(Intent(app, OverlayService::class.java))
        HeartRateManager.stop()
        app.setEmulatedModel(model)
        requestRestart.value = Unit
    }
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
    val requestDiscoveryReportExport = MutableLiveData<Unit>()
    private val serviceDiscovery = (application as GrupettoApplication).bikeServiceDiscovery
    val discoveryState = serviceDiscovery.state
    val discoveryConnection = serviceDiscovery.connection
    fun discoverBikeServices() { if (!isPreview) serviceDiscovery.scan() }
    fun cancelBikeDiscovery() = serviceDiscovery.cancel()
    fun requestBikeDiscoveryReport() { requestDiscoveryReportExport.value = Unit }
    fun exportBikeDiscoveryReport(uri: android.net.Uri) {
        viewModelScope.launch {
            try { serviceDiscovery.exportReport(uri); infoPopup.value = "Diagnostic report exported." }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                infoPopup.value = "Could not export report: ${e.message}"
            }
        }
    }
    fun useDiscoveredBikeService(result: com.spop.poverlay.sensor.discovery.BikeProbeResult) {
        if (isPreview) return
        viewModelScope.launch(Dispatchers.IO) {
            if (serviceDiscovery.select(result)) restartForBikeService()
            else infoPopup.postValue("Could not save this service. Run discovery again.")
        }
    }
    fun restoreAutomaticBikeService() {
        if (isPreview) return
        viewModelScope.launch(Dispatchers.IO) {
            if (serviceDiscovery.clearSelection()) restartForBikeService()
            else infoPopup.postValue("Could not restore automatic selection.")
        }
    }
    private fun restartForBikeService() {
        val app = getApplication<Application>() as GrupettoApplication
        app.bikeCalibration.setEnabled(false)
        app.sensorInterface.bikeControl?.stop()
        app.stopService(Intent(app, OverlayService::class.java))
        app.bleServer.stop()
        app.bleServer.stopDirConOnly()
        app.antPlusServer.stop()
        app.powerMeterManager.stop()
        requestRestart.postValue(Unit)
    }

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

    val showShifters get() = configurationRepository.showShifters
    val shifterInset get() = configurationRepository.shifterInset
    fun setShowShifters(shown: Boolean) = configurationRepository.setShowShifters(shown)
    fun setShifterInset(inset: Float) = configurationRepository.setShifterInset(inset)

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
    private val bikeControl = (application as GrupettoApplication).sensorInterface.bikeControl
    private val powerMeters = (application as GrupettoApplication).powerMeterManager
    private val calibration = (application as GrupettoApplication).bikeCalibration
    val calibrationSupported get() = calibration.supported
    val calibrationState = calibration.state
    private val boardCalibration = (application as GrupettoApplication).boardCalibration
    val boardCalibrationState = boardCalibration.state
    private val grupettoCalibration = (application as GrupettoApplication).grupettoCalibration
    val grupettoCalibrationState = grupettoCalibration.state
    private fun boardSetupRunning() = boardCalibration.state.value.active || boardCalibration.state.value.busy
    val calibrationMeterReady = kotlinx.coroutines.flow.combine(powerMeters.connectedDevice, powerMeters.reading) { device, reading ->
        device != null && device.address == reading?.address &&
            com.spop.poverlay.sensor.power.freshExternalPower(reading, android.os.SystemClock.elapsedRealtime()) != null
    }
    private fun hasCalibrationMeter() = powerMeters.connectedDevice.value?.let { device ->
        val reading = powerMeters.reading.value
        device.address == reading?.address &&
            com.spop.poverlay.sensor.power.freshExternalPower(reading, android.os.SystemClock.elapsedRealtime()) != null
    } == true
    fun recordGrupettoCalibration(enabled: Boolean) {
        calibration.setMode(com.spop.poverlay.calibration.CalibrationMode.FreeRide)
        if (!calibration.supported || boardSetupRunning()) return
        if (enabled && !hasBluetoothPermissions()) { requestBluetoothPermissions.value = getRequiredBluetoothPermissions(); return }
        if (enabled && !hasCalibrationMeter()) return
        viewModelScope.launch {
            val saved = calibration.savedCapture()
            if (boardSetupRunning() || enabled && !hasCalibrationMeter()) return@launch
            grupettoCalibration.record(enabled, saved)
            if (enabled && !calibration.state.value.enabled) setCalibrationEnabled(true)
            else if (!enabled) setCalibrationEnabled(false)
        }
    }
    fun activateGrupettoCalibration(enabled: Boolean) { if (!enabled || !boardSetupRunning()) grupettoCalibration.activate(enabled) }
    fun clearGrupettoCalibration() { if (!boardSetupRunning()) grupettoCalibration.clear() }
    suspend fun exportCalibrationBackup() = grupettoCalibration.exportBackup()
    internal suspend fun loadCalibrationBackup(backup: com.spop.poverlay.calibration.CalibrationBackup): com.spop.poverlay.calibration.CalibrationLoadResult {
        check(!calibration.state.value.enabled && !boardSetupRunning()) {
            "Pause calibration capture before loading a table."
        }
        return grupettoCalibration.importBackup(backup)
    }
    fun startGuidedCalibration() {
        if (!calibration.supported || boardSetupRunning()) return
        calibration.setMode(com.spop.poverlay.calibration.CalibrationMode.Guided)
        showGuidedCalibration.value = true
        if (!hasBluetoothPermissions()) { requestBluetoothPermissions.value = getRequiredBluetoothPermissions(); return }
        if (!hasCalibrationMeter()) return
        viewModelScope.launch {
            val saved = calibration.savedCapture()
            if (boardSetupRunning() || !hasCalibrationMeter()) return@launch
            grupettoCalibration.record(true, saved)
            if (!calibration.state.value.enabled) setCalibrationEnabled(true)
        }
    }
    fun prepareBoardCalibration() { grupettoCalibration.deactivate("Board setup · Grupetto correction off"); boardCalibration.begin() }
    fun restoreBoardCalibration() { grupettoCalibration.deactivate("Board restore · Grupetto correction off"); boardCalibration.begin(restore = true) }
    fun writeBoardCalibrationPoint() = boardCalibration.writePoint()
    fun closeBoardCalibration() = boardCalibration.close()
    val showCalibration = mutableStateOf(false)
    val showGuidedCalibration = mutableStateOf(false)
    fun showCalibrationTable() { calibration.setMode(com.spop.poverlay.calibration.CalibrationMode.FreeRide) }
    fun setCalibrationMode(mode: com.spop.poverlay.calibration.CalibrationMode) = calibration.setMode(mode)
    fun restCalibration(resting: Boolean) = calibration.rest(resting)
    fun easierCalibration() = calibration.easier()
    fun harderCalibration() = calibration.harder()
    fun skipCalibrationTarget() = calibration.skip()
    fun selectCalibrationTarget(target: com.spop.poverlay.calibration.CalibrationTarget) = calibration.selectTarget(target)
    fun setCalibrationEnabled(enabled: Boolean) {
        if (!calibration.supported) return
        if (enabled && boardSetupRunning()) return
        if (enabled) {
            if (!hasBluetoothPermissions()) {
                requestBluetoothPermissions.value = getRequiredBluetoothPermissions()
                return
            }
            if (!hasCalibrationMeter()) return
            powerMeters.start()
            ContextCompat.startForegroundService(getApplication(), Intent(getApplication(),
                com.spop.poverlay.calibration.CalibrationCaptureService::class.java))
        } else {
            calibration.setEnabled(false)
        }
    }
    fun clearCalibrationCapture() = calibration.clear()
    val powerMeterDevice = powerMeters.connectedDevice
    val powerMeterReading = powerMeters.reading
    val powerMeterStatus = powerMeters.status
    val powerMeterDiscovered = powerMeters.discoveredDevices
    val powerMeterSaved = powerMeters.savedDevices
    val powerMeterScanning = powerMeters.scanning
    val livePowerDisplay = (application as GrupettoApplication).sensorInterface.displayPower
    val liveCadence = (application as GrupettoApplication).sensorInterface.cadence
    val liveResistance = (application as GrupettoApplication).sensorInterface.resistance
    val liveSpeed = (application as GrupettoApplication).sensorInterface.speed
    val liveIncline = (application as GrupettoApplication).sensorInterface.incline
    val isTread = (application as GrupettoApplication).sensorInterface.deviceType == com.spop.poverlay.sensor.interfaces.DeviceType.Tread
    private var managingPowerMeters = false
    fun managePowerMeters(active: Boolean) {
        if (isPreview) return
        managingPowerMeters = active
        if (active && !hasBluetoothPermissions()) {
            requestBluetoothPermissions.value = getRequiredBluetoothPermissions()
            return
        }
        powerMeters.manage(active)
    }
    fun connectPowerMeter(device: HeartRateDevice) { if (!isPreview) powerMeters.connect(device) }
    fun disconnectPowerMeter() { powerMeters.disconnect() }
    fun forgetPowerMeter(address: String) { powerMeters.forget(address) }
    val bikeControlState = bikeControl?.state ?: MutableStateFlow(com.spop.poverlay.control.ControlState())

    fun setBikeTuning(shiftSize: Int, gain: Float, wattsPerShift: Int, inclineSensitivity: Float) {
        bikeControl?.tune(shiftSize, gain, wattsPerShift, inclineSensitivity)
        if (isPreview) return
        bikeControl?.state?.value?.let { state ->
            getApplication<Application>().getSharedPreferences(ConfigurationRepository.SharedPrefsName, Context.MODE_PRIVATE)
                .edit().putInt("bikeShiftSize", state.shiftSize).putFloat("bikeProportionalGain", state.gain)
                .putInt("bikeWattsPerShift", state.wattsPerShift).putFloat("bikeInclineSensitivity", state.inclineSensitivity).apply()
        }
    }
    fun startErg(watts: Int) {
        if (bikeControl?.localErg(watts) != true) infoPopup.value = "Waiting for fresh Bike+ / CrossTrainer data."
    }
    fun startSimulation(incline: Float) {
        if (bikeControl?.localSimulation(incline) != true) infoPopup.value = "Waiting for fresh Bike+ / CrossTrainer data."
    }
    fun setBikeResistance(resistance: Int) { bikeControl?.localResistance(resistance) }
    fun stopBikeControl() { bikeControl?.localManual() }
    private var batteryOptimizationPromptShownThisSession = false

    init {
        updatePermissionState()
        if (!isPreview) HeartRateManager.start(getApplication())
        if (!isPreview) powerMeters.start()
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
        if (!isPreview && isChecked && !hasBackgroundLocationPermission()) {
            requestBackgroundLocationPermission.value = Unit
        }
    }

    fun onShowTimerWhenMinimizedClicked(isChecked: Boolean) {
        configurationRepository.setShowTimerWhenMinimized(isChecked)
    }

    fun onBleTxEnabledClicked(isChecked: Boolean) {
        configurationRepository.setBleTxEnabled(isChecked)
        if (isPreview) return
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

    val antPlusSupported: Boolean get() = (isPreview && emulatedModel != com.spop.poverlay.sensor.interfaces.EmulatedModel.Tread) || antPlusServer.isSupported

    fun onAntPlusTxEnabledClicked(isChecked: Boolean) {
        if (!isPreview && isChecked && (!antPlusSupported || !antPlusServer.isAntPlusAvailable())) {
            infoPopup.postValue("ANT+ requires a supported bike and ANT Radio Service.")
            return
        }
        configurationRepository.setAntPlusTxEnabled(isChecked)
        syncAntPlusTransport()
        requestBatteryOptimizationExemptionIfNeeded()
    }

    private fun syncAntPlusTransport() {
        if (isPreview) return
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
            if (!isPreview) {
                powerMeters.start()
                if (managingPowerMeters) powerMeters.manage(true)
            }
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
        if (isPreview) return
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
        calibration.setEnabled(false)
        bikeControl?.stop()
        powerMeters.stop()
        requestQuit.value = Unit
    }

    fun onClickedRelease(release: Release) {
        val browserIntent = Intent(Intent.ACTION_VIEW, release.url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(browserIntent)
    }

    fun startHeartRateDiscovery() {
        if (isPreview) return
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
        OverlayService.configurationVisible.value = true
        if (isOverlayRunning.value) {
            ContextCompat.startForegroundService(
                getApplication(),
                Intent(getApplication(), OverlayService::class.java).apply {
                    action = OverlayService.ActionMinimizeOverlay
                }
            )
        }
        if (isPreview) return
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
        boardCalibration.close()
        OverlayService.configurationVisible.value = false
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
        if (isPreview) return
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
