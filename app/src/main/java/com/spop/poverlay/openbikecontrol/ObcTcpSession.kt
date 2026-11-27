package com.spop.poverlay.openbikecontrol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * One TCP connection to an OpenBikeControl remote. Sends App Info right after connecting,
 * then reads until the remote closes the connection or the coroutine is cancelled.
 * Returns when the remote closes; throws on a connect or read error. The caller reconnects.
 */
internal class ObcTcpSession(
    private val clock: () -> Long,
    private val connectTimeoutMs: Int = 5000,
    private val socketFactory: () -> Socket = ::Socket
) {
    suspend fun run(host: InetAddress, port: Int, appInfo: ByteArray,
                    onConnected: () -> Unit, onMessage: (ObcMessage) -> Unit) = coroutineScope {
        val socket = socketFactory()
        // A blocking read ignores cancellation; closing the socket ends it.
        val closer = launch { try { awaitCancellation() } finally { runCatching { socket.close() } } }
        try {
            withContext(Dispatchers.IO) {
                val decoder = ObcStreamDecoder()
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.getOutputStream().apply { write(appInfo); flush() }
                onConnected()
                val input = socket.getInputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    // Wait without a limit, unless the decoder holds bytes it may still settle.
                    socket.soTimeout = decoder.waitMs(clock())?.toInt() ?: 0
                    val read = try { input.read(buffer) } catch (e: SocketTimeoutException) {
                        decoder.flush(clock()).forEach(onMessage); continue
                    }
                    if (read < 0) break
                    decoder.offer(buffer.copyOf(read), clock()).forEach(onMessage)
                }
            }
        } finally {
            closer.cancel()
            runCatching { socket.close() }
        }
    }
}
