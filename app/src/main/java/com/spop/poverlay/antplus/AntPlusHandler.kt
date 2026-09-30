package com.spop.poverlay.antplus

import android.content.ComponentName
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.dsi.ant.AntService
import com.dsi.ant.channel.AntChannel
import com.dsi.ant.channel.AntChannelProvider
import com.dsi.ant.channel.AntCommandFailedException
import com.dsi.ant.channel.IAntChannelEventHandler
import com.dsi.ant.channel.PredefinedNetwork
import com.dsi.ant.message.ChannelId
import com.dsi.ant.message.ChannelType
import com.dsi.ant.message.EventCode
import com.dsi.ant.message.fromant.AcknowledgedDataMessage
import com.dsi.ant.message.fromant.ChannelEventMessage
import com.dsi.ant.message.fromant.MessageFromAntType
import com.dsi.ant.message.ipc.AntMessageParcel
import com.spop.poverlay.BuildConfig

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Handles ANT+ broadcasting using the low-level ANT channel API from `android_antlib`.
 *
 * `antpluginlib`'s PCC classes are receivers/clients for existing ANT+ devices; to advertise this
 * app as a sensor we need to bind to `AntService`, acquire a master channel, and publish the power
 * page payload ourselves.
 */
class AntPlusHandler(
    private val context: Context,
    private val deviceName: String,
    private val scope: CoroutineScope
) : ServiceConnection {

    companion object {
        // Discovery mode forces a fixed mock power/cadence broadcast to make the channel easier
        // to find during initial pairing. Set to FALSE for production (broadcasts real sensor data).
        private const val DiscoveryModeEnabled = false
        private const val DiscoveryModePowerWatts = 180
        private const val DiscoveryModeCadenceRpm = 85

        private const val PayloadDebugEveryNMessages = 20
        private const val StrictVirtualPowerParityMode = false
    }


    private var antService: AntService? = null
    private var antChannelProvider: AntChannelProvider? = null
    private var antChannel: AntChannel? = null  // Power meter channel
    private var cscChannel: AntChannel? = null  // Speed/Cadence channel
    private var hrmChannel: AntChannel? = null  // Heart Rate Monitor channel
    private var isServiceBound = false
    private var isChannelOpen = false
    private var isCscChannelOpen = false
    private var isHrmChannelOpen = false
    private var currentTransmissionType: Int = -1

    @Volatile
    private var latestPowerWatts: Int = 0

    @Volatile
    private var latestCadenceRpm: Int = 0

    @Volatile
    private var latestSpeedKmh: Float = 0.0f

    @Volatile
    private var latestHeartRateBpm: Int = 0

    private var eventCount: Int = 0
    private var cscCumulativeWheelRevolutions: Int = 0 // uint16
    private var cscCumulativeCrankRevolutions: Int = 0 // uint16 on air
    private var accumulatedPower: Int = 0
    private var cscLastWheelEventTime: Int = 0 // uint16, 1/1024s ticks
    private var cscLastCrankEventTime: Int = 0 // uint16, 1/1024s ticks
    private var cscWheelResidual: Double = 0.0
    private var cscCrankResidual: Double = 0.0
    private var cscLastUpdateMs: Long = SystemClock.elapsedRealtime()
    private var cscMessageCounter: Int = 0
    private var hrmMessageCounter: Int = 0
    private var hrmBeatCount: Int = 0
    private var hrmLastBeatTime: Int = 0
    private var hrmBeatResidual: Double = 0.0
    private var hrmLastUpdateMs: Long = SystemClock.elapsedRealtime()
    private var messageCounter: Int = 0
    private var hasSentInitializationPage: Boolean = false
    private var lastBroadcastPowerWatts: Int = 0
    private var lastBroadcastCadenceRpm: Int = 0
    private var lastBroadcastSpeedKmh: Float = 0.0f
    private var isChannelProviderReceiverRegistered = false

    // Reused page buffers - all page builders fully overwrite every byte before returning, and
    // all callers (pushCurrentPayload/pushCscPayload/pushHrmPayload) are @Synchronized on this
    // instance, so a single buffer per channel is safe to reuse across TX events (~4Hz each).
    private val powerPayloadBuffer = ByteArray(AntPlusConstants.ANT_MESSAGE_SIZE)
    private val cscPayloadBuffer = ByteArray(AntPlusConstants.ANT_MESSAGE_SIZE)
    private val hrmPayloadBuffer = ByteArray(AntPlusConstants.ANT_MESSAGE_SIZE)
    private var isChannelSetupInProgress = false
    @Volatile private var closed = false

    private val channelProviderStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AntChannelProvider.ACTION_CHANNEL_PROVIDER_STATE_CHANGED) {
                return
            }

            val numChannels = intent.getIntExtra(AntChannelProvider.NUM_CHANNELS_AVAILABLE, -1)
            val legacyInUse = intent.getBooleanExtra(AntChannelProvider.LEGACY_INTERFACE_IN_USE, false)

            // Channel setup can retry with delays; run off the (likely main) receiver thread.
            scope.launch {
                if (numChannels >= 0) {
                    handleProviderState(numChannels, legacyInUse, "broadcast")
                } else {
                    syncProviderStateFromProvider("broadcast-fallback")
                }
            }
        }
    }

    private val channelEventHandler = object : IAntChannelEventHandler {
        override fun onChannelDeath() {
            logWarn("ANT_DEBUG: ANT power channel died")
            antChannel = null
            isChannelOpen = false
        }

        override fun onReceiveMessage(messageType: MessageFromAntType, messageParcel: AntMessageParcel) {
            when (messageType) {
                MessageFromAntType.ACKNOWLEDGED_DATA -> {
                    handleAcknowledgedData(messageParcel)
                }

                MessageFromAntType.CHANNEL_EVENT -> {
                    val eventCode = ChannelEventMessage(messageParcel).eventCode
                    when (eventCode) {
                        EventCode.TX -> {
                            pushCurrentPayload()
                        }

                        EventCode.CHANNEL_CLOSED -> {
                            logDebug("ANT_DEBUG: ANT power channel closed")
                            isChannelOpen = false
                        }

                        EventCode.CHANNEL_COLLISION -> {
                            logDebug("ANT_DEBUG: ANT power channel collision")
                        }

                        else -> {
                            logDebug("ANT_DEBUG: Power channel event=$eventCode")
                        }
                    }
                }

                else -> Unit
            }
        }
    }

    private val cscChannelEventHandler = object : IAntChannelEventHandler {
        override fun onChannelDeath() {
            logWarn("ANT_DEBUG: ANT CSC channel died")
            cscChannel = null
            isCscChannelOpen = false
        }

        override fun onReceiveMessage(messageType: MessageFromAntType, messageParcel: AntMessageParcel) {
            when (messageType) {
                MessageFromAntType.CHANNEL_EVENT -> {
                    val eventCode = ChannelEventMessage(messageParcel).eventCode
                    when (eventCode) {
                        EventCode.TX -> {
                            pushCscPayload()
                        }

                        EventCode.CHANNEL_CLOSED -> {
                            logDebug("ANT_DEBUG: ANT CSC channel closed")
                            isCscChannelOpen = false
                        }

                        else -> {
                            // Suppress most CSC channel events to reduce log noise
                        }
                    }
                }

                else -> Unit
            }
        }
    }

    private val hrmChannelEventHandler = object : IAntChannelEventHandler {
        override fun onChannelDeath() {
            logWarn("ANT_DEBUG: ANT HRM channel died")
            hrmChannel = null
            isHrmChannelOpen = false
        }

        override fun onReceiveMessage(messageType: MessageFromAntType, messageParcel: AntMessageParcel) {
            when (messageType) {
                MessageFromAntType.CHANNEL_EVENT -> {
                    val eventCode = ChannelEventMessage(messageParcel).eventCode
                    when (eventCode) {
                        EventCode.TX -> pushHrmPayload()
                        EventCode.CHANNEL_CLOSED -> {
                            logDebug("ANT_DEBUG: ANT HRM channel closed")
                            isHrmChannelOpen = false
                        }
                        else -> {}
                    }
                }
                else -> Unit
            }
        }
    }

    @Synchronized
    fun initialize() {
        if (!scope.isActive) return
        closed = false
        if (isServiceBound) {
            logDebug("ANT_DEBUG: ANT service already bound")
            return
        }

        logDebug("ANT_DEBUG: initialize() called — version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        logDebug(
            "ANT_DEBUG: Flags discoveryMode=$DiscoveryModeEnabled strictParity=$StrictVirtualPowerParityMode device=${AntPlusConstants.DEVICE_NUMBER} txTypes=${AntPlusConstants.TRANSMISSION_TYPES_TO_TRY.joinToString()}"
        )
        logDebug("ANT_DEBUG: Predefined networks available=${PredefinedNetwork.entries.joinToString { it.name }}")

        // Log the ANT Radio Service version code — determines which IPC path the SDK uses
        // (new handleMessage path if >= 40400, old acquireChannel AIDL transact if < 40400)
        val antServiceVersion = try {
            context.packageManager.getPackageInfo("com.dsi.ant.service.socket", 0).versionCode
        } catch (_: Exception) { -1 }
        logDebug("ANT_DEBUG: ANT Radio Service version code = $antServiceVersion (new IPC path if >= 40400)")

        // Check whether the ANT Radio Service app is actually installed before trying to bind
        val antRadioInstalled = try {
            context.packageManager.getPackageInfo("com.dsi.ant.service.socket", 0)
            true
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
        logDebug("ANT_DEBUG: ANT Radio Service (com.dsi.ant.service.socket) installed: $antRadioInstalled")
        if (!antRadioInstalled) {
            error("ANT Radio Service is not installed")
        }

        logDebug("ANT_DEBUG: Binding ANT service for $deviceName")
        val bindResult = AntService.bindService(context, this)
        isServiceBound = bindResult
        logDebug("ANT_DEBUG: AntService.bindService() returned $bindResult")
        if (!bindResult) {
            error("ANT Radio Service could not be bound")
        }
    }

    @Synchronized
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        if (closed || !scope.isActive) return
        logDebug("ANT_DEBUG: ANT Radio Service connected")
        if (service == null) {
            logWarn("ANT_DEBUG: ANT service binder was null")
            return
        }

        try {
            antService = AntService(service)
            antChannelProvider = antService?.channelProvider
            registerChannelProviderReceiverIfNeeded()
        } catch (e: Exception) {
            logWarn("ANT service initialization failed", e)
            shutdown()
            // Keep the active session eligible for its scheduled setup retry.
            closed = !scope.isActive
            return
        }
        // Channel setup can retry with delays; onServiceConnected runs on a Binder callback
        // thread and must not be blocked for that long.
        scope.launch { syncProviderStateFromProvider("onServiceConnected") }
    }

    @Synchronized
    override fun onServiceDisconnected(name: ComponentName?) {
        logDebug("ANT_DEBUG: ANT Radio Service disconnected")
        unregisterChannelProviderReceiverIfNeeded()
        cleanupChannel()
        antService = null
        antChannelProvider = null
        // Android keeps this binding registered until unbindService is called.
    }

    // Suspend so the retry delay below doesn't block the calling thread (previously a
    // runBlocking{delay(600)}, which could freeze the main thread for up to ~2.4s across
    // retries). The actual channel mutations still run under synchronized(this) - the same
    // monitor used by pushCurrentPayload()/pushCscPayload()/pushHrmPayload() - so a payload
    // push can never observe a half-torn-down channel; only the delay itself runs unlocked.
    private suspend fun setupAntChannel() {
        val channelProvider = synchronized(this) {
            if (closed || !scope.isActive || isChannelSetupInProgress || isChannelOpen) {
                return
            }
            isChannelSetupInProgress = true
            cleanupChannel()
            antChannelProvider
        }

        if (channelProvider == null) {
            synchronized(this) { isChannelSetupInProgress = false }
            logWarn("ANT_DEBUG: ANT channel provider unavailable")
            return
        }

        try {
            var lastError: Exception? = null
            repeat(4) { attempt ->
                val setupAttempt = attempt + 1
                logDebug("ANT_DEBUG: Channel setup attempt $setupAttempt/4")
                val result = synchronized(this) {
                    if (closed || !scope.isActive) return
                    trySetupAntChannelOnce(channelProvider)
                }
                if (result.success) {
                    return
                }

                lastError = result.error
                if (result.retryable) {
                    delay(600)
                }
            }

            synchronized(this) {
                logError("ANT_DEBUG: Failed to setup ANT channel on all attempts", lastError)
                cleanupChannel()
            }
        } finally {
            synchronized(this) { isChannelSetupInProgress = false }
        }
    }

    private data class ChannelSetupResult(
        val success: Boolean,
        val retryable: Boolean,
        val error: Exception? = null
    )

    private fun trySetupAntChannelOnce(channelProvider: AntChannelProvider): ChannelSetupResult {
        // Peloton's Radio Service exposes its managed ANT+ network in slot 1.
        // Other network slots are not interchangeable with ANT+.
        var lastError: Exception? = null

        logDebug("ANT_DEBUG: Trying networkId=1 (ANT+ slot)")
        for (transmissionType in AntPlusConstants.TRANSMISSION_TYPES_TO_TRY) {
            var candidateChannel: AntChannel? = null
            try {
                val acquiredChannel = acquireChannelByNetworkId(channelProvider, networkId = 1)
                    ?: throw IllegalStateException("acquireChannelByNetworkId returned null for networkId=1")
                candidateChannel = acquiredChannel

                configurePowerChannel(acquiredChannel, transmissionType)

                antChannel = acquiredChannel
                isChannelOpen = true
                currentTransmissionType = transmissionType
                pushCurrentPayload()
                logDebug("ANT_DEBUG: ✓ ANT+ LIVE on networkId=1 txType=$transmissionType")

                // Also set up CSC (Speed/Cadence) and HRM channels on separate devices
                setupCscChannel(channelProvider, transmissionType)
                setupHrmChannel(channelProvider, transmissionType)

                if (DiscoveryModeEnabled) {
                    logDebug("ANT_DEBUG: Discovery mode active (mock: power=${DiscoveryModePowerWatts}W cadence=${DiscoveryModeCadenceRpm}rpm)")
                }
                return ChannelSetupResult(success = true, retryable = false)
            } catch (e: Exception) {
                antChannel = null
                isChannelOpen = false
                lastError = e
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                logWarn("ANT_DEBUG: networkId=1 txType=$transmissionType failed — ${cause.javaClass.simpleName}: ${cause.message}", e)
            }
            try { candidateChannel?.release() } catch (_: Exception) {}
        }

        val retryable = lastError?.message?.contains("SERVICE_INITIALIZING", ignoreCase = true) == true
        return ChannelSetupResult(success = false, retryable = retryable, error = lastError)
    }

    /**
     * Acquires an ANT channel by raw integer network ID, bypassing the [PredefinedNetwork] enum.
     *
     * Uses reflection to call [com.dsi.ant.channel.ipc.IAntChannelProviderCommunicator.acquireChannel]
     * directly with an integer network ID. The Radio Service manages the RF frequency and network
     * key for the acquired channel.
     */
    private fun acquireChannelByNetworkId(channelProvider: AntChannelProvider, networkId: Int): AntChannel? {
        val communicatorField = AntChannelProvider::class.java.getDeclaredField("mAntChannelProvider")
        communicatorField.isAccessible = true
        val communicator = communicatorField.get(channelProvider)
            ?: throw IllegalStateException("mAntChannelProvider is null")

        logDebug("ANT_DEBUG: acquireChannelByNetworkId networkId=$networkId via ${communicator.javaClass.simpleName}")

        val capabilitiesClass = try {
            Class.forName("com.dsi.ant.channel.Capabilities")
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException("Capabilities class not found", e)
        }
        val acquireMethod = communicator.javaClass.getMethod(
            "acquireChannel",
            Context::class.java,
            Int::class.javaPrimitiveType,
            capabilitiesClass,
            capabilitiesClass,
            android.os.Bundle::class.java
        )

        val resultBundle = android.os.Bundle()
        val channelCommunicator = acquireMethod.invoke(communicator, context, networkId, null, null, resultBundle)
            ?: run {
                val errMsg = resultBundle.getString("error") ?: "unknown (no error key in bundle)"
                throw IllegalStateException("Service returned null channel communicator: $errMsg")
            }

        logDebug("ANT_DEBUG: acquireChannelByNetworkId got communicator=${channelCommunicator.javaClass.simpleName}")

        val iAntChannelCommunicatorClass = Class.forName("com.dsi.ant.channel.ipc.IAntChannelCommunicator")
        val antChannelConstructor = AntChannel::class.java.getDeclaredConstructor(iAntChannelCommunicatorClass)
        antChannelConstructor.isAccessible = true
        return antChannelConstructor.newInstance(channelCommunicator) as AntChannel
    }

    private fun configurePowerChannel(channel: AntChannel, transmissionType: Int) {
        logDebug("ANT_DEBUG: Setting channel event handler")
        channel.setChannelEventHandler(channelEventHandler)

        logDebug("ANT_DEBUG: Assigning channel as ${ChannelType.BIDIRECTIONAL_MASTER}")
        channel.assign(ChannelType.BIDIRECTIONAL_MASTER)

        logDebug("ANT_DEBUG: Setting channel period=${AntPlusConstants.POWER_METER_PERIOD}")
        channel.setPeriod(AntPlusConstants.POWER_METER_PERIOD)

        // Managed ANT+ networks can reject frequency changes because the service owns them.
        try {
            channel.setRfFrequency(AntPlusConstants.ANT_RF_FREQ)
            logDebug("ANT_DEBUG: RF frequency set to ${AntPlusConstants.ANT_RF_FREQ} (2457 MHz)")
        } catch (e: AntCommandFailedException) {
            logDebug("ANT_DEBUG: RF frequency ${AntPlusConstants.ANT_RF_FREQ} rejected (expected — managed network): service controls")
        } catch (e: Exception) {
            logDebug("ANT_DEBUG: RF frequency attempt (non-fatal): ${e.javaClass.simpleName}")
        }

        try {
            val maxTxPower = channel.getCapabilities().maxOutputPowerLevelSetting
            channel.setTransmitPower(maxTxPower)
            logDebug("ANT_DEBUG: Set channel transmit power to max supported level=$maxTxPower")
        } catch (e: Exception) {
            logWarn("ANT_DEBUG: Failed to set channel transmit power; continuing", e)
        }

        logDebug("ANT_DEBUG: Setting channel ID (device=${AntPlusConstants.DEVICE_NUMBER}, type=${AntPlusConstants.DEVICE_TYPE_POWER_METER}, tx=$transmissionType)")
        channel.setChannelId(
            ChannelId(
                AntPlusConstants.DEVICE_NUMBER,
                AntPlusConstants.DEVICE_TYPE_POWER_METER,
                transmissionType
            )
        )

        logDebug("ANT_DEBUG: Opening channel")
        channel.open()
    }

    /**
     * Updates the latest values to be advertised. Actual transmit timing is driven by ANT TX events.
     */
    @Synchronized
    fun broadcastPowerData(powerWatts: Int, cadenceRpm: Int) {
        latestPowerWatts = powerWatts.coerceAtLeast(0)
        latestCadenceRpm = cadenceRpm.coerceAtLeast(0)
        // TX events drive actual message cadence; this just updates latest values.
    }

    /**
     * Updates speed data to be broadcast via CSC (Cycling Speed and Cadence) profile.
     *
     * @param speedKmh Speed in km/h (float for precision)
     */
    @Synchronized
    fun broadcastSpeedData(speedKmh: Float) {
        latestSpeedKmh = speedKmh.coerceAtLeast(0.0f)
    }

    /**
     * Updates heart rate data to be broadcast via ANT+ HRM profile (device type 120).
     */
    @Synchronized
    fun broadcastHrmData(bpm: Int) {
        latestHeartRateBpm = bpm.coerceAtLeast(0)
    }

    @Synchronized
    fun hasConnectedDevices(): Boolean = isServiceBound && isChannelOpen

    @Synchronized
    fun shutdown() {
        closed = true
        logDebug("ANT_DEBUG: Shutting down ANT+")
        cleanupChannel()
        unregisterChannelProviderReceiverIfNeeded()

        if (isServiceBound) {
            isServiceBound = false
            runCatching { context.unbindService(this) }
                .onFailure { logWarn("ANT service unbind failed", it) }
        }

        antChannelProvider = null
        antService = null
    }

    @Synchronized
    private fun pushCurrentPayload() {
        val channel = antChannel ?: return
        if (!isChannelOpen) {
            return
        }

        try {
            channel.setBroadcastData(buildPowerPayload())
            logDebug(
                "ANT_DEBUG: Broadcast pwr data queued (power=${lastBroadcastPowerWatts}W, cadence=${lastBroadcastCadenceRpm}rpm, speed=${String.format("%.1f", lastBroadcastSpeedKmh)}km/h, nextEvent=${eventCount and 0xFF})"
            )
        } catch (e: RemoteException) {
            logError("ANT_DEBUG: Failed to queue ANT broadcast payload", e)
        } catch (e: AntCommandFailedException) {
            logError("ANT_DEBUG: ANT command failed while queuing broadcast payload", e)
        }
    }

    @Synchronized
    private fun buildPowerPayload(): ByteArray {
        val (effectivePower, effectiveCadence, effectiveSpeed) = effectiveMetricsForBroadcast()
        lastBroadcastPowerWatts = effectivePower
        lastBroadcastCadenceRpm = effectiveCadence
        lastBroadcastSpeedKmh = effectiveSpeed

        // Match VirtualPowerMeter startup behavior: first TX is a page-16 init frame.
        if (!hasSentInitializationPage) {
            hasSentInitializationPage = true
            powerPayloadBuffer.fill(0)
            powerPayloadBuffer[0] = AntPlusConstants.POWER_METER_PAGE_STANDARD.toByte()
            logDebug("ANT_DEBUG: Sent initialization payload=${powerPayloadBuffer.toHexString()}")
            return powerPayloadBuffer
        }

        val instantaneousPower = effectivePower.coerceIn(0, 0xFFFF)
        val cadence = effectiveCadence.coerceIn(0, 0xFF)
        val nextMessageCount = (messageCounter + 1) and 0xFFFF
        val payload = if (StrictVirtualPowerParityMode) {
            eventCount = (eventCount + 1) and 0xFF
            accumulatedPower = (accumulatedPower + instantaneousPower) and 0xFFFF
            buildStandardPowerPage(eventCount and 0xFF, cadence, instantaneousPower)
        } else {
            val rotationSlot = nextMessageCount % AntPlusConstants.COMMON_PAGE_ROTATION_INTERVAL
            when (rotationSlot) {
                AntPlusConstants.MANUFACTURER_PAGE_SLOT -> buildManufacturerInfoPage()
                AntPlusConstants.PRODUCT_PAGE_SLOT -> buildProductInfoPage()
                else -> {
                    eventCount = (eventCount + 1) and 0xFF
                    accumulatedPower = (accumulatedPower + instantaneousPower) and 0xFFFF
                    buildStandardPowerPage(eventCount and 0xFF, cadence, instantaneousPower)
                }
            }
        }

        messageCounter = nextMessageCount

        if (messageCounter % PayloadDebugEveryNMessages == 0) {
            logDebug("ANT_DEBUG: Payload bytes=${payload.toHexString()}")
        }

        return payload
    }

    @Synchronized
    private fun handleAcknowledgedData(messageParcel: AntMessageParcel) {
        val payload = AcknowledgedDataMessage(messageParcel).payload
        if (payload.size < AntPlusConstants.ANT_MESSAGE_SIZE) {
            return
        }

        val isCapabilitiesRequest = payload[0] == 0.toByte() &&
            payload[1] == 1.toByte() &&
            payload[2] == 0xAA.toByte()
        if (!isCapabilitiesRequest) {
            return
        }

        val response = byteArrayOf(
            0x01,
            0xAC.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0x00,
            0x00
        )
        val channel = antChannel ?: return
        if (!isChannelOpen) {
            return
        }

        try {
            channel.setBroadcastData(response)
            logDebug("ANT_DEBUG: Responded to acknowledged capabilities request")
        } catch (e: RemoteException) {
            logError("ANT_DEBUG: Failed to respond to acknowledged capabilities request", e)
        } catch (e: AntCommandFailedException) {
            logError("ANT_DEBUG: ANT command failed while responding to acknowledged request", e)
        }
    }

    private fun effectiveMetricsForBroadcast(): Triple<Int, Int, Float> {
        if (DiscoveryModeEnabled) {
            return Triple(DiscoveryModePowerWatts, DiscoveryModeCadenceRpm, 25.0f)  // mock 25 km/h
        }

        val power = latestPowerWatts.coerceAtLeast(0)
        val cadence = latestCadenceRpm.coerceAtLeast(0)
        val speed = latestSpeedKmh.coerceAtLeast(0.0f)
        return Triple(power, cadence, speed)
    }


    private fun buildStandardPowerPage(eventCountByte: Int, cadence: Int, instantaneousPower: Int): ByteArray {
        val payload = powerPayloadBuffer
        payload[0] = AntPlusConstants.POWER_METER_PAGE_STANDARD.toByte()
        payload[1] = eventCountByte.toByte()
        // Pedal power is not supported; 0xFF means "not used" per Bike Power profile.
        payload[2] = 0xFF.toByte()
        payload[3] = cadence.toByte()
        payload[4] = (accumulatedPower and 0xFF).toByte()
        payload[5] = ((accumulatedPower shr 8) and 0xFF).toByte()
        payload[6] = (instantaneousPower and 0xFF).toByte()
        payload[7] = ((instantaneousPower shr 8) and 0xFF).toByte()
        return payload
    }

    private fun buildManufacturerInfoPage(): ByteArray {
        val payload = powerPayloadBuffer
        payload[0] = AntPlusConstants.PAGE_MANUFACTURER_INFO.toByte()
        payload[1] = 0xFF.toByte()
        payload[2] = 0xFF.toByte()
        payload[3] = AntPlusConstants.HARDWARE_REVISION.toByte()
        // Manufacturer ID: use encoded value to help Garmin identify as Grupetto
        // 12345 = 0x3039 in hex
        payload[4] = (AntPlusConstants.MANUFACTURER_ID and 0xFF).toByte()           // 0x39 = 57
        payload[5] = ((AntPlusConstants.MANUFACTURER_ID shr 8) and 0xFF).toByte()   // 0x30 = 48
        // Model bytes: encode "GR" (Grupetto) as ASCII codes
        payload[6] = 0x47.toByte()  // 'G'
        payload[7] = 0x52.toByte()  // 'R'
        return payload
    }

    private fun buildProductInfoPage(): ByteArray {
        val payload = powerPayloadBuffer
        payload[0] = AntPlusConstants.PAGE_PRODUCT_INFO.toByte()
        payload[1] = 0xFF.toByte()
        payload[2] = AntPlusConstants.SOFTWARE_REVISION_SUPPLEMENTAL.toByte()
        payload[3] = AntPlusConstants.SOFTWARE_REVISION_MAIN.toByte()
        // Serial number bytes: encode "EP" (Grupetto Power) as identifier
        payload[4] = 0x45.toByte()  // 'E'
        payload[5] = 0x50.toByte()  // 'P'
        payload[6] = 0x00.toByte()  // version
        payload[7] = 0x01.toByte()  // instance
        return payload
    }

    /**
     * CSC (Cycling Speed and Cadence) combined data page.
     * Uses cumulative rev counters + event timestamps so receivers compute speed/cadence correctly.
     */
    private fun buildCscSpeedCadencePage(speedKmh: Float): ByteArray {
        val payload = cscPayloadBuffer

        val nowMs = SystemClock.elapsedRealtime()
        val deltaMs = (nowMs - cscLastUpdateMs).coerceAtLeast(0L)
        cscLastUpdateMs = nowMs

        val clampedSpeedKmh = speedKmh.coerceAtLeast(0.0f)
        val cadenceRpm = latestCadenceRpm.coerceAtLeast(0)
        val wheelCircumferenceMeters = 2.127

        // Wheel updates (speed -> wheel revolutions/time in 1/1024s ticks).
        val speedMps = clampedSpeedKmh / 3.6
        if (speedMps > 0.0) {
            val wheelRps = speedMps / wheelCircumferenceMeters
            val wheelDeltaRevs = wheelRps * (deltaMs / 1000.0)
            cscWheelResidual += wheelDeltaRevs
            val wheelRevsToAdd = kotlin.math.floor(cscWheelResidual).toInt()
            if (wheelRevsToAdd > 0) {
                cscWheelResidual -= wheelRevsToAdd
                val ticksPerWheelRev = 1024.0 / wheelRps
                val wheelTicksToAdd = (ticksPerWheelRev * wheelRevsToAdd).toInt().coerceAtLeast(1)
                cscCumulativeWheelRevolutions = (cscCumulativeWheelRevolutions + wheelRevsToAdd) and 0xFFFF
                cscLastWheelEventTime = (cscLastWheelEventTime + wheelTicksToAdd) and 0xFFFF
            }
        }

        // Crank updates (cadence -> crank revolutions/time in 1/1024s ticks).
        if (cadenceRpm > 0) {
            val crankRps = cadenceRpm / 60.0
            val crankDeltaRevs = crankRps * (deltaMs / 1000.0)
            cscCrankResidual += crankDeltaRevs
            val crankRevsToAdd = kotlin.math.floor(cscCrankResidual).toInt()
            if (crankRevsToAdd > 0) {
                cscCrankResidual -= crankRevsToAdd
                cscCumulativeCrankRevolutions = (cscCumulativeCrankRevolutions + crankRevsToAdd) and 0xFFFF
                val ticksPerCrankRev = (60.0 * 1024.0) / cadenceRpm
                val crankTicksToAdd = (ticksPerCrankRev * crankRevsToAdd).toInt().coerceAtLeast(1)
                cscLastCrankEventTime = (cscLastCrankEventTime + crankTicksToAdd) and 0xFFFF
            }
        }

        // ANT+ combined speed/cadence has no page-number byte: four uint16 fields.
        // [0..1] crank event time, [2..3] cumulative crank revs,
        // [4..5] wheel event time, [6..7] cumulative wheel revs.
        payload[0] = (cscLastCrankEventTime and 0xFF).toByte()
        payload[1] = ((cscLastCrankEventTime shr 8) and 0xFF).toByte()
        payload[2] = (cscCumulativeCrankRevolutions and 0xFF).toByte()
        payload[3] = ((cscCumulativeCrankRevolutions shr 8) and 0xFF).toByte()
        payload[4] = (cscLastWheelEventTime and 0xFF).toByte()
        payload[5] = ((cscLastWheelEventTime shr 8) and 0xFF).toByte()
        payload[6] = (cscCumulativeWheelRevolutions and 0xFF).toByte()
        payload[7] = ((cscCumulativeWheelRevolutions shr 8) and 0xFF).toByte()

        return payload
    }

    private fun cleanupChannel() {
        isChannelOpen = false
        isCscChannelOpen = false
        isHrmChannelOpen = false
        currentTransmissionType = -1
        hasSentInitializationPage = false
        eventCount = 0
        cscCumulativeWheelRevolutions = 0
        cscCumulativeCrankRevolutions = 0
        accumulatedPower = 0
        cscLastWheelEventTime = 0
        cscLastCrankEventTime = 0
        cscWheelResidual = 0.0
        cscCrankResidual = 0.0
        cscLastUpdateMs = SystemClock.elapsedRealtime()
        cscMessageCounter = 0
        hrmBeatCount = 0
        hrmLastBeatTime = 0
        hrmBeatResidual = 0.0
        hrmLastUpdateMs = SystemClock.elapsedRealtime()
        hrmMessageCounter = 0
        messageCounter = 0

        try {
            antChannel?.release()
        } catch (e: Exception) {
            logWarn("ANT_DEBUG: Error releasing power channel", e)
        }

        try {
            cscChannel?.release()
        } catch (e: Exception) {
            logWarn("ANT_DEBUG: Error releasing CSC channel", e)
        }

        try {
            hrmChannel?.release()
        } catch (e: Exception) {
            logWarn("ANT_DEBUG: Error releasing HRM channel", e)
        }

        antChannel = null
        cscChannel = null
        hrmChannel = null
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = " ") { byte ->
        String.format("%02X", byte.toInt() and 0xFF)
    }


    private fun registerChannelProviderReceiverIfNeeded() {
        if (isChannelProviderReceiverRegistered) {
            return
        }

        ContextCompat.registerReceiver(
            context,
            channelProviderStateReceiver,
            IntentFilter(AntChannelProvider.ACTION_CHANNEL_PROVIDER_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        isChannelProviderReceiverRegistered = true
        logDebug("ANT_DEBUG: Registered channel provider state receiver")
    }

    private fun unregisterChannelProviderReceiverIfNeeded() {
        if (!isChannelProviderReceiverRegistered) {
            return
        }

        try {
            context.unregisterReceiver(channelProviderStateReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver may already be unregistered.
        }
        isChannelProviderReceiverRegistered = false
        logDebug("ANT_DEBUG: Unregistered channel provider state receiver")
    }

    private suspend fun syncProviderStateFromProvider(source: String) {
        if (closed || !scope.isActive) return
        val provider = antChannelProvider
        if (provider == null) {
            logWarn("ANT_DEBUG: Cannot sync provider state ($source), provider unavailable")
            return
        }

        try {
            val numChannels = provider.numChannelsAvailable
            val legacyInUse = provider.isLegacyInterfaceInUse
            handleProviderState(numChannels, legacyInUse, source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarn("ANT_DEBUG: Failed to query channel provider state ($source)", e)
        }
    }

    // setupAntChannel() below is suspend (it can delay() between retries), so this can no
    // longer be a single @Synchronized function - see setupAntChannel()'s comment for how
    // mutual exclusion with the payload-push methods is preserved instead.
    private suspend fun handleProviderState(numChannels: Int, legacyInterfaceInUse: Boolean, source: String) {
        if (closed || !scope.isActive) return
        val canAcquireChannel = numChannels > 0 || legacyInterfaceInUse
        logDebug(
            "ANT_DEBUG: Provider state ($source): numChannels=$numChannels legacyInterfaceInUse=$legacyInterfaceInUse canAcquire=$canAcquireChannel"
        )

        if (!canAcquireChannel) {
            // Zero available channels can mean our own channels occupy the radio.
            return
        }

        val shouldSetupPowerChannel = synchronized(this) { !isChannelOpen && !isChannelSetupInProgress }
        if (shouldSetupPowerChannel) {
            setupAntChannel()
            return
        }

        synchronized(this) {
            if (closed || !scope.isActive) return
            if (isChannelOpen && currentTransmissionType >= 0) {
                // Power channel open; opportunistically retry any missing secondary channels
                val provider = antChannelProvider ?: return
                if (!isCscChannelOpen) setupCscChannel(provider, currentTransmissionType)
                if (!isHrmChannelOpen) setupHrmChannel(provider, currentTransmissionType)
            }
        }
    }

    @Synchronized
    fun retryChannelSetupIfNeeded() {
        if (closed || !scope.isActive) return
        if (isChannelSetupInProgress) return
        if (isChannelOpen) {
            val provider = antChannelProvider ?: return
            if (!isCscChannelOpen) setupCscChannel(provider, currentTransmissionType)
            if (!isHrmChannelOpen) setupHrmChannel(provider, currentTransmissionType)
            return
        }
        logDebug("ANT_DEBUG: boot-retry: no channel open, reconnecting ANT service")
        shutdown()
        initialize()
    }

    @Synchronized
    fun isChannelReady(): Boolean = isChannelOpen && isCscChannelOpen && isHrmChannelOpen

    private fun logDebug(message: String) = Timber.d(message)

    private fun logWarn(message: String, throwable: Throwable? = null) {
        if (throwable == null) Timber.w(message) else Timber.w(throwable, message)
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (throwable == null) Timber.e(message) else Timber.e(throwable, message)
    }

    private fun setupCscChannel(channelProvider: AntChannelProvider, transmissionType: Int) {
        // Set up a second ANT+ channel for CSC (Cycling Speed and Cadence) profile
        // Device type 121 = Speed/Cadence sensor
        try {
            val cscChannelAcquired = acquireChannelByNetworkId(channelProvider, networkId = 1)
                ?: throw IllegalStateException("Failed to acquire CSC channel")
            cscChannel = cscChannelAcquired

            cscChannelAcquired.setChannelEventHandler(cscChannelEventHandler)
            cscChannelAcquired.assign(ChannelType.BIDIRECTIONAL_MASTER)
            cscChannelAcquired.setPeriod(AntPlusConstants.SPEED_CADENCE_PERIOD)

            try {
                cscChannelAcquired.setRfFrequency(AntPlusConstants.ANT_RF_FREQ)
            } catch (e: AntCommandFailedException) {
                logDebug("ANT_DEBUG: CSC RF frequency rejected (managed network)")
            }

            try {
                val maxTxPower = cscChannelAcquired.getCapabilities().maxOutputPowerLevelSetting
                cscChannelAcquired.setTransmitPower(maxTxPower)
            } catch (e: Exception) {
                logWarn("ANT_DEBUG: Failed to set CSC transmit power", e)
            }

            // Device number 2 for CSC (to differentiate from power channel which is 1)
            cscChannelAcquired.setChannelId(
                ChannelId(2, AntPlusConstants.DEVICE_TYPE_SPEED_CADENCE, transmissionType)
            )

            cscChannelAcquired.open()
            cscChannel = cscChannelAcquired
            isCscChannelOpen = true
            logDebug("ANT_DEBUG: CSC (Speed/Cadence) channel opened on networkId=1 as device 2")
        } catch (e: Exception) {
            runCatching { cscChannel?.release() }
            cscChannel = null
            isCscChannelOpen = false
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            logWarn("ANT_DEBUG: Failed to setup CSC channel — ${cause.javaClass.simpleName}: ${cause.message}", e)
        }
    }

    private fun setupHrmChannel(channelProvider: AntChannelProvider, transmissionType: Int) {
        try {
            val hrmChannelAcquired = acquireChannelByNetworkId(channelProvider, networkId = 1)
                ?: throw IllegalStateException("Failed to acquire HRM channel")
            hrmChannel = hrmChannelAcquired

            hrmChannelAcquired.setChannelEventHandler(hrmChannelEventHandler)
            hrmChannelAcquired.assign(ChannelType.BIDIRECTIONAL_MASTER)
            hrmChannelAcquired.setPeriod(AntPlusConstants.HRM_PERIOD)

            try {
                hrmChannelAcquired.setRfFrequency(AntPlusConstants.ANT_RF_FREQ)
            } catch (e: AntCommandFailedException) {
                logDebug("ANT_DEBUG: HRM RF frequency rejected (managed network)")
            }

            try {
                val maxTxPower = hrmChannelAcquired.getCapabilities().maxOutputPowerLevelSetting
                hrmChannelAcquired.setTransmitPower(maxTxPower)
            } catch (e: Exception) {
                logWarn("ANT_DEBUG: Failed to set HRM transmit power", e)
            }

            hrmChannelAcquired.setChannelId(
                ChannelId(3, AntPlusConstants.DEVICE_TYPE_HRM, transmissionType)
            )

            hrmChannelAcquired.open()
            hrmChannel = hrmChannelAcquired
            isHrmChannelOpen = true
            logDebug("ANT_DEBUG: HRM channel opened on networkId=1 as device 3")
        } catch (e: Exception) {
            runCatching { hrmChannel?.release() }
            hrmChannel = null
            isHrmChannelOpen = false
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            logWarn("ANT_DEBUG: Failed to setup HRM channel — ${cause.javaClass.simpleName}: ${cause.message}", e)
        }
    }

    @Synchronized
    private fun buildHrmPayload(): ByteArray {
        val bpm = if (DiscoveryModeEnabled) 140 else latestHeartRateBpm

        val nowMs = SystemClock.elapsedRealtime()
        val deltaMs = (nowMs - hrmLastUpdateMs).coerceAtLeast(0L)
        hrmLastUpdateMs = nowMs

        if (bpm > 0) {
            val beatsPerMs = bpm / 60000.0
            hrmBeatResidual += beatsPerMs * deltaMs
            val beatsToAdd = kotlin.math.floor(hrmBeatResidual).toInt()
            if (beatsToAdd > 0) {
                hrmBeatResidual -= beatsToAdd
                hrmBeatCount = (hrmBeatCount + beatsToAdd) and 0xFF
                val ticksPerBeat = (60.0 * 1024.0) / bpm
                hrmLastBeatTime = (hrmLastBeatTime + (ticksPerBeat * beatsToAdd).toInt().coerceAtLeast(1)) and 0xFFFF
            }
        }

        val payload = hrmPayloadBuffer
        payload[0] = AntPlusConstants.HRM_PAGE_DATA.toByte()
        payload[1] = 0xFF.toByte()
        payload[2] = 0xFF.toByte()
        payload[3] = 0xFF.toByte()
        payload[4] = (hrmLastBeatTime and 0xFF).toByte()
        payload[5] = ((hrmLastBeatTime shr 8) and 0xFF).toByte()
        payload[6] = (hrmBeatCount and 0xFF).toByte()
        payload[7] = bpm.coerceIn(0, 255).toByte()
        return payload
    }

    @Synchronized
    private fun pushHrmPayload() {
        val channel = hrmChannel ?: return
        if (!isHrmChannelOpen) return
        try {
            channel.setBroadcastData(buildHrmPayload())
            hrmMessageCounter = (hrmMessageCounter + 1) and 0xFFFF
            if (hrmMessageCounter % PayloadDebugEveryNMessages == 0) {
                logDebug("ANT_DEBUG: HRM payload queued (bpm=$latestHeartRateBpm, beatCount=$hrmBeatCount, beatTime=$hrmLastBeatTime)")
            }
        } catch (e: RemoteException) {
            logError("ANT_DEBUG: Failed to queue HRM payload", e)
        } catch (e: AntCommandFailedException) {
            logError("ANT_DEBUG: ANT command failed while queuing HRM payload", e)
        }
    }

    @Synchronized
    private fun pushCscPayload() {
        val channel = cscChannel ?: return
        if (!isCscChannelOpen) {
            return
        }

        try {
            val payload = buildCscSpeedCadencePage(latestSpeedKmh)
            channel.setBroadcastData(payload)
            cscMessageCounter = (cscMessageCounter + 1) and 0xFFFF
            if (cscMessageCounter % PayloadDebugEveryNMessages == 0) {
                logDebug(
                    "ANT_DEBUG: CSC payload queued (speed=${String.format("%.1f", latestSpeedKmh)}km/h, cadence=${latestCadenceRpm}rpm, wheelRev=${cscCumulativeWheelRevolutions}, wheelTime=${cscLastWheelEventTime}, crankRev=${cscCumulativeCrankRevolutions}, crankTime=${cscLastCrankEventTime}, bytes=${payload.toHexString()})"
                )
            }
        } catch (e: RemoteException) {
            logError("ANT_DEBUG: Failed to queue CSC payload", e)
        } catch (e: AntCommandFailedException) {
            logError("ANT_DEBUG: ANT command failed while queuing CSC payload", e)
        }
    }
}
