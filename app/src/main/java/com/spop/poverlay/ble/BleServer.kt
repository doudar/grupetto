package com.spop.poverlay.ble

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.SharedPreferences
import android.os.ParcelUuid
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

class BleServer(

    
    private val context: Context,
    private val deviceName: String = "Grupetto"
) {
    
    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _connectionCount = MutableStateFlow(0)
    val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()
    
    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private val subscribedDevices = mutableMapOf<String, MutableSet<BluetoothGattCharacteristic>>()
    
    // SharedPreferences for storing device identifier
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("ble_Server", Context.MODE_PRIVATE)

    
    }

        companion object {
    private const val NOTIFICATION_ID = 2033
    private const val NOTIFICATION_CHANNEL_ID = "ble_server"
    private const val UPDATE_INTERVAL_MS = 500L // 500ms for responsive data updates
    private const val SMOOTHING_BUFFER_SIZE = 5 // Number of samples to average
    private const val OUTLIER_THRESHOLD = 2.0 // Standard deviations for outlier detection
    private var lastUpdateTime = System.currentTimeMillis()

        // Actions for service control
    const val ACTION_START= "com.spop.poverlay.ble.START"
    const val ACTION_STOP= "com.spop.poverlay.ble.STOP"
    const val ACTION_TOGGLE= "com.spop.poverlay.ble.TOGGLE"
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    }
    
    // GATT characteristics
    private var indoorBikeDataCharacteristic: BluetoothGattCharacteristic? = null
    private var fitnessMachineStatusCharacteristic: BluetoothGattCharacteristic? = null
    private var fitnessMachineControlPointCharacteristic: BluetoothGattCharacteristic? = null
    
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
            // Note: startAdvertising() will be called automatically after services are added successfully
            
            return true
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception when starting BLE server")
            return false
        }
    }
    
    fun stopServer() {
        try {
            stopAdvertising()
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
    
    private fun setupGattServices() {
        // Create FTMS service
        val ftmsService = BluetoothGattService(
            FtmsUuids.FITNESS_MACHINE_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        
        // Fitness Machine Feature characteristic (read-only)
        val fitnessMachineFeatureCharacteristic = BluetoothGattCharacteristic(
            FtmsUuids.FITNESS_MACHINE_FEATURE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        ftmsService.addCharacteristic(fitnessMachineFeatureCharacteristic)
        
        // Indoor Bike Data characteristic (notify)
        indoorBikeDataCharacteristic = BluetoothGattCharacteristic(
            FtmsUuids.INDOOR_BIKE_DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val indoorBikeDataDescriptor = BluetoothGattDescriptor(
            FtmsUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        // Initialize descriptor with default value (notifications disabled)
        indoorBikeDataDescriptor.value = byteArrayOf(0x00, 0x00)
        indoorBikeDataCharacteristic!!.addDescriptor(indoorBikeDataDescriptor)
        ftmsService.addCharacteristic(indoorBikeDataCharacteristic!!)
        
        // Fitness Machine Status characteristic (notify)
        fitnessMachineStatusCharacteristic = BluetoothGattCharacteristic(
            FtmsUuids.FITNESS_MACHINE_STATUS_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val statusDescriptor = BluetoothGattDescriptor(
            FtmsUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        // Initialize descriptor with default value (notifications disabled)
        statusDescriptor.value = byteArrayOf(0x00, 0x00)
        fitnessMachineStatusCharacteristic!!.addDescriptor(statusDescriptor)
        ftmsService.addCharacteristic(fitnessMachineStatusCharacteristic!!)
        
        // Fitness Machine Control Point characteristic (write, indicate)
        fitnessMachineControlPointCharacteristic = BluetoothGattCharacteristic(
            FtmsUuids.FITNESS_MACHINE_CONTROL_POINT_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val controlPointDescriptor = BluetoothGattDescriptor(
            FtmsUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        // Initialize descriptor with default value (indications disabled)
        controlPointDescriptor.value = byteArrayOf(0x00, 0x00)
        fitnessMachineControlPointCharacteristic!!.addDescriptor(controlPointDescriptor)
        ftmsService.addCharacteristic(fitnessMachineControlPointCharacteristic!!)
        
        // Supported Power Range characteristic (read-only)
        val supportedPowerRangeCharacteristic = BluetoothGattCharacteristic(
            FtmsUuids.SUPPORTED_POWER_RANGE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        ftmsService.addCharacteristic(supportedPowerRangeCharacteristic)
        
        // Supported Resistance Level Range characteristic (read-only)
        val supportedResistanceRangeCharacteristic = BluetoothGattCharacteristic(
            FtmsUuids.SUPPORTED_RESISTANCE_LEVEL_RANGE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        ftmsService.addCharacteristic(supportedResistanceRangeCharacteristic)
        
        // Add FTMS service to server first
        gattServer?.addService(ftmsService)
    }
    
    private fun addDeviceInformationService() {
        // Create Device Information Service
        val deviceInfoService = BluetoothGattService(
            FtmsUuids.DEVICE_INFORMATION_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        
        // Manufacturer Name
        val manufacturerNameCharacteristic = BluetoothGattCharacteristic(
            FtmsUuids.MANUFACTURER_NAME_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        deviceInfoService.addCharacteristic(manufacturerNameCharacteristic)
        manufacturerNameCharacteristic.value = deviceName.toByteArray()
        
        // Model Number
        val modelNumberCharacteristic = BluetoothGattCharacteristic(
            FtmsUuids.MODEL_NUMBER_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        deviceInfoService.addCharacteristic(modelNumberCharacteristic)
        modelNumberCharacteristic.value = "Peloton FTMS Bridge".toByteArray()

        // Add device info service
        gattServer?.addService(deviceInfoService)
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
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY) // More responsive for connections
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH) // Higher power for better range
                .setConnectable(true)
                .setTimeout(0) // Advertise indefinitely
                .build()
            
            // Get persistent 4-digit device identifier for manufacturer data
            val deviceId = getDeviceIdentifier()
            val deviceIdBytes = deviceId.toByteArray()
            
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(FtmsUuids.FITNESS_MACHINE_SERVICE_UUID))
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
    
    fun sendIndoorBikeData(ftmsData: FtmsData) {
        indoorBikeDataCharacteristic?.let { characteristic ->
            try {
                val data = ftmsData.toIndoorBikeDataBytes()
                characteristic.value = data
                Timber.d("Setting indoor bike data: ${data.size} bytes [${data.joinToString(",") { "%02x".format(it) }}], power=${ftmsData.instantaneousPower}, cadence=${ftmsData.instantaneousCadence}")
                
                val notificationResult = notifyCharacteristicChanged(characteristic)
                Timber.d("Notification result: $notificationResult")
            } catch (e: Exception) {
                Timber.e(e, "Error setting indoor bike data")
            }
        } ?: run {
            Timber.w("Indoor bike data characteristic is null, cannot send data")
        }
    }
    
    fun sendFitnessMachineStatus(statusData: ByteArray) {
        fitnessMachineStatusCharacteristic?.let { characteristic ->
            characteristic.value = statusData
            notifyCharacteristicChanged(characteristic)
        }
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
                            // Mark device for removal if notification fails consistently
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
                        FtmsUuids.FITNESS_MACHINE_SERVICE_UUID -> {
                            Timber.d("FTMS service added, now adding Device Information Service")
                            addDeviceInformationService()
                        }
                        FtmsUuids.DEVICE_INFORMATION_SERVICE_UUID -> {
                            Timber.d("Device Information Service added, setup complete - starting advertising")
                            startAdvertising()
                        }
                    }
                }
            } else {
                Timber.e("Failed to add service: ${service?.uuid}, status: $status")
                // If FTMS service failed, we can't continue
                if (service?.uuid == FtmsUuids.FITNESS_MACHINE_SERVICE_UUID) {
                    Timber.e("Critical: FTMS service failed to add - stopping server")
                    stopServer()
                } else if (service?.uuid == FtmsUuids.DEVICE_INFORMATION_SERVICE_UUID) {
                    // Device info service is optional, continue with advertising
                    Timber.w("Device Information Service failed to add, but continuing with advertising")
                    startAdvertising()
                }
            }
        }
        
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            device?.let {
                synchronized(this@BleServer) {
                    val statusDescription = when (status) {
                        BluetoothGatt.GATT_SUCCESS -> "SUCCESS"
                        BluetoothGatt.GATT_FAILURE -> "FAILURE"
                        0x13 -> "REMOTE_USER_TERMINATED" // Common disconnection reason
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
            
            when (characteristic?.uuid) {
                FtmsUuids.FITNESS_MACHINE_FEATURE_UUID -> {
                    // Return supported features for indoor bike
                    data = ByteArray(8)
                    val features = FtmsConstants.INDOOR_BIKE_FEATURES
                    val targetSettings = FtmsConstants.INDOOR_BIKE_TARGET_SETTINGS
                    
                    // Pack features (4 bytes) + target settings (4 bytes)
                    data[0] = (features and 0xFF).toByte()
                    data[1] = ((features shr 8) and 0xFF).toByte()
                    data[2] = ((features shr 16) and 0xFF).toByte()
                    data[3] = ((features shr 24) and 0xFF).toByte()
                    data[4] = (targetSettings and 0xFF).toByte()
                    data[5] = ((targetSettings shr 8) and 0xFF).toByte()
                    data[6] = ((targetSettings shr 16) and 0xFF).toByte()
                    data[7] = ((targetSettings shr 24) and 0xFF).toByte()
                }
                FtmsUuids.SUPPORTED_POWER_RANGE_UUID -> {
                    // Min power (0W), Max power (2000W), Min increment (1W)
                    data = byteArrayOf(0, 0, 0xD0.toByte(), 0x07, 1, 0)
                }
                FtmsUuids.SUPPORTED_RESISTANCE_LEVEL_RANGE_UUID -> {
                    // Min resistance (0), Max resistance (100), Min increment (1)
                    data = byteArrayOf(0, 0, 100, 0, 1, 0)
                }
                FtmsUuids.MANUFACTURER_NAME_UUID -> {
                    data = deviceName.toByteArray()
                }
                FtmsUuids.MODEL_NUMBER_UUID -> {
                    data = "Peloton FTMS Bridge".toByteArray()
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
                } else if (descriptor.uuid == FtmsUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                    val characteristic = descriptor.characteristic
                    val deviceAddress = device?.address
                    
                    if (value != null && value.size >= 2 && deviceAddress != null && characteristic != null) {
                        // Validate that this descriptor belongs to one of our characteristics
                        val isValidDescriptor = when (characteristic.uuid) {
                            FtmsUuids.INDOOR_BIKE_DATA_UUID,
                            FtmsUuids.FITNESS_MACHINE_STATUS_UUID,
                            FtmsUuids.FITNESS_MACHINE_CONTROL_POINT_UUID -> true
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
            
            when (characteristic?.uuid) {
                FtmsUuids.FITNESS_MACHINE_CONTROL_POINT_UUID -> {
                    // Handle control point commands
                    if (value != null && value.isNotEmpty()) {
                        handleControlPointCommand(device, value[0])
                    } else {
                        status = BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                    }
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
    
    private fun handleControlPointCommand(device: BluetoothDevice?, opCode: Byte) {
        val response = when (opCode) {
            FtmsConstants.FTMS_CONTROL_REQUEST_CONTROL -> {
                Timber.d("Control requested by ${device?.address}")
                byteArrayOf(0x80.toByte(), opCode, FtmsConstants.FTMS_RESPONSE_SUCCESS)
            }
            FtmsConstants.FTMS_CONTROL_START_OR_RESUME -> {
                Timber.d("Start/Resume requested by ${device?.address}")
                // Send status notification
                sendFitnessMachineStatus(byteArrayOf(FtmsConstants.STATUS_STARTED_BY_EXTERNAL))
                byteArrayOf(0x80.toByte(), opCode, FtmsConstants.FTMS_RESPONSE_SUCCESS)
            }
            FtmsConstants.FTMS_CONTROL_STOP_OR_PAUSE -> {
                Timber.d("Stop/Pause requested by ${device?.address}")
                // Send status notification
                sendFitnessMachineStatus(byteArrayOf(FtmsConstants.STATUS_PAUSED_BY_EXTERNAL))
                byteArrayOf(0x80.toByte(), opCode, FtmsConstants.FTMS_RESPONSE_SUCCESS)
            }
            FtmsConstants.FTMS_CONTROL_RESET -> {
                Timber.d("Reset requested by ${device?.address}")
                // Send status notification
                sendFitnessMachineStatus(byteArrayOf(FtmsConstants.STATUS_RESET))
                byteArrayOf(0x80.toByte(), opCode, FtmsConstants.FTMS_RESPONSE_SUCCESS)
            }
            else -> {
                Timber.d("Unsupported op code: $opCode from ${device?.address}")
                byteArrayOf(0x80.toByte(), opCode, FtmsConstants.FTMS_RESPONSE_OP_CODE_NOT_SUPPORTED)
            }
        }
        
        // Send indication response
        fitnessMachineControlPointCharacteristic?.let { characteristic ->
            characteristic.value = response
            try {
                device?.let {
                    gattServer?.notifyCharacteristicChanged(it, characteristic, true)
                }
            } catch (e: SecurityException) {
                Timber.e(e, "Security exception when sending indication")
            }
        }
    }
}