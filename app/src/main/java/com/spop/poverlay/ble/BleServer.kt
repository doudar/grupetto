package com.spop.poverlay.ble
import android.os.Build
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.spop.poverlay.BuildConfig
import com.spop.poverlay.dircon.DirConGattBridge
import com.spop.poverlay.dircon.DirConServer
import com.spop.poverlay.dircon.toDirConService
import com.spop.poverlay.sensor.heartrate.HeartRateManager
import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.sensor.interfaces.DeviceType
import java.util.LinkedList
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import kotlin.math.abs

interface TimeProvider {
    fun elapsedRealtime(): Long
}

class SystemTimeProvider : TimeProvider {
    override fun elapsedRealtime() = android.os.SystemClock.elapsedRealtime()
}

// Base class for all BLE services
abstract class BaseBleService(val server: BleServer) {
    abstract val service: BluetoothGattService
    abstract fun onSensorDataUpdated(cadence: Float, power: Float, speed: Float, resistance: Float, incline: Float)
    protected val connectedDevices = java.util.concurrent.CopyOnWriteArraySet<BluetoothDevice>()

    fun hasConnectedDevices(): Boolean = connectedDevices.isNotEmpty()

    open fun onConnected(device: BluetoothDevice) {
        connectedDevices.add(device)
    }

    open fun onDisconnected(device: BluetoothDevice) {
        connectedDevices.remove(device)
    }

    open fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
    ) {
        if (responseNeeded) {
            server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }
    }

    @Suppress("DEPRECATION")
    open fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
    ) {
        descriptor.value = value
        if (responseNeeded) {
            server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }
    }

    @Suppress("DEPRECATION")
    open fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
    ) {
        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, descriptor.value)
    }
}

class BleServer(
        private val context: Context,
        private val bluetoothManager: BluetoothManager,
        private val sensorInterface: SensorInterface,
        private val timeProvider: TimeProvider = SystemTimeProvider()
) : BluetoothGattServerCallback(), CoroutineScope {

    override val coroutineContext = SupervisorJob() + Dispatchers.IO
    private var sensorDataJob: Job? = null
    private var watchdogJob: Job? = null
    private var heartRateServiceWatcherJob: Job? = null

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val registeredServices = mutableListOf<BaseBleService>()
    private var dirConServer: DirConServer? = null
    private var dirConTransportEnabled = true
    private var isDirConOnlyStarted = false
    private val servicesToRegister = LinkedList<BaseBleService>()
    private var currentlyRegisteringService: BaseBleService? = null
    private var serviceAddTimeoutJob: Job? = null
    private var gattServerGeneration = 0L

    // Advertising state tracking
    @Volatile private var isAdvertising = false
    private var lastAdvertisingStartTime = 0L
    private var lastAdvertisingFailureCode: Int? = null
    @Volatile private var isServerStarted = false
    private var heartRateServiceEnabled = false
    private var bluetoothSuspended = false
    private var advertisingCallback: AdvertiseCallback? = null
    private var advertisingRestartJob: Job? = null

    private data class PendingNotification(
        val device: BluetoothDevice,
        val characteristic: BluetoothGattCharacteristic,
        val confirm: Boolean,
        val value: ByteArray,
        val coalescible: Boolean
    )

    // All queue operations use the BleServer monitor, including generation-scoped callbacks.
    private val pendingNotifications = mutableListOf<PendingNotification>()
    private var notificationInFlight: PendingNotification? = null
    private var notificationTimeoutJob: Job? = null
    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private val telemetryUuids = setOf(
        FitnessMachineConstants.IndoorBikeDataUUID,
        FitnessMachineConstants.TreadmillDataUUID,
        CyclingPowerConstants.MeasurementUUID,
        CyclingSpeedAndCadenceConstants.MeasurementUUID,
        HeartRateConstants.MeasurementUUID
    )

    // CCCD UUID for checking notification subscriptions
    private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    
    // Track notification subscriptions: Device Address -> Set of Characteristic UUIDs
    private val notificationSubscriptions = mutableMapOf<String, MutableSet<UUID>>()
    
    // Watchdog configuration
    companion object {
        private const val WATCHDOG_INITIAL_DELAY_MS = 60_000L // 1 minute
        private const val WATCHDOG_CHECK_INTERVAL_MS = 120_000L // 2 minutes
        private const val SERVICE_ADD_TIMEOUT_MS = 2_000L
        private const val MAX_PENDING_NOTIFICATIONS = 64
        private const val NOTIFICATION_TIMEOUT_MS = 5_000L
        
        // Standard BLE sensors typically update at 1Hz. 
        // We use a slight offset to avoid aliasing with sensor sampling rates.
        private const val SENSOR_UPDATE_INTERVAL_MS = 1_000L
    }
    
    // Bluetooth adapter state receiver
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                handleBluetoothStateChange(state)
            }
        }
    }
    
    // Bond state receiver for diagnostics
    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = getBluetoothDeviceExtra(intent)
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                val name = try {
                    device?.name
                } catch (e: SecurityException) {
                    "Unknown"
                }
                Timber.d("Bond state changed: ${device?.address} $name $prev -> $state")
            }
        }
    }

    private fun getBluetoothDeviceExtra(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    @Suppress("DEPRECATION")
    private fun characteristicValue(characteristic: BluetoothGattCharacteristic): ByteArray {
        return characteristic.value ?: ByteArray(0)
    }

    private fun toHex(value: ByteArray?): String {
        if (value == null || value.isEmpty()) return "<empty>"
        return value.joinToString(" ") { b -> "%02X".format(b.toInt() and 0xFF) }
    }

    fun logBleDebug(message: String) {
        Timber.d(message)
    }

    fun logBleWarn(message: String, throwable: Throwable? = null) {
        if (throwable == null) Timber.w(message) else Timber.w(throwable, message)
    }

    fun logBleError(message: String, throwable: Throwable? = null) {
        if (throwable == null) Timber.e(message) else Timber.e(throwable, message)
    }

    //ADD OR EDIT SERVICES HERE
    private fun setupServices() {
        servicesToRegister.addAll(baseServices())
        registerNextService()
    }

    private val dirConBridge = object : DirConGattBridge {
        override fun services() = advertisedServices().map { it.service.toDirConService() }

        override fun readCharacteristic(uuid: UUID): ByteArray? = synchronized(this@BleServer) {
            val characteristic = findGattCharacteristic(uuid) ?: return@synchronized null
            characteristicValue(characteristic)
        }

        override fun writeCharacteristic(uuid: UUID, value: ByteArray): Boolean {
            // Control writes require a session identity; the session-aware overload handles them.
            if (uuid == FitnessMachineConstants.ControlPointUUID) return false
            val characteristic = findGattCharacteristic(uuid) ?: return false
            val writable = characteristic.properties and
                (BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            if (!writable) return false

            @Suppress("DEPRECATION")
            characteristic.value = value
            return true
        }

        override fun writeCharacteristic(client: String, uuid: UUID, value: ByteArray): Boolean {
            if (uuid != FitnessMachineConstants.ControlPointUUID) return writeCharacteristic(uuid, value)
            val service = advertisedServices().filterIsInstance<FitnessMachineService>().firstOrNull() ?: return false
            val response = service.handleControl(client, value)
            dirConServer?.notifyCharacteristicChanged(uuid, response, client)
            return true
        }

        override fun disconnected(client: String) { sensorInterface.bikeControl?.disconnect(client) }
    }

    private fun baseServices(): List<BaseBleService> {
        return buildList {
            add(FitnessMachineService(this@BleServer, sensorInterface.deviceType, sensorInterface.bikeControl))
            if (sensorInterface.deviceType != DeviceType.Tread) {
                add(CyclingPowerService(this@BleServer))
                add(CyclingSpeedAndCadenceService(this@BleServer))
            }
            add(DeviceInformationService(this@BleServer))
            // Keep the GATT database stable for the lifetime of the server. Rebuilding the
            // database when a heart-rate sensor connects can race Android's asynchronous
            // service deletion, particularly on Android 11 vendor Bluetooth stacks.
            add(HeartRateService(this@BleServer))
        }
    }

    private fun advertisedServices(): List<BaseBleService> =
        registeredServices.filter {
            heartRateServiceEnabled || it.service.uuid != HeartRateConstants.ServiceUUID
        }

    private fun callbackForGeneration(generation: Long) =
        object : BluetoothGattServerCallback() {
            private fun dispatch(callback: () -> Unit) {
                synchronized(this@BleServer) {
                    if (generation != gattServerGeneration || gattServer == null) {
                        Timber.d("Ignoring callback from stale GATT server generation $generation")
                        return
                    }
                    callback()
                }
            }

            override fun onServiceAdded(status: Int, service: BluetoothGattService) =
                dispatch { this@BleServer.onServiceAdded(status, service) }

            override fun onConnectionStateChange(
                device: BluetoothDevice?,
                status: Int,
                newState: Int
            ) = dispatch { this@BleServer.onConnectionStateChange(device, status, newState) }

            override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) =
                dispatch { this@BleServer.onMtuChanged(device, mtu) }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) =
                dispatch { this@BleServer.onNotificationSent(device, status) }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) = dispatch {
                this@BleServer.onCharacteristicWriteRequest(
                    device,
                    requestId,
                    characteristic,
                    preparedWrite,
                    responseNeeded,
                    offset,
                    value
                )
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) = dispatch {
                this@BleServer.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor
            ) = dispatch {
                this@BleServer.onDescriptorReadRequest(device, requestId, offset, descriptor)
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) = dispatch {
                this@BleServer.onDescriptorWriteRequest(
                    device,
                    requestId,
                    descriptor,
                    preparedWrite,
                    responseNeeded,
                    offset,
                    value
                )
            }
        }

    @Synchronized
    fun start() {
        if (isServerStarted) {
            Timber.d("BLE server already started, ignoring duplicate start()")
            return
        }
        logBleDebug("BLE_DEBUG: App version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        if (isDirConOnlyStarted) {
            stopDirConOnly()
        }
        val bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            Timber.e("Bluetooth adapter is null")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnectPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasConnectPermission) {
                Timber.w("Cannot start BLE server: missing BLUETOOTH_CONNECT permission")
                return
            }
        } else {
            val hasBluetoothPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBluetoothPermission) {
                Timber.w("Cannot start BLE server: missing BLUETOOTH permission")
                return
            }
        }

        bluetoothSuspended = false
        if (!isBluetoothReady()) return

        // setIncludeDeviceName uses the shared adapter name, not our GATT model string.
        // Request the original name before service registration starts advertising.
        try {
            if (bluetoothAdapter.name != "Grupetto") {
                if (bluetoothAdapter.setName("Grupetto")) {
                    Timber.d("Bluetooth adapter name change to Grupetto accepted")
                } else {
                    Timber.w("Bluetooth adapter rejected the Grupetto name; retaining its current name")
                }
            }
        } catch (e: SecurityException) {
            Timber.w(e, "Missing Bluetooth permission to set adapter name")
        } catch (e: Exception) {
            Timber.w(e, "Failed to set Bluetooth adapter name")
        }

        if (!isBluetoothReady()) return
        try {
            advertiser = bluetoothAdapter.bluetoothLeAdvertiser ?: return
        } catch (e: RuntimeException) {
            Timber.w(e, "Bluetooth became unavailable while getting advertiser")
            return
        }

        try {
            val generation = ++gattServerGeneration
            val server = bluetoothManager.openGattServer(context, callbackForGeneration(generation))
            if (server == null) {
                Timber.e("Failed to open GATT server (returned null)")
                return
            }
            gattServer = server
            isServerStarted = true
            
            // Register Bluetooth state change receiver
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            context.registerReceiver(bluetoothStateReceiver, filter)
            Timber.d("Registered Bluetooth state change receiver")
            
            // Register bond state receiver
            val bondFilter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            context.registerReceiver(bondStateReceiver, bondFilter)
            heartRateServiceEnabled = HeartRateManager.connectedDevice.value != null
            setupServices()
            startWatchdog()
            startHeartRateServiceWatcher()
            
        } catch (e: SecurityException) {
            Timber.e(e, "Failed to open GATT server due to missing Bluetooth permission")
            stop() // Clean up partial initialization
        } catch (e: Exception) {
            Timber.e(e, "Failed to start BLE server")
            stop() // Clean up partial initialization
        }
    }

    @Synchronized
    private fun registerNextService() {
        if (servicesToRegister.isEmpty()) {
            currentlyRegisteringService = null
            serviceAddTimeoutJob?.cancel()
            serviceAddTimeoutJob = null
            startAdvertising()
            startDirCon()
            startSensorDataUpdates()
        } else {
            currentlyRegisteringService = servicesToRegister.pop()
            try {
                val added = gattServer?.addService(currentlyRegisteringService!!.service) == true
                if (!added) {
                    currentlyRegisteringService = null
                    registerNextService()
                    return
                }
                serviceAddTimeoutJob?.cancel()
                val generation = gattServerGeneration
                val service = currentlyRegisteringService
                serviceAddTimeoutJob = launch {
                    delay(SERVICE_ADD_TIMEOUT_MS)
                    synchronized(this@BleServer) {
                        if (generation == gattServerGeneration && currentlyRegisteringService === service) {
                            currentlyRegisteringService = null
                            registerNextService()
                        }
                    }
                }
            } catch (e: SecurityException) {
                Timber.e(e, "Failed to add service ${currentlyRegisteringService!!.service.uuid}")
                currentlyRegisteringService = null
                servicesToRegister.clear()
            }
        }
    }

    @Synchronized
    fun stop() {
        sensorInterface.bikeControl?.disconnectTransport("ble:")
        isServerStarted = false
        isDirConOnlyStarted = false
        gattServerGeneration++
        clearNotificationState()
        advertisingRestartJob?.cancel()
        advertisingRestartJob = null

        // Detach first so no callback or concurrent operation can use a server once teardown starts.
        val serverToClose = gattServer
        gattServer = null
        serviceAddTimeoutJob?.cancel()
        serviceAddTimeoutJob = null
        servicesToRegister.clear()
        currentlyRegisteringService = null

        // Closing the detached registration is the highest-priority teardown operation.
        closeGattServer(serverToClose)

        stopWatchdog()
        stopSensorDataUpdates()
        stopHeartRateServiceWatcher()
        stopDirCon()
        stopAdvertising()
            
        // Unregister Bluetooth state change receiver
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
            Timber.d("Unregistered Bluetooth state change receiver")
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, this is normal if stop() called without start()
            Timber.d("Bluetooth state receiver was not registered (normal if not started)")
        }
        try {
            context.unregisterReceiver(bondStateReceiver)
        } catch (e: IllegalArgumentException) {
            Timber.d("Bond state receiver was not registered")
        }

        registeredServices.clear()
        heartRateServiceEnabled = false
            
        // Reset state tracking
        isAdvertising = false
        lastAdvertisingStartTime = 0L
        lastAdvertisingFailureCode = null
            
        // Reset smoothing and CSC state so a restart begins fresh
        smoothedCadence = null
        smoothedPower = null
        smoothedSpeedMph = null
        cscCrankResidual = 0.0
        cscWheelResidual = 0.0
        cscCumulativeWheelRev = 0L
        cscLastWheelEvtTime = 0
        cscCumulativeCrankRev = 0
        cscLastCrankEvtTime = 0
            
        synchronized(notificationSubscriptions) {
            notificationSubscriptions.clear()
        }
    }

    private fun closeGattServer(server: BluetoothGattServer?) {
        if (server == null) return

        // BluetoothGattServer.close() unregisters the server, and Android's GattService
        // deletes all of its services as part of that operation. Calling clearServices()
        // immediately before close() starts the same asynchronous deletion twice. Some
        // Android 11 vendor stacks then throw ConcurrentModificationException in
        // GattService.deleteServices(), leaving orphaned native services behind.
        try {
            server.close()
        } catch (e: SecurityException) {
            Timber.e(e, "Missing bluetooth permissions while closing GATT server")
        } catch (e: Exception) {
            Timber.e(e, "Failed to close GATT server")
        }
    }

    fun setDirConTransportEnabled(enabled: Boolean) {
        dirConTransportEnabled = enabled
        if (enabled) {
            if (isServerStarted) {
                startDirCon()
            } else {
                startDirConOnly()
            }
        } else if (isDirConOnlyStarted) {
            stopDirConOnly()
        } else {
            stopDirCon()
        }
    }

    fun startDirConOnly() {
        if (!dirConTransportEnabled || isServerStarted || isDirConOnlyStarted) {
            return
        }

        heartRateServiceEnabled = HeartRateManager.connectedDevice.value != null
        registeredServices.clear()
        registeredServices.addAll(baseServices())
        startDirCon()
        startSensorDataUpdates()
        isDirConOnlyStarted = true
        Timber.i("DIRCON-only transport started")
    }

    fun stopDirConOnly() {
        if (!isDirConOnlyStarted) {
            stopDirCon()
            return
        }

        stopDirCon()
        stopSensorDataUpdates()
        registeredServices.clear()
        isDirConOnlyStarted = false
        heartRateServiceEnabled = false
        Timber.i("DIRCON-only transport stopped")
    }

    private fun startHeartRateServiceWatcher() {
        heartRateServiceWatcherJob?.cancel()
        heartRateServiceWatcherJob = launch {
            HeartRateManager.connectedDevice
                .collect { connectedDevice ->
                    val shouldEnable = connectedDevice != null
                    if (!isServerStarted || shouldEnable == heartRateServiceEnabled) {
                        return@collect
                    }
                    updateHeartRateServiceRegistration(shouldEnable)
                }
        }
    }

    private fun stopHeartRateServiceWatcher() {
        heartRateServiceWatcherJob?.cancel()
        heartRateServiceWatcherJob = null
    }

    @Synchronized
    private fun updateHeartRateServiceRegistration(enable: Boolean) {
        if (!isServerStarted || gattServer == null) return

        heartRateServiceEnabled = enable
        Timber.i("Heart rate BLE service ${if (enable) "enabled" else "disabled"}")

        if (currentlyRegisteringService != null || servicesToRegister.isNotEmpty()) {
            Timber.d("Deferring heart-rate advertisement update until GATT setup completes")
            return
        }

        // The heart-rate GATT service is registered up front. Only its discoverability is
        // changed here, so connecting/disconnecting a sensor never mutates the live GATT
        // database or races the platform's asynchronous service cleanup.
        stopDirCon()
        stopAdvertising()
        startAdvertising()
        startDirCon()
    }

    private fun startDirCon() {
        if (!dirConTransportEnabled || dirConServer != null || registeredServices.isEmpty()) {
            return
        }

        dirConServer = DirConServer(
            context = context,
            bridge = dirConBridge,
            serialNumberProvider = { serialNumber() }
        ).also { it.start() }
    }

    private fun stopDirCon() {
        dirConServer?.stop()
        dirConServer = null
    }

    @Synchronized
    fun notifyCharacteristicChanged(
            device: BluetoothDevice,
            characteristic: BluetoothGattCharacteristic,
            confirm: Boolean
    ) {
        if (!isServerStarted || gattServer == null || !isBluetoothReady()) return
        // Convention compliance: Only notify if subscribed (handled by tracking CCCD writes)
        val isSubscribed = synchronized(notificationSubscriptions) {
            notificationSubscriptions[device.address]?.contains(characteristic.uuid) == true
        }

        if (!isSubscribed) {
            logBleDebug("BLE notify skipped (not subscribed) dev=${device.address} ch=${characteristic.uuid}")
            return
        }

        val pending = PendingNotification(device, characteristic, confirm,
            characteristicValue(characteristic).copyOf(), !confirm && characteristic.uuid in telemetryUuids)
        val replaced = if (pending.coalescible) pendingNotifications.indexOfFirst {
            it.coalescible && it.device == device && it.characteristic === characteristic
        } else -1
        if (replaced >= 0) {
            pendingNotifications[replaced] = pending
        } else {
            if (pendingNotifications.size >= MAX_PENDING_NOTIFICATIONS) {
                val obsolete = pendingNotifications.indexOfFirst { it.coalescible }
                if (obsolete >= 0) {
                    pendingNotifications.removeAt(obsolete)
                } else {
                    logBleWarn("BLE notification queue full dev=${device.address} ch=${characteristic.uuid}")
                    // A control response cannot be silently discarded while keeping its session alive.
                    if (!pending.coalescible) disconnectNotificationClient(device)
                    return
                }
            }
            pendingNotifications.add(pending)
        }
        sendNextNotification()
    }

    private fun sendNextNotification() {
        if (notificationInFlight != null || !isServerStarted || !isBluetoothReady()) return
        val server = gattServer ?: return
        while (pendingNotifications.isNotEmpty()) {
            val pending = pendingNotifications.removeAt(0)
            if (notificationSubscriptions[pending.device.address]?.contains(pending.characteristic.uuid) != true) continue
            notificationInFlight = pending
            val accepted = try {
                sendGattNotification(server, pending.device, pending.characteristic, pending.confirm, pending.value)
            } catch (e: RuntimeException) {
                logBleWarn("BLE notification submission failed ch=${pending.characteristic.uuid}", e)
                false
            }
            if (!accepted) {
                notificationInFlight = null
                logBleWarn("BLE notification rejected dev=${pending.device.address} ch=${pending.characteristic.uuid}")
                disconnectNotificationClient(pending.device)
                continue
            }
            val generation = gattServerGeneration
            notificationTimeoutJob = launch {
                delay(NOTIFICATION_TIMEOUT_MS)
                synchronized(this@BleServer) {
                    handleNotificationTimeout(generation, pending)
                }
            }
            return
        }
    }

    private fun handleNotificationTimeout(generation: Long, pending: PendingNotification) {
        if (generation != gattServerGeneration || notificationInFlight !== pending) return
        logBleWarn("BLE notification timed out dev=${pending.device.address} ch=${pending.characteristic.uuid}")
        // Callbacks identify only the device, not the packet. Retire the registration before
        // sending again, otherwise a late callback could release a newer packet for that device.
        restartGattAndAdvertising("notification completion timeout")
    }

    @Synchronized
    override fun onNotificationSent(device: BluetoothDevice, status: Int) {
        val pending = notificationInFlight ?: return
        if (pending.device != device) return
        notificationTimeoutJob?.cancel()
        notificationTimeoutJob = null
        notificationInFlight = null
        if (status != BluetoothGatt.GATT_SUCCESS) {
            logBleWarn("BLE notification failed dev=${device.address} ch=${pending.characteristic.uuid} status=$status")
            disconnectNotificationClient(device)
        }
        sendNextNotification()
    }

    private fun disconnectNotificationClient(device: BluetoothDevice) {
        pendingNotifications.removeAll { it.device == device }
        notificationSubscriptions.remove(device.address)
        connectedDevices.remove(device)
        registeredServices.forEach { it.onDisconnected(device) }
        try {
            gattServer?.cancelConnection(device)
        } catch (e: SecurityException) {
            logBleWarn("Missing Bluetooth permission while disconnecting dev=${device.address}", e)
        } catch (e: RuntimeException) {
            logBleWarn("BLE client disconnect failed dev=${device.address}", e)
        }
    }

    private fun clearNotificationState() {
        notificationTimeoutJob?.cancel()
        notificationTimeoutJob = null
        notificationInFlight = null
        pendingNotifications.clear()
        notificationSubscriptions.clear()
        connectedDevices.toList().forEach { device ->
            registeredServices.forEach { it.onDisconnected(device) }
        }
        connectedDevices.clear()
    }

    fun notifyDirConCharacteristicChanged(characteristic: BluetoothGattCharacteristic) {
        dirConServer?.notifyCharacteristicChanged(
            characteristic.uuid,
            characteristicValue(characteristic)
        )
    }

    fun sendResponse(
            device: BluetoothDevice?,
            requestId: Int,
            status: Int,
            offset: Int,
            value: ByteArray?
    ) {
        try {
            gattServer?.sendResponse(device, requestId, status, offset, value)
        } catch (e: SecurityException) {
            Timber.e(e, "Missing bluetooth permissions")
        }
    }
    
    @Synchronized
    private fun handleBluetoothStateChange(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                bluetoothSuspended = true
                sensorInterface.bikeControl?.disconnectTransport("ble:")
                advertisingRestartJob?.cancel()
                advertisingRestartJob = null
                stopAdvertising()
                clearNotificationState()
                // Retire the registration but keep the receiver and intent to run on STATE_ON.
                gattServerGeneration++
                val serverToClose = gattServer
                gattServer = null
                serviceAddTimeoutJob?.cancel()
                serviceAddTimeoutJob = null
                currentlyRegisteringService = null
                servicesToRegister.clear()
                closeGattServer(serverToClose)
                Timber.i("Bluetooth shutting down; BLE work suspended")
            }
            BluetoothAdapter.STATE_ON -> {
                bluetoothSuspended = false
                Timber.i("Bluetooth turned on, attempting to restart advertising")
                if (isServerStarted) {
                    restartGattAndAdvertising("Bluetooth turned on")
                }
            }
            BluetoothAdapter.STATE_TURNING_ON -> {
                Timber.d("Bluetooth turning on")
            }
        }
    }
    
    private fun isBluetoothReady(): Boolean = try {
        !bluetoothSuspended && bluetoothManager.adapter?.state == BluetoothAdapter.STATE_ON
    } catch (e: SecurityException) {
        Timber.w(e, "Cannot access Bluetooth adapter state")
        false
    }

    private fun startWatchdog() {
        stopWatchdog()
        watchdogJob = launch {
            // Wait a bit before starting watchdog to allow initial setup
            delay(WATCHDOG_INITIAL_DELAY_MS)
            
            while (isActive && isServerStarted) {
                try {
                    checkAndRestartAdvertising()
                } catch (e: Exception) {
                    Timber.e(e, "Error in advertising watchdog")
                }
                // Check every 2 minutes
                delay(WATCHDOG_CHECK_INTERVAL_MS)
            }
        }
        Timber.d("Started advertising watchdog")
    }
    
    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        Timber.d("Stopped advertising watchdog")
    }
    
    private fun hasConnectedDevices(): Boolean {
        return registeredServices.any { it.hasConnectedDevices() }
    }
    
    @Synchronized
    private fun checkAndRestartAdvertising() {
        val bluetoothAdapter = bluetoothManager.adapter
        
        if (bluetoothAdapter == null) {
            Timber.w("Watchdog: Bluetooth adapter is null")
            return
        }
        
        if (!isServerStarted || !isBluetoothReady()) {
            Timber.d("Watchdog: Bluetooth is disabled; skipping auto-enable to avoid interfering with other apps")
            isAdvertising = false
            return
        }

        if (gattServer == null) {
            restartGattAndAdvertising("GATT registration unavailable")
            return
        }
        
        // Check if we should be advertising but aren't
        if (!isAdvertising && registeredServices.isNotEmpty()) {
            // With multi-client support, advertising should be active even with connected devices
            // Only skip if we very recently had a connection state change (give it time to restart)
            val timeSinceLastStart = System.currentTimeMillis() - lastAdvertisingStartTime
            
            if (hasConnectedDevices() && timeSinceLastStart < 5000) {
                // Recent connection, advertising is being restarted automatically
                Timber.d("Watchdog: Recent connection detected, waiting for automatic advertising restart")
                return
            }
            
            val reason = if (lastAdvertisingStartTime == 0L) {
                "never started"
            } else if (lastAdvertisingFailureCode != null) {
                "last failed with code $lastAdvertisingFailureCode"
            } else if (hasConnectedDevices()) {
                "stopped despite connected clients (${timeSinceLastStart / 1000}s ago)"
            } else {
                "stopped (${timeSinceLastStart / 1000}s ago)"
            }
            
            Timber.w("Watchdog: Advertising is not active, reason: $reason. Restarting...")
            
            // Retrying advertising does not require rebuilding the GATT database. Rebuilding
            // here can repeatedly exercise buggy vendor teardown paths and orphan services.
            Timber.i("Retrying advertising without rebuilding the GATT server")
            startAdvertising()
        } else if (isAdvertising) {
            val timeSinceStart = System.currentTimeMillis() - lastAdvertisingStartTime
            Timber.d("Watchdog: Advertising active for ${timeSinceStart / 1000}s")
        }
    }
    
    @Synchronized
    private fun restartGattAndAdvertising(reason: String) {
        if (!isServerStarted) {
            Timber.d("Ignoring GATT restart after server was stopped: $reason")
            return
        }

        Timber.i("Restarting GATT and advertising: $reason")
        sensorInterface.bikeControl?.disconnectTransport("ble:")
        
        try {
            // Stop current advertising
            stopAdvertising()
            clearNotificationState()
            stopSensorDataUpdates()
            
            // Close and reopen GATT server
            val serverToClose = gattServer
            gattServer = null
            gattServerGeneration++
            serviceAddTimeoutJob?.cancel()
            serviceAddTimeoutJob = null
            servicesToRegister.clear()
            currentlyRegisteringService = null
            closeGattServer(serverToClose)
            
            val bluetoothAdapter = bluetoothManager.adapter
            if (bluetoothAdapter == null || !isBluetoothReady()) {
                Timber.e("Cannot restart: Bluetooth not available")
                return
            }
            
            advertiser = bluetoothAdapter.bluetoothLeAdvertiser
            if (advertiser == null) {
                Timber.e("Cannot restart: Failed to get advertiser")
                return
            }
            
            val generation = ++gattServerGeneration
            val replacement = bluetoothManager.openGattServer(
                context,
                callbackForGeneration(generation)
            )
            if (replacement == null) {
                Timber.e("Cannot restart: Failed to open GATT server")
                return
            }
            gattServer = replacement
            
            // Re-register all services
            registeredServices.clear()
            servicesToRegister.addAll(baseServices())
            
            registerNextService()
            
        } catch (e: SecurityException) {
            Timber.e(e, "Missing bluetooth permissions during restart")
        } catch (e: Exception) {
            Timber.e(e, "Error during GATT and advertising restart")
        }
    }

    @Synchronized
    private fun startAdvertising() {
        if (!isServerStarted || gattServer == null || !isBluetoothReady() || advertisingCallback != null) {
            return
        }
        val serviceUuids = advertisedServices().map { ParcelUuid(it.service.uuid) }
        if (serviceUuids.isEmpty()) {
            return
        }
        try {
            val settings =
                    AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                            .setConnectable(true)
                            .build()

            // Primary advertising data: keep it lean (just service UUIDs) to avoid 31-byte limit
            val advDataBuilder = AdvertiseData.Builder()
            for (uuid in serviceUuids) {
                advDataBuilder.addServiceUuid(uuid)
            }

            logBleDebug("BLE advertising UUIDs: ${serviceUuids.joinToString { it.uuid.toString() }}")

            // Scan response: include device name and manufacturer specific data
            val scanResponseBuilder = AdvertiseData.Builder()
                .setIncludeDeviceName(true)

            // Manufacturer data (use a test/manufacturer ID; replace with your assigned company ID if available)
            val manufacturerId = 0xFFFF // 16-bit Company Identifier (testing)
            val sn = serialNumber()
            // Keep payload concise to fit scan response size constraints
            val manufacturerData = "GRUP-$sn".toByteArray(Charsets.UTF_8)
            scanResponseBuilder.addManufacturerData(manufacturerId, manufacturerData)
            logBleDebug("BLE scan response manufacturerId=0x${manufacturerId.toString(16)} data=${String(manufacturerData)}")

            val activeAdvertiser = advertiser ?: return
            val callback = newAdvertisingCallback(gattServerGeneration)
            advertisingCallback = callback
            activeAdvertiser.startAdvertising(
                settings,
                advDataBuilder.build(),
                scanResponseBuilder.build(),
                callback
            )
        } catch (e: SecurityException) {
            advertisingCallback = null
            isAdvertising = false
            Timber.e(e, "Missing bluetooth permissions")
        } catch (e: IllegalArgumentException) {
            advertisingCallback = null
            isAdvertising = false
            // Thrown if advertise data exceeds the allowed size
            Timber.e(e, "Invalid advertise data: %s", e.message)
        } catch (e: IllegalStateException) {
            advertisingCallback = null
            isAdvertising = false
            Timber.w(e, "Bluetooth became unavailable while starting advertising")
        }
    }

    @Synchronized
    private fun stopAdvertising() {
        val callback = advertisingCallback
        advertisingCallback = null
        isAdvertising = false
        try {
            if (callback != null && isBluetoothReady()) advertiser?.stopAdvertising(callback)
            Timber.d("Stopped advertising")
        } catch (e: SecurityException) {
            Timber.e(e, "Missing bluetooth permissions")
        } catch (e: IllegalStateException) {
            Timber.w(e, "Bluetooth became unavailable while stopping advertising")
        }
    }

    private fun newAdvertisingCallback(generation: Long): AdvertiseCallback =
            object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    synchronized(this@BleServer) {
                        if (advertisingCallback !== this || generation != gattServerGeneration ||
                            !isServerStarted || !isBluetoothReady()) return
                        isAdvertising = true
                        lastAdvertisingStartTime = System.currentTimeMillis()
                        lastAdvertisingFailureCode = null
                        logBleDebug("BLE advertising started")
                    }
                }

                override fun onStartFailure(errorCode: Int) {
                    synchronized(this@BleServer) {
                        if (advertisingCallback !== this || generation != gattServerGeneration ||
                            !isServerStarted || !isBluetoothReady()) return
                        advertisingCallback = null
                        isAdvertising = false
                        lastAdvertisingFailureCode = errorCode
                        val errorMessage = when (errorCode) {
                            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                            else -> "Unknown error"
                        }
                        logBleError("BLE advertising failed: $errorCode ($errorMessage)")
                        if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) {
                            advertisingCallback = this
                            isAdvertising = true
                        }
                    }
                }
            }

    @Synchronized
    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
        serviceAddTimeoutJob?.cancel()
        serviceAddTimeoutJob = null
        if (currentlyRegisteringService?.service?.uuid != service.uuid) {
            Timber.e(
                    "Mismatched service added callback! Expected ${currentlyRegisteringService?.service?.uuid}, got ${service.uuid}"
            )
            servicesToRegister.clear()
            currentlyRegisteringService = null
            return
        }

        if (status == BluetoothGatt.GATT_SUCCESS) {
            Timber.d("Service added ${service.uuid}")
            registeredServices.add(currentlyRegisteringService!!)
        } else {
            Timber.e("Failed to add service ${service.uuid}, status: $status")
        }
        registerNextService()
    }

    @Synchronized
    override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            device?.let {
                connectedDevices.add(device)
                registeredServices.forEach { it.onConnected(device) }
                logBleDebug("BLE connected: ${device.address}")
                
                // Restart advertising to allow additional clients to connect (support multiple connections)
                if (!isAdvertising && isServerStarted) {
                    Timber.i("Device connected, restarting advertising to allow more clients")
                    scheduleAdvertisingRestart()
                }
            }
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            // Clean up subscriptions
            synchronized(notificationSubscriptions) {
                notificationSubscriptions.remove(device?.address)
            }
            device?.let {
                connectedDevices.remove(device)
                pendingNotifications.removeAll { it.device == device }
                // Keep outstanding sends until callback/timeout, even on disconnect.
                registeredServices.forEach { it.onDisconnected(device) }
                logBleDebug("BLE disconnected: ${device.address}")
                
                // Restart advertising if no devices are connected anymore
                if (!hasConnectedDevices() && !isAdvertising && isServerStarted) {
                    Timber.i("Last device disconnected, restarting advertising")
                    scheduleAdvertisingRestart()
                }
            }
        }
    }

    private fun scheduleAdvertisingRestart() {
        advertisingRestartJob?.cancel()
        val generation = gattServerGeneration
        advertisingRestartJob = launch {
            delay(500)
            synchronized(this@BleServer) {
                if (generation == gattServerGeneration) startAdvertising()
            }
        }
    }

    override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
        Timber.d("onMtuChanged: $mtu for ${device?.address}")
    }

    private fun findServiceForCharacteristic(uuid: UUID?): BaseBleService? {
        return registeredServices.firstOrNull { it.service.uuid == uuid }
    }

    private fun findGattCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        return registeredServices
            .asSequence()
            .flatMap { it.service.characteristics.asSequence() }
            .firstOrNull { it.uuid == uuid }
    }

    override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
    ) {
        logBleDebug(
            "BLE write req dev=${device.address} svc=${characteristic.service.uuid} ch=${characteristic.uuid} prepared=$preparedWrite resp=$responseNeeded offset=$offset value=${toHex(value)}"
        )
        findServiceForCharacteristic(characteristic.service.uuid)
                ?.onCharacteristicWriteRequest(
                        device,
                        requestId,
                        characteristic,
                        preparedWrite,
                        responseNeeded,
                        offset,
                        value
                )
    }

    override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
    ) {
        val service = findServiceForCharacteristic(characteristic.service.uuid)
        if (service == null) {
            logBleWarn("BLE read req failed (service missing) dev=${device.address} svc=${characteristic.service.uuid} ch=${characteristic.uuid}")
            sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            return
        }
        val value = characteristicValue(characteristic)
        logBleDebug("BLE read req dev=${device.address} svc=${characteristic.service.uuid} ch=${characteristic.uuid} offset=$offset value=${toHex(value)}")
        sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
    }

    override fun onDescriptorReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        descriptor: BluetoothGattDescriptor
    ) {
        findServiceForCharacteristic(descriptor.characteristic.service.uuid)
            ?.onDescriptorReadRequest(device, requestId, offset, descriptor)
    }

    @Synchronized
    override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
    ) {
        logBleDebug(
            "BLE desc write req dev=${device.address} svc=${descriptor.characteristic.service.uuid} ch=${descriptor.characteristic.uuid} desc=${descriptor.uuid} value=${toHex(value)}"
        )
        // Convention compliance: Track CCCD state
        if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG && value != null) {
            // CCCD is a little-endian uint16: 0 disables, 1 notifies, 2 indicates.
            val configuration = if (value.size == 2 && value[1] == 0.toByte()) value[0].toInt() else -1
            val isEnabled = configuration == 1 || configuration == 2
            val isDisabled = configuration == 0

            synchronized(notificationSubscriptions) {
                val deviceAddress = device.address
                if (isEnabled) {
                    val uuidSet = notificationSubscriptions.getOrPut(deviceAddress) { mutableSetOf() }
                    uuidSet.add(descriptor.characteristic.uuid)
                    logBleDebug("BLE CCCD enabled ch=${descriptor.characteristic.uuid} dev=$deviceAddress")
                } else if (isDisabled) {
                    pendingNotifications.removeAll {
                        it.device == device && it.characteristic.uuid == descriptor.characteristic.uuid
                    }
                    notificationSubscriptions[deviceAddress]?.remove(descriptor.characteristic.uuid)
                    if (notificationSubscriptions[deviceAddress]?.isEmpty() == true) {
                        notificationSubscriptions.remove(deviceAddress)
                    }
                    logBleDebug("BLE CCCD disabled ch=${descriptor.characteristic.uuid} dev=$deviceAddress")
                }
            }
        }

        findServiceForCharacteristic(descriptor.characteristic.service.uuid)
                ?.onDescriptorWriteRequest(
                        device,
                        requestId,
                        descriptor,
                        preparedWrite,
                        responseNeeded,
                        offset,
                        value
                )
    }

    // Data class to hold four lists of sensor values
    private data class SensorData(
            val cadence: List<Float>,
            val power: List<Float>,
            val speed: List<Float>,
            val resistance: List<Float>,
            val incline: List<Float>
    )

    private fun startSensorDataUpdates() {
        sensorDataJob?.cancel()
        sensorDataJob = launch {
            sensorInterface.bikeControl?.takeIf { it.supported }?.let { control ->
                launch {
                    control.state.collect { state ->
                        synchronized(this@BleServer) {
                            if (!isActive) return@collect
                            registeredServices.filterIsInstance<FitnessMachineService>()
                                .forEach { it.onControlStateChanged(state) }
                        }
                    }
                }
            }
            val mutex = Mutex()
            val cadenceBuffer = mutableListOf<Float>()
            val powerBuffer = mutableListOf<Float>()
            val speedBuffer = mutableListOf<Float>()
            val resistanceBuffer = mutableListOf<Float>()
            val inclineBuffer = mutableListOf<Float>()

            launch {
                combine(
                                sensorInterface.cadence,
                                sensorInterface.power,
                                sensorInterface.speed,
                                sensorInterface.resistance,
                                sensorInterface.incline
                        ) { cadence, power, speed, resistance, incline ->
                            mutex.withLock {
                                cadenceBuffer.add(cadence)
                                powerBuffer.add(power)
                                speedBuffer.add(speed)
                                resistanceBuffer.add(resistance)
                                inclineBuffer.add(incline)
                            }
                        }
                        .collect()
            }

            launch {
                while (isActive) {
                    // Use a standard interval (~1Hz) to be consistent with typical clients.
                    // The slight offset (e.g. 1003ms) prevents phase-locking/aliasing with incoming sensor data loops.
                    delay(SENSOR_UPDATE_INTERVAL_MS)
                    
                    val buffers =
                            mutex.withLock {
                                if (cadenceBuffer.isEmpty()) null
                                else
                                        SensorData(
                                                cadenceBuffer.toList(),
                                                powerBuffer.toList(),
                                                speedBuffer.toList(),
                                                resistanceBuffer.toList(),
                                                inclineBuffer.toList()
                                        )
                                                .also {
                                                    cadenceBuffer.clear()
                                                    powerBuffer.clear()
                                                    speedBuffer.clear()
                                                    resistanceBuffer.clear()
                                                    inclineBuffer.clear()
                                                }
                            }
                    buffers?.let { data ->
                        // Robust outlier filtering per metric, then light exponential smoothing
                        val rCadence = robustAverage(data.cadence)
                        val rPower = robustAverage(data.power)
                        val rSpeedMph = robustAverage(data.speed) // mph

                        val sCadence = smoothCadence(rCadence)
                        val sPower = smoothPower(rPower)
                        val sSpeedMph = smoothSpeed(rSpeedMph)
                        // Resistance is a discrete setting, already spike-filtered by the
                        // sensor interface. Averaging/smoothing invents intermediate levels
                        // (e.g. 99.7 for a received 100) and delays both increases and decreases.
                        val resistance = data.resistance.lastOrNull { it.isFinite() } ?: 0f
                        val incline = data.incline.lastOrNull { it.isFinite() } ?: 0f

                        // Convert mph -> km/h for wheel calculations
                        val sSpeedKmh = sSpeedMph * 1.60934f
                        // Update shared CSC counters using km/h for wheel and RPM for crank
                        updateWheelAndCrankRev(sSpeedKmh, sCadence)
                        // Speed remains mph; resistance is the latest sensor setting.
                        synchronized(this@BleServer) {
                            if (isActive) registeredServices.forEach {
                                it.onSensorDataUpdated(sCadence, sPower, sSpeedMph, resistance, incline)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stopSensorDataUpdates() {
        sensorDataJob?.cancel()
    }

    // --- Robust filtering and smoothing helpers ---
    // Median of a non-empty Float list
    private fun medianOf(values: List<Float>): Float {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else ((sorted[n / 2 - 1] + sorted[n / 2]) / 2f)
    }

    // Compute a robust average by removing outliers using a MAD-based threshold
    private fun robustAverage(values: List<Float>): Float {
        val finite = values.filter { it.isFinite() }
        if (finite.isEmpty()) return 0f
        if (finite.size < 3) return finite.average().toFloat()

        val med = medianOf(finite)
        val deviations = finite.map { abs(it - med) }
        val mad = medianOf(deviations)

        // If MAD is ~0 (stable), fall back to light trimming of extremes
        if (mad == 0f) {
            val sorted = finite.sorted()
            return when {
                sorted.size >= 5 -> sorted.subList(1, sorted.size - 1).average().toFloat()
                else -> sorted.average().toFloat()
            }
        }

        // 3-sigma rule on MAD with consistency constant
        val threshold = 3.0f * 1.4826f * mad
        val inliers = finite.filter { abs(it - med) <= threshold }
        return if (inliers.isNotEmpty()) inliers.average().toFloat() else med
    }

    // Exponential smoothing states and helpers
    private var smoothedCadence: Float? = null
    private var smoothedPower: Float? = null
    private var smoothedSpeedMph: Float? = null

    private fun smooth(prev: Float?, value: Float, alpha: Float): Float =
        if (prev == null) value else (alpha * value + (1f - alpha) * prev)

    // With 1Hz updates, we increase alpha (reduce smoothing) to maintain responsiveness.
    // The previous 1-second buffering already provides significant noise reduction.
    private fun smoothCadence(v: Float, alpha: Float = 0.7f): Float {
        smoothedCadence = smooth(smoothedCadence, v, alpha)
        return smoothedCadence!!
    }

    private fun smoothPower(v: Float, alpha: Float = 0.7f): Float {
        smoothedPower = smooth(smoothedPower, v, alpha)
        return smoothedPower!!
    }

    private fun smoothSpeed(vMph: Float, alpha: Float = 0.7f): Float {
        smoothedSpeedMph = smooth(smoothedSpeedMph, vMph, alpha)
        return smoothedSpeedMph!!
    }

    // CSC shared state (used by multiple services)
    // Wheel values: cumulative (uint32) and last event time (uint16, 1/1024s). Only updated if
    // speed provided.
    var cscCumulativeWheelRev: Long = 0L
        private set
    var cscLastWheelEvtTime: Int = 0 // uint16 ticks (wrap at 65536)
        private set
    // Crank values: cumulative (uint16) and last event time (uint16, 1/1024s)
    var cscCumulativeCrankRev: Int = 0
        private set
    var cscLastCrankEvtTime: Int = 0 // uint16 ticks (wrap at 65536)
        private set

    // Update CSC wheel and crank revolutions using the C++ algorithm
    // speedKmh: if provided, wheel data will be updated; cadenceRpm always used for crank
    private var cscLastUpdateMs: Long = timeProvider.elapsedRealtime()
    private var cscCrankResidual: Double = 0.0
    private var cscWheelResidual: Double = 0.0
    fun updateWheelAndCrankRev(speedKmh: Float?, cadenceRpm: Float) {
        val now = timeProvider.elapsedRealtime()
        val deltaMs = (now - cscLastUpdateMs).coerceAtLeast(0)
        cscLastUpdateMs = now

        // Wheel
        val wheelSizeMeters = 2.127f // 700c x 28, typical
        // speedKmh must be in km/h; convert to m/s for wheel RPM calculation
        val speedMps = speedKmh?.let { it / 3.6f }
        if (speedMps != null && speedMps > 0f) {

            val wheelRpm = (speedMps / wheelSizeMeters) * 60f
            if (wheelRpm > 0f) {
                val wheelRevPeriodTicks = (60.0 * 1024.0) / wheelRpm
                val wheelRevsDelta = wheelRpm * (deltaMs / 60000.0)
                cscWheelResidual += wheelRevsDelta
                    val toAdd = kotlin.math.floor(cscWheelResidual).toInt()
                    if (toAdd > 0) {
                        cscWheelResidual -= toAdd
                        cscCumulativeWheelRev = (cscCumulativeWheelRev + toAdd) and 0xFFFF_FFFFL
                        val ticksAdd = (wheelRevPeriodTicks * toAdd).toInt().coerceAtLeast(1)
                        cscLastWheelEvtTime = (cscLastWheelEvtTime + ticksAdd) and 0xFFFF
                    }
                }
            }

        // Crank
        if (cadenceRpm > 0f) {
            val crankRevPeriodTicks = (60.0 * 1024.0) / cadenceRpm
            val crankRevsDelta = cadenceRpm * (deltaMs / 60000.0)
            cscCrankResidual += crankRevsDelta
            val toAdd = kotlin.math.floor(cscCrankResidual).toInt()
            if (toAdd > 0) {
                cscCrankResidual -= toAdd
                cscCumulativeCrankRev = (cscCumulativeCrankRev + toAdd) and 0xFFFF
                val ticksAdd = (crankRevPeriodTicks * toAdd).toInt().coerceAtLeast(1)
                cscLastCrankEvtTime = (cscLastCrankEvtTime + ticksAdd) and 0xFFFF
            }
        }
    }


    //Function checks userprefs to see if serial number has been generated on previous ones, and if not, It creates one.
    fun serialNumber(): String {
        // Use the same shared preferences as ConfigurationRepository
        val prefs = context.getSharedPreferences(
            com.spop.poverlay.ConfigurationRepository.SharedPrefsName,
            Context.MODE_PRIVATE
        )
        val key = com.spop.poverlay.ConfigurationRepository.Preferences.SerialNumber.key
        var existing = prefs.getString(key, null)
        if (existing.isNullOrEmpty()) {
            val value = kotlin.random.Random.nextInt(0x10000)
            existing = value.toString(16).padStart(4, '0').uppercase()
            prefs.edit { putString(key, existing) }
        }
        return existing
    }
    
}
