package com.spop.poverlay.openbikecontrol

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/** Where one transport is. [device] is the remote's advertised name. */
data class ObcLink(val phase: Phase, val device: String? = null, val detail: String? = null) {
    enum class Phase { Off, Searching, Connecting, Connected, Unavailable }
    companion object { val Off = ObcLink(Phase.Off) }
}

/**
 * Wi-Fi transport: finds `_openbikecontrol._tcp` remotes with mDNS and connects to the
 * first one it resolves. When the connection ends, it searches again after [retryDelayMs],
 * because a restarted remote can come back on a different port.
 */
internal class ObcNetworkClient(
    private val nsd: NsdManager?,
    private val scope: CoroutineScope,
    private val session: ObcTcpSession,
    private val appInfo: () -> ByteArray,
    private val onLink: (ObcLink) -> Unit,
    private val onMessage: (ObcMessage) -> Unit,
    private val retryDelayMs: Long = 3000
) {
    private var running = false
    private var discovery: NsdManager.DiscoveryListener? = null
    private var resolving = false
    private var connection: Job? = null
    private var retry: Job? = null

    @Synchronized fun start() {
        if (running) return
        running = true
        discover()
    }

    @Synchronized fun stop() {
        running = false
        retry?.cancel(); retry = null
        stopDiscovery()
        connection?.cancel(); connection = null
        resolving = false
        onLink(ObcLink.Off)
    }

    private fun discover() {
        if (!running || discovery != null || connection != null) return
        val nsd = nsd ?: run { onLink(ObcLink(ObcLink.Phase.Unavailable, detail = "Network discovery unavailable")); return }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(this@ObcNetworkClient) {
                    if (discovery !== this) return
                    discovery = null
                    Timber.w("OpenBikeControl discovery failed: %d", errorCode)
                    onLink(ObcLink(ObcLink.Phase.Unavailable, detail = "Network discovery failed ($errorCode)"))
                    retryLater()
                }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                synchronized(this@ObcNetworkClient) {
                    if (discovery !== this || resolving || connection != null) return
                    resolve(service)
                }
            }
        }
        discovery = listener
        onLink(ObcLink(ObcLink.Phase.Searching))
        try {
            nsd.discoverServices(OpenBikeControl.MdnsServiceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            Timber.w(e, "OpenBikeControl discovery could not start")
            discovery = null
            onLink(ObcLink(ObcLink.Phase.Unavailable, detail = "Network discovery unavailable"))
            retryLater()
        }
    }

    // Android resolves one service at a time; a second resolve fails with FAILURE_ALREADY_ACTIVE.
    private fun resolve(service: NsdServiceInfo) {
        val nsd = nsd ?: return
        resolving = true
        try {
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    synchronized(this@ObcNetworkClient) {
                        resolving = false
                        if (!running) return
                        Timber.w("OpenBikeControl resolve failed: %d", errorCode)
                        // Discovery reports a service once; search again to hear it again.
                        stopDiscovery(); retryLater()
                    }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    synchronized(this@ObcNetworkClient) {
                        resolving = false
                        if (!running || connection != null) return
                        connect(info)
                    }
                }
            })
        } catch (e: RuntimeException) {
            Timber.w(e, "OpenBikeControl resolve could not start")
            resolving = false
            stopDiscovery(); retryLater()
        }
    }

    private fun connect(info: NsdServiceInfo) {
        @Suppress("DEPRECATION") val host = info.host
        val name = info.serviceName
        if (host == null) { stopDiscovery(); retryLater(); return }
        stopDiscovery()
        onLink(ObcLink(ObcLink.Phase.Connecting, name))
        Timber.i("OpenBikeControl connecting to %s at %s:%d", name, host.hostAddress, info.port)
        connection = scope.launch {
            var reason = "Connection closed"
            try {
                session.run(host, info.port, appInfo(), { onLink(ObcLink(ObcLink.Phase.Connected, name)) }, onMessage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reason = "Connection failed"
                Timber.w(e, "OpenBikeControl connection to %s ended", name)
            }
            synchronized(this@ObcNetworkClient) {
                if (connection !== coroutineContext[Job]) return@launch
                connection = null
                if (!running) return@launch
                onLink(ObcLink(ObcLink.Phase.Searching, name, "$reason · reconnecting"))
                retryLater()
            }
        }
    }

    private fun stopDiscovery() {
        val listener = discovery ?: return
        discovery = null
        try { nsd?.stopServiceDiscovery(listener) } catch (e: RuntimeException) { Timber.w(e, "OpenBikeControl discovery stop") }
    }

    private fun retryLater() {
        if (!running || retry?.isActive == true) return
        retry = scope.launch {
            delay(retryDelayMs)
            synchronized(this@ObcNetworkClient) { retry = null; discover() }
        }
    }
}
