package com.spop.poverlay.openbikecontrol

import android.content.Context
import android.content.SharedPreferences
import android.net.nsd.NsdManager
import android.os.SystemClock
import com.spop.poverlay.ConfigurationRepository
import com.spop.poverlay.sensor.heartrate.HeartRateBluetoothAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

data class OpenBikeControlStatus(
    val enabled: Boolean = false,
    /** False until the overlay service starts the remotes. */
    val active: Boolean = false,
    val network: ObcLink = ObcLink.Off,
    val bluetooth: ObcLink = ObcLink.Off,
    val battery: Int? = null,
    val lastShift: String? = null
)

/**
 * Shifts the Bike+ from OpenBikeControl remotes (for example BikeControl, which relays
 * Zwift Click, Cycplus, gamepads and many other controllers). Both transports run while
 * the setting is on; a press from either one is one shift.
 */
class OpenBikeControlManager internal constructor(
    private val preferences: SharedPreferences,
    private val shift: ((Int) -> Unit)?,
    private val network: (OpenBikeControlManager) -> ObcNetworkClient,
    private val bluetooth: (OpenBikeControlManager) -> ObcBluetoothClient
) {
    companion object {
        fun create(context: Context, preferencesName: String, appVersion: String, shift: ((Int) -> Unit)?): OpenBikeControlManager {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val clock: () -> Long = SystemClock::elapsedRealtime
            val appInfo = { OpenBikeControl.encodeAppInfo(appVersion) }
            return OpenBikeControlManager(context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE), shift,
                { manager -> ObcNetworkClient(context.getSystemService(Context.NSD_SERVICE) as? NsdManager, scope,
                    ObcTcpSession(clock), appInfo, manager::networkLink, manager::networkMessage) },
                { manager -> ObcBluetoothClient(HeartRateBluetoothAccess(context), scope, clock, appInfo,
                    manager::bluetoothLink, manager::bluetoothMessage) })
        }
    }

    private val key = ConfigurationRepository.Preferences.OpenBikeControlEnabled.key
    private val mutableStatus = MutableStateFlow(OpenBikeControlStatus(enabled = preferences.getBoolean(key, false)))
    val status: StateFlow<OpenBikeControlStatus> = mutableStatus.asStateFlow()
    val supported get() = shift != null

    private val networkClient by lazy { network(this) }
    private val bluetoothClient by lazy { bluetooth(this) }
    private val networkButtons = ObcButtonTracker(::onShift)
    private val bluetoothButtons = ObcButtonTracker(::onShift)
    private var started = false
    private var transportsRunning = false

    // Kept as a field: SharedPreferences holds listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
        if (changed == key) sync()
    }

    /** Follows the setting from now on. Called when the overlay service starts. */
    @Synchronized fun start() {
        if (started) return
        started = true
        preferences.registerOnSharedPreferenceChangeListener(listener)
        sync()
    }

    @Synchronized fun stop() {
        if (!started) return
        started = false
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        stopTransports()
    }

    @Synchronized private fun sync() {
        val enabled = preferences.getBoolean(key, false)
        mutableStatus.update { it.copy(enabled = enabled) }
        if (started && enabled && supported) startTransports() else stopTransports()
    }

    private fun startTransports() {
        if (transportsRunning) return
        transportsRunning = true
        mutableStatus.update { it.copy(active = true) }
        Timber.i("OpenBikeControl remotes on")
        networkClient.start()
        bluetoothClient.start()
    }

    private fun stopTransports() {
        if (!transportsRunning) return
        transportsRunning = false
        Timber.i("OpenBikeControl remotes off")
        networkClient.stop()
        bluetoothClient.stop()
        networkButtons.reset(); bluetoothButtons.reset()
        mutableStatus.update { it.copy(active = false, battery = null) }
    }

    internal fun networkLink(link: ObcLink) {
        if (link.phase != ObcLink.Phase.Connected) synchronized(networkButtons) { networkButtons.reset() }
        mutableStatus.update { it.copy(network = link) }
        // One remote is enough: no Bluetooth scans while a Wi-Fi remote is connected.
        bluetoothClient.pauseSearch(link.phase == ObcLink.Phase.Connected)
    }

    internal fun bluetoothLink(link: ObcLink) {
        if (link.phase != ObcLink.Phase.Connected) synchronized(bluetoothButtons) { bluetoothButtons.reset() }
        mutableStatus.update { it.copy(bluetooth = link) }
    }

    internal fun networkMessage(message: ObcMessage) = receive(networkButtons, message)
    internal fun bluetoothMessage(message: ObcMessage) = receive(bluetoothButtons, message)

    private fun receive(tracker: ObcButtonTracker, message: ObcMessage) {
        if (message is ObcMessage.DeviceStatus) {
            mutableStatus.update { it.copy(battery = message.battery) }
            return
        }
        synchronized(tracker) { tracker.handle(message) }
    }

    private fun onShift(direction: Int, button: Int) {
        shift?.invoke(direction)
        mutableStatus.update { it.copy(lastShift = if (direction > 0) "Shift up" else "Shift down") }
        Timber.d("OpenBikeControl button 0x%02x -> shift %+d", button, direction)
    }
}
