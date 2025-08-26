package com.spop.poverlay.ble

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.spop.poverlay.ble.BleUuids
import com.spop.poverlay.ble.CyclingPowerServiceImpl
import com.spop.poverlay.ble.DeviceInformationServiceImpl
import com.spop.poverlay.sensor.interfaces.DummySensorInterface
import com.spop.poverlay.sensor.interfaces.PelotonBikePlusSensorInterface
import com.spop.poverlay.sensor.interfaces.PelotonBikeSensorInterfaceV1New
import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.util.IsBikePlus
import com.spop.poverlay.util.IsRunningOnPeloton
import com.spop.poverlay.util.calculateSpeedFromPelotonV1Power
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * BLE GATT Server Manager
 * Handles BLE advertising, GATT server operations, and service coordination
 */
class BleServerManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val deviceName: String = "Grupetto FTMS"
) {
    
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    
    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _connectionCount = MutableStateFlow(0)
    val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()
    
    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private val subscribedDevices = mutableMapOf<String, MutableSet<BluetoothGattCharacteristic>>()
    
    // Service implementations
    private lateinit var ftmsService: FtmsServiceImpl
    private lateinit var cyclingPowerService: CyclingPowerServiceImpl
    private lateinit var deviceInfoService: DeviceInformationServiceImpl
    
    // Sensor data collection
    private var sensorInterface: SensorInterface? = null
    private var dataUpdateJob: Job? = null
    
    // Data smoothing and timing constants
    companion object {
        private const val UPDATE_INTERVAL_MS = 500L // 500ms for responsive data updates
        private const val SMOOTHING_BUFFER_SIZE = 5 // Number of samples to average
        private const val OUTLIER_THRESHOLD = 2.0 // Standard deviations for outlier detection
    }
    
    // Data smoothing buffers
    private val powerBuffer = mutableListOf<Float>()
    private val cadenceBuffer = mutableListOf<Float>()
    private val resistanceBuffer = mutableListOf<Float>()
    
    // Data tracking variables
    private var startTime: Long = 0
    private var totalDistance: Float = 0f
    private var totalEnergy: Float = 0f
    private var lastPowerValue: Float = 0f
    private var lastUpdateTime: Long = 0
    
    // SharedPreferences for storing device identifier
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("ble_ftms_server", Context.MODE_PRIVATE)
    }
    
    fun initialize(): Boolean {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        
        if (bluetoothAdapter == null) {
            Timber.e("Bluetooth adapter is null")
            return false
        }
        
        if (!bluetoothAdapter!!.isEnabled) {
            Timber.e("Bluetooth is not enabled")
            return false
        }
        
        bluetoothLeAdvertiser = bluetoothAdapter!!.bluetoothLeAdvertiser
        if (bluetoothLeAdvertiser == null) {
            Timber.e("Bluetooth LE Advertiser is not available")
            return false
        }
        
        // Initialize service implementations
        ftmsService = FtmsServiceImpl()
        cyclingPowerService = CyclingPowerServiceImpl()
        deviceInfoService = DeviceInformationServiceImpl(deviceName)
        
        return true
    }
    
    fun startServer(): Boolean {
        if (!initialize()) {
            return false
        }
        
        try {
            gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
            if (gattServer == null) {
                Timber.e("Failed to create GATT server")
                return false
            }
            
            setupGattServices()
            setupSensorInterface()
            
            // Start advertising immediately for testing - this will help determine if issue is with advertising or service setup
            Timber.i("Starting immediate advertising for testing")
            startAdvertising()
            
            // Start collecting sensor data
            startDataUpdates()
            
            return true
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception when starting BLE server")
            return false
        }
    }
    
    fun stopServer() {
        try {
            stopAdvertising()
            stopDataUpdates()
            gattServer?.close()
            gattServer = null
            connectedDevices.clear()
            subscribedDevices.clear()
            _isConnected.value = false
            _connectionCount.value = 0
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception when stopping BLE server")
        }
    }
    
    private fun hasBluetoothPermissions(): Boolean {
        val bluetoothPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.BLUETOOTH
        ) == PackageManager.PERMISSION_GRANTED
        
        val bluetoothAdminPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.BLUETOOTH_ADMIN
        ) == PackageManager.PERMISSION_GRANTED
        
        // For Android 12+ (API 31+) check new permissions
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
        
        return bluetoothPermission && bluetoothAdminPermission && 
               bluetoothAdvertisePermission && bluetoothConnectPermission
    }

    private fun setupGattServices() {
        if (!hasBluetoothPermissions()) {
            Timber.w("Missing Bluetooth permissions for GATT service setup")
            return
        }
        
        try {
            // Add FTMS service first
            gattServer?.addService(ftmsService.createService())
            // Note: Other services will be added in the onServiceAdded callback
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException when adding FTMS service")
        }
    }
    
    private fun addCyclingPowerService() {
        if (!hasBluetoothPermissions()) {
            Timber.w("Missing Bluetooth permissions for Cycling Power service setup")
            return
        }
        
        try {
            gattServer?.addService(cyclingPowerService.createService())
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException when adding Cycling Power service")
        }
    }
    
    private fun addDeviceInformationService() {
        if (!hasBluetoothPermissions()) {
            Timber.w("Missing Bluetooth permissions for Device Information service setup")
            return
        }
        
        try {
            gattServer?.addService(deviceInfoService.createService())
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException when adding Device Information service")
        }
    }
    
    /**
     * Gets or generates a 4-digit device identifier that persists across app runs
     */
    private fun getDeviceIdentifier(): String {
        val key = "device_identifier"
        val existingId = prefs.getString(key, null)
        
        return if (existingId != null) {
            existingId
        } else {
            // Generate a random 4-digit number (1000-9999)
            val newId = Random.nextInt(1000, 10000).toString()
            prefs.edit().putString(key, newId).apply()
            Timber.i("Generated new device identifier: $newId")
            newId
        }
    }
    
    private fun startAdvertising() {
        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0) // Advertise indefinitely
                .build()
            
            // Get persistent 4-digit device identifier for manufacturer data
            val deviceId = getDeviceIdentifier()
            val deviceIdBytes = deviceId.toByteArray()
            
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(BleUuids.FITNESS_MACHINE_SERVICE_UUID))
                //.addServiceUuid(ParcelUuid(BleUuids.CYCLING_POWER_SERVICE_UUID))
                .addManufacturerData(0xFFFF, deviceIdBytes) // Use 0xFFFF as test/development company ID
                .build()
                
            // Set device name for better identification
            try {
                bluetoothAdapter?.setName(deviceName)
            } catch (e: SecurityException) {
                Timber.w(e, "Could not set device name")
            }
            
            Timber.i("Starting BLE advertising with device name: $deviceName, device ID: $deviceId")
            bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception when starting advertising")
        }
    }
    
    private fun stopAdvertising() {
        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            _isAdvertising.value = false
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception when stopping advertising")
        }
    }
    
    fun sendIndoorBikeData(ftmsData: FtmsServiceImpl.FtmsData) {
        ftmsService.sendIndoorBikeData(ftmsData, ::notifyCharacteristicChanged)
    }
    
    fun sendCyclingPowerMeasurement(power: Int, cadence: Int? = null) {
        cyclingPowerService.sendPowerMeasurement(power, cadence, ::notifyCharacteristicChanged)
    }
    
    /**
     * Send data to all applicable BLE services with consistent timing
     */
    fun sendAllSensorData(ftmsData: FtmsServiceImpl.FtmsData) {
        // Send FTMS data
        sendIndoorBikeData(ftmsData)
        
        // Send cycling power data - round cadence instead of truncating
        val cadenceInt = ftmsData.instantaneousCadence.roundToInt()
        Timber.d("BleServerManager: Sending cadence ${ftmsData.instantaneousCadence} -> $cadenceInt to cycling power service")
        sendCyclingPowerMeasurement(ftmsData.instantaneousPower, cadenceInt)
    }
    
    fun sendFitnessMachineStatus(statusData: ByteArray) {
        ftmsService.sendFitnessMachineStatus(statusData, ::notifyCharacteristicChanged)
    }
    
    private fun notifyCharacteristicChanged(characteristic: BluetoothGattCharacteristic): Boolean {
        try {
            if (connectedDevices.isEmpty()) {
                Timber.v("No connected devices to notify")
                return true
            }
            
            val subscribedCount = connectedDevices.count { device ->
                subscribedDevices[device.address]?.contains(characteristic) == true
            }
            
            if (subscribedCount == 0) {
                Timber.v("No devices subscribed to ${characteristic.uuid}")
                return true
            }
            
            Timber.v("Notifying ${connectedDevices.size} connected devices, $subscribedCount subscribed to ${characteristic.uuid}")
            
            var allSuccess = true
            val devicesToRemove = mutableListOf<BluetoothDevice>()
            
            connectedDevices.forEach { device ->
                val deviceAddress = device.address
                if (subscribedDevices[deviceAddress]?.contains(characteristic) == true) {
                    try {
                        val success = gattServer?.notifyCharacteristicChanged(device, characteristic, false) ?: false
                        if (!success) {
                            Timber.w("Failed to notify device $deviceAddress")
                            allSuccess = false
                            devicesToRemove.add(device)
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Exception notifying device $deviceAddress")
                        devicesToRemove.add(device)
                        allSuccess = false
                    }
                }
            }
            
            // Remove devices that failed to receive notifications
            devicesToRemove.forEach { device ->
                Timber.i("Removing unresponsive device: ${device.address}")
                connectedDevices.remove(device)
                subscribedDevices.remove(device.address)
                _isConnected.value = connectedDevices.isNotEmpty()
                _connectionCount.value = connectedDevices.size
            }
            
            return allSuccess
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception when notifying characteristic changed")
            return false
        } catch (e: Exception) {
            Timber.e(e, "Unexpected exception when notifying characteristic changed")
            return false
        }
    }
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Timber.i("BLE advertising started successfully")
            _isAdvertising.value = true
        }
        
        override fun onStartFailure(errorCode: Int) {
            val errorMessage = when (errorCode) {
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                else -> "Unknown error: $errorCode"
            }
            Timber.e("BLE advertising failed: $errorMessage")
            _isAdvertising.value = false
        }
    }
    
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Timber.i("Service added successfully: ${service?.uuid}")
                service?.let {
                    when (it.uuid) {
                        BleUuids.FITNESS_MACHINE_SERVICE_UUID -> {
                            Timber.d("FTMS service added, now adding Cycling Power Service")
                            addCyclingPowerService()
                        }
                        BleUuids.CYCLING_POWER_SERVICE_UUID -> {
                            Timber.d("Cycling Power Service added, now adding Device Information Service")
                            addDeviceInformationService()
                        }
                        BleUuids.DEVICE_INFORMATION_SERVICE_UUID -> {
                            Timber.d("Device Information Service added, setup complete - starting advertising")
                            startAdvertising()
                        }
                    }
                }
            } else {
                Timber.e("Failed to add service: ${service?.uuid}, status: $status")
                // If FTMS service failed, we can't continue
                if (service?.uuid == BleUuids.FITNESS_MACHINE_SERVICE_UUID) {
                    Timber.e("Critical: FTMS service failed to add - stopping server")
                    stopServer()
                } else {
                    // Other services are optional, continue with advertising if this is the last one
                    if (service?.uuid == BleUuids.DEVICE_INFORMATION_SERVICE_UUID) {
                        Timber.w("Device Information Service failed to add, but continuing with advertising")
                        startAdvertising()
                    }
                }
            }
        }
        
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            device?.let {
                synchronized(this@BleServerManager) {
                    val statusDescription = when (status) {
                        BluetoothGatt.GATT_SUCCESS -> "SUCCESS"
                        BluetoothGatt.GATT_FAILURE -> "FAILURE"
                        0x13 -> "REMOTE_USER_TERMINATED"
                        0x16 -> "CONNECTION_TIMEOUT"
                        0x08 -> "CONNECTION_TERMINATED_BY_LOCAL_HOST"
                        else -> "UNKNOWN($status)"
                    }
                    
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                connectedDevices.add(it)
                                subscribedDevices[it.address] = mutableSetOf()
                                _isConnected.value = true
                                _connectionCount.value = connectedDevices.size
                                Timber.i("Device connected successfully: ${it.address}")
                            } else {
                                Timber.w("Device connection failed: ${it.address}, status: $statusDescription")
                            }
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            val wasConnected = connectedDevices.contains(it)
                            connectedDevices.remove(it)
                            subscribedDevices.remove(it.address)
                            _isConnected.value = connectedDevices.isNotEmpty()
                            _connectionCount.value = connectedDevices.size
                            
                            if (wasConnected) {
                                Timber.i("Device disconnected: ${it.address}, reason: $statusDescription")
                            } else {
                                Timber.d("Device connection attempt failed: ${it.address}, reason: $statusDescription")
                            }
                        }
                        BluetoothProfile.STATE_CONNECTING -> {
                            Timber.d("Device connecting: ${it.address}")
                        }
                        BluetoothProfile.STATE_DISCONNECTING -> {
                            Timber.d("Device disconnecting: ${it.address}")
                        }
                    }
                }
            } ?: run {
                Timber.w("Connection state change with null device, status: $status, newState: $newState")
            }
        }
        
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            var data: ByteArray? = null
            var status = BluetoothGatt.GATT_SUCCESS
            
            // Try to handle with each service
            when (characteristic?.service?.uuid) {
                BleUuids.FITNESS_MACHINE_SERVICE_UUID -> {
                    val result = ftmsService.handleCharacteristicRead(characteristic)
                    data = result.first
                    status = result.second
                }
                BleUuids.CYCLING_POWER_SERVICE_UUID -> {
                    val result = cyclingPowerService.handleCharacteristicRead(characteristic)
                    data = result.first
                    status = result.second
                }
                BleUuids.DEVICE_INFORMATION_SERVICE_UUID -> {
                    val result = deviceInfoService.handleCharacteristicRead(characteristic)
                    data = result.first
                    status = result.second
                }
                else -> {
                    status = BluetoothGatt.GATT_READ_NOT_PERMITTED
                }
            }
            
            try {
                gattServer?.sendResponse(device, requestId, status, 0, data)
            } catch (e: SecurityException) {
                Timber.e(e, "Security exception when sending response")
            }
        }
        
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            var status = BluetoothGatt.GATT_SUCCESS
            
            try {
                Timber.d("Descriptor write request from ${device?.address}: descriptor=${descriptor?.uuid}, characteristic=${descriptor?.characteristic?.uuid}, value=${value?.contentToString()}")
                
                if (descriptor == null) {
                    Timber.w("Descriptor is null in write request")
                    status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                } else if (descriptor.uuid == BleUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                    val characteristic = descriptor.characteristic
                    val deviceAddress = device?.address
                    
                    if (value != null && value.size >= 2 && deviceAddress != null && characteristic != null) {
                        // Validate that this descriptor belongs to one of our characteristics
                        val isValidDescriptor = when (characteristic.uuid) {
                            BleUuids.INDOOR_BIKE_DATA_UUID,
                            BleUuids.FITNESS_MACHINE_STATUS_UUID,
                            BleUuids.FITNESS_MACHINE_CONTROL_POINT_UUID,
                            BleUuids.CYCLING_POWER_MEASUREMENT_UUID,
                            BleUuids.CYCLING_POWER_CONTROL_POINT_UUID -> true
                            else -> false
                        }
                        
                        if (!isValidDescriptor) {
                            Timber.w("Descriptor write request for unknown characteristic: ${characteristic.uuid}")
                            status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                        } else {
                            // Set the descriptor value first
                            descriptor.value = value
                            
                            val enabled = (value[0].toInt() and 0x01) != 0 || (value[0].toInt() and 0x02) != 0
                            
                            // Ensure device exists in subscribed devices map
                            if (subscribedDevices[deviceAddress] == null) {
                                subscribedDevices[deviceAddress] = mutableSetOf()
                            }
                            
                            if (enabled) {
                                subscribedDevices[deviceAddress]?.add(characteristic)
                                Timber.i("Device $deviceAddress subscribed to ${characteristic.uuid}")
                            } else {
                                subscribedDevices[deviceAddress]?.remove(characteristic)
                                Timber.i("Device $deviceAddress unsubscribed from ${characteristic.uuid}")
                            }
                        }
                    } else {
                        status = BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                        Timber.w("Invalid descriptor write request: value=${value?.contentToString()}, size=${value?.size}, device=$deviceAddress, characteristic=$characteristic")
                    }
                } else {
                    status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                    Timber.w("Write not permitted for descriptor: ${descriptor?.uuid}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Exception in onDescriptorWriteRequest")
                status = BluetoothGatt.GATT_FAILURE
            }
            
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, status, 0, null)
                    Timber.d("Sent descriptor write response: status=$status")
                } catch (e: SecurityException) {
                    Timber.e(e, "Security exception when sending response")
                }
            }
        }
        
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            var status = BluetoothGatt.GATT_SUCCESS
            
            when (characteristic?.service?.uuid) {
                BleUuids.FITNESS_MACHINE_SERVICE_UUID -> {
                    status = ftmsService.handleCharacteristicWrite(device, characteristic, value, gattServer)
                }
                BleUuids.CYCLING_POWER_SERVICE_UUID -> {
                    status = cyclingPowerService.handleCharacteristicWrite(device, characteristic, value, gattServer)
                }
                else -> {
                    status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                }
            }
            
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, status, 0, null)
                } catch (e: SecurityException) {
                    Timber.e(e, "Security exception when sending response")
                }
            }
        }
    }
    
    // Sensor data collection and management
    fun setupSensorInterface() {
        sensorInterface = if (IsRunningOnPeloton) {
            if (IsBikePlus) {
                PelotonBikePlusSensorInterface(context)
            } else {
                PelotonBikeSensorInterfaceV1New(context)
            }
        } else {
            DummySensorInterface()
        }
    }
    
    fun startDataUpdates() {
        val sensor = sensorInterface ?: return
        
        startTime = System.currentTimeMillis()
        lastUpdateTime = startTime
        resetCounters()
        
        Timber.i("Starting BLE data updates with sensor: ${sensor::class.simpleName}")
        
        dataUpdateJob = lifecycleOwner.lifecycleScope.launch {
            var dataCount = 0
            combine(
                sensor.power,
                sensor.cadence,
                sensor.resistance
            ) { power, cadence, resistance ->
                Triple(power, cadence, resistance)
            }.collect { (power, cadence, resistance) ->
                dataCount++
                if (dataCount % 10 == 0) { // Log every 10th reading to avoid spam
                    Timber.i("BLE raw data #$dataCount: power=$power, cadence=$cadence, resistance=$resistance")
                }
                
                // Add data to smoothing buffers and get smoothed values
                val smoothedPower = addToBufferAndSmooth(powerBuffer, power)
                val smoothedCadence = addToBufferAndSmooth(cadenceBuffer, cadence)
                val smoothedResistance = addToBufferAndSmooth(resistanceBuffer, resistance)
                
                Timber.v("BLE smoothed data: power=$smoothedPower, cadence=$smoothedCadence, resistance=$smoothedResistance")

                // Collect all of the time, but only update services every UPDATE_INTERVAL_MS
                if (System.currentTimeMillis() - lastUpdateTime > UPDATE_INTERVAL_MS) {
                    updateAllServices(smoothedPower, smoothedCadence, smoothedResistance)
                    lastUpdateTime = System.currentTimeMillis()
                }
            }
        }
    }
    
    fun stopDataUpdates() {
        dataUpdateJob?.cancel()
        dataUpdateJob = null
        
        // Stop sensor interface if it has a stop method
        val sensor = sensorInterface
        when (sensor) {
            is PelotonBikePlusSensorInterface -> sensor.stop()
            is PelotonBikeSensorInterfaceV1New -> sensor.stop()
            // DummySensorInterface and others don't need explicit stopping
        }
    }
    
    private fun addToBufferAndSmooth(buffer: MutableList<Float>, newValue: Float): Float {
        // Add new value to buffer
        buffer.add(newValue)
        
        // Keep buffer at fixed size
        if (buffer.size > SMOOTHING_BUFFER_SIZE) {
            buffer.removeAt(0)
        }
        
        // If we don't have enough samples yet, return the current value
        if (buffer.size < 3) {
            return newValue
        }
        
        // Calculate mean and standard deviation for outlier detection
        val mean = buffer.average().toFloat()
        val variance = buffer.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance).toFloat()
        
        // Filter out outliers (values more than OUTLIER_THRESHOLD standard deviations from mean)
        val filteredValues = buffer.filter { 
            abs(it - mean) <= OUTLIER_THRESHOLD * stdDev 
        }
        
        // If we filtered out too many values, use the original buffer
        val valuesToAverage = if (filteredValues.size >= 2) filteredValues else buffer
        
        // Return the average of the filtered values
        return valuesToAverage.average().toFloat()
    }
    
    private fun updateAllServices(power: Float, cadence: Float, resistance: Float) {
        val currentTime = System.currentTimeMillis()
        val elapsedTimeSeconds = ((currentTime - startTime) / 1000).toInt()
        val deltaTimeSeconds = (currentTime - lastUpdateTime) / 1000f
        
        // Calculate speed from power (using existing utility function)
        val speed = calculateSpeedFromPelotonV1Power(power)
        
        // Update cumulative values
        if (deltaTimeSeconds > 0) {
            totalDistance += speed * deltaTimeSeconds
            totalEnergy += power * deltaTimeSeconds / 3600f / 1000f // Convert to kJ
        }
        
        // Calculate energy rates
        val energyPerHour = power.toInt()
        val energyPerMinute = (power / 60f).toInt()
        
        val ftmsData = FtmsServiceImpl.FtmsData(
            instantaneousPower = power.toInt(),
            instantaneousCadence = cadence,
            instantaneousSpeed = speed,
            totalDistance = totalDistance.toInt(),
            elapsedTime = elapsedTimeSeconds,
            heartRate = 0, // Not available from Peloton data
            resistanceLevel = resistance,
            totalEnergy = totalEnergy.toInt(),
            energyPerHour = energyPerHour,
            energyPerMinute = energyPerMinute
        )
        
        Timber.d("Sending smoothed data: power=${ftmsData.instantaneousPower}W, cadence=${ftmsData.instantaneousCadence}RPM, resistance=${ftmsData.resistanceLevel}")
        
        // Send data to all connected BLE services
        sendAllSensorData(ftmsData)
        
        lastPowerValue = power
        lastUpdateTime = currentTime
    }
    
    private fun resetCounters() {
        totalDistance = 0f
        totalEnergy = 0f
        lastPowerValue = 0f
    }
}
