package com.spop.poverlay.openbikecontrol

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ObcNetworkTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).apply { soTimeout = 5000 }
    private val appInfo = OpenBikeControl.encodeAppInfo("test")

    @After fun tearDown() { scope.cancel(); server.close() }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun Socket.readAppInfo(): ByteArray {
        soTimeout = 5000
        val buffer = ByteArray(appInfo.size)
        var read = 0
        while (read < buffer.size) read += getInputStream().read(buffer, read, buffer.size - read).also { check(it > 0) }
        return buffer
    }

    private fun waitFor(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "timed out" }; Thread.sleep(10) }
    }

    @Test fun `session sends app info and splits merged button messages`() = runBlocking {
        val messages = CopyOnWriteArrayList<ObcMessage>()
        var connected = false
        val remote = scope.async {
            server.accept().use { socket ->
                val info = socket.readAppInfo()
                // BikeControl writes a press and its release back to back.
                socket.getOutputStream().apply { write(bytes(1, 1, 1, 1, 1, 0)); flush() }
                Thread.sleep(50)
                socket.getOutputStream().apply { write(bytes(1, 2, 1)); write(bytes(1, 2, 0)); flush() }
                Thread.sleep(50)
                info
            }
        }
        withTimeout(5000) {
            ObcTcpSession(System::currentTimeMillis).run(InetAddress.getLoopbackAddress(), server.localPort, appInfo,
                { connected = true }, { messages += it })
        }
        assertArrayEquals(appInfo, remote.await())
        assertTrue(connected)
        val buttons = messages.filterIsInstance<ObcMessage.Buttons>().flatMap { it.states }
        assertEquals(listOf(1 to 1, 1 to 0, 2 to 1, 2 to 0), buttons)
    }

    @Test fun `a held two-button message is delivered while the connection stays open`() = runBlocking {
        val messages = CopyOnWriteArrayList<ObcMessage>()
        val delivered = CompletableDeferred<Long>()
        val remote = scope.async {
            server.accept().use { socket ->
                socket.readAppInfo()
                val sent = System.currentTimeMillis()
                socket.getOutputStream().apply { write(bytes(1, 0x30, 1, 1, 1)); flush() }
                withTimeout(2000) { delivered.await() } - sent
            }
        }
        withTimeout(5000) {
            ObcTcpSession(System::currentTimeMillis).run(InetAddress.getLoopbackAddress(), server.localPort, appInfo, {}) {
                messages += it
                delivered.complete(System.currentTimeMillis())
            }
        }
        val latency = remote.await()
        assertEquals(listOf(ObcMessage.Buttons(listOf(0x30 to 1, 0x01 to 1))), messages)
        assertTrue("settled after $latency ms", latency < 1000)
    }

    @Test fun `cancelling a session closes the connection`() = runBlocking {
        val accepted = LinkedBlockingQueue<Socket>()
        scope.launch { accepted.put(server.accept()) }
        val session = scope.launch {
            ObcTcpSession(System::currentTimeMillis).run(InetAddress.getLoopbackAddress(), server.localPort, appInfo, {}, {})
        }
        val socket = accepted.poll(5, TimeUnit.SECONDS)!!
        socket.readAppInfo()
        withTimeout(2000) { session.cancelAndJoin() }
        // The remote sees the close instead of a connection left open.
        assertEquals(-1, socket.getInputStream().read())
        socket.close()
    }

    @Test fun `discovers, connects, and searches again when the remote goes away`() {
        val nsd = mockk<NsdManager>(relaxed = true)
        val discoveries = CopyOnWriteArrayList<NsdManager.DiscoveryListener>()
        every { nsd.discoverServices(OpenBikeControl.MdnsServiceType, NsdManager.PROTOCOL_DNS_SD, capture(discoveries)) } just Runs
        val resolve = slot<NsdManager.ResolveListener>()
        every { nsd.resolveService(any(), capture(resolve)) } just Runs
        val found = mockk<NsdServiceInfo>(relaxed = true)
        val resolved = mockk<NsdServiceInfo>(relaxed = true)
        @Suppress("DEPRECATION")
        every { resolved.host } returns InetAddress.getLoopbackAddress()
        every { resolved.port } returns server.localPort
        every { resolved.serviceName } returns "BikeControl"

        val links = CopyOnWriteArrayList<ObcLink>()
        val messages = CopyOnWriteArrayList<ObcMessage>()
        val client = ObcNetworkClient(nsd, scope, ObcTcpSession(System::currentTimeMillis), { appInfo },
            { links += it }, { messages += it }, retryDelayMs = 100)
        client.start()
        assertEquals(1, discoveries.size)
        assertEquals(ObcLink.Phase.Searching, links.last().phase)

        discoveries[0].onServiceFound(found)
        discoveries[0].onServiceFound(found) // a second report while resolving is ignored
        verify(exactly = 1) { nsd.resolveService(found, any()) }
        resolve.captured.onServiceResolved(resolved)
        verify { nsd.stopServiceDiscovery(discoveries[0]) }

        server.accept().use { socket ->
            assertArrayEquals(appInfo, socket.readAppInfo())
            waitFor { links.last().phase == ObcLink.Phase.Connected }
            assertEquals("BikeControl", links.last().device)
            socket.getOutputStream().apply { write(bytes(1, 1, 1)); flush() }
            waitFor { messages.isNotEmpty() }
        }
        assertEquals(ObcMessage.Buttons(listOf(1 to 1)), messages[0])

        // The remote closed: Grupetto searches again with a fresh discovery.
        waitFor { discoveries.size == 2 }
        assertTrue(links.any { it.phase == ObcLink.Phase.Searching && it.detail == "Connection closed · reconnecting" })

        client.stop()
        assertEquals(ObcLink.Off, links.last())
        verify { nsd.stopServiceDiscovery(discoveries[1]) }
    }

    @Test fun `a failed resolve searches again`() {
        val nsd = mockk<NsdManager>(relaxed = true)
        val discoveries = CopyOnWriteArrayList<NsdManager.DiscoveryListener>()
        every { nsd.discoverServices(any<String>(), any(), capture(discoveries)) } just Runs
        val resolve = slot<NsdManager.ResolveListener>()
        every { nsd.resolveService(any(), capture(resolve)) } just Runs
        val client = ObcNetworkClient(nsd, scope, ObcTcpSession(System::currentTimeMillis), { appInfo }, {}, {},
            retryDelayMs = 50)
        client.start()
        val service = mockk<NsdServiceInfo>(relaxed = true)
        discoveries[0].onServiceFound(service)
        resolve.captured.onResolveFailed(service, NsdManager.FAILURE_INTERNAL_ERROR)
        waitFor { discoveries.size == 2 }
        client.stop()
    }

    @Test fun `without network discovery the link says so`() {
        val links = CopyOnWriteArrayList<ObcLink>()
        ObcNetworkClient(null, scope, ObcTcpSession(System::currentTimeMillis), { appInfo }, { links += it }, {}).start()
        assertEquals(ObcLink(ObcLink.Phase.Unavailable, detail = "Network discovery unavailable"), links.last())
    }
}
