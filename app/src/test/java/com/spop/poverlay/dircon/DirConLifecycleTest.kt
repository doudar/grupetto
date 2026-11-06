package com.spop.poverlay.dircon

import android.content.Context
import android.net.nsd.NsdManager
import android.bluetooth.BluetoothManager
import com.spop.poverlay.ble.BleServer
import com.spop.poverlay.ble.BaseBleService
import com.spop.poverlay.sensor.interfaces.SensorInterface
import io.mockk.*
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DirConLifecycleTest {
    private lateinit var context: Context
    private val servers = mutableListOf<DirConServer>()
    private val measurement = UUID.fromString("00002a63-0000-1000-8000-00805f9b34fb")
    private val service = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb")
    private val payload = byteArrayOf(0x30,0,100,0,0,0,0,0,0,0,0,0,0,0)
    @Before fun setup() {
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.NSD_SERVICE) } returns mockk<NsdManager>(relaxed = true)
        mockkConstructor(DirConMdnsAdvertiser::class)
        every { anyConstructed<DirConMdnsAdvertiser>().start(any(),any(),any()) } just Runs
        every { anyConstructed<DirConMdnsAdvertiser>().stop() } just Runs
    }
    @After fun cleanup() {
        servers.forEach { it.stop(); it.coroutineContext.cancel() }
        unmockkAll()
    }
    private fun server(port: Int = 0): DirConServer {
        val bridge = object : DirConGattBridge {
            override fun services() = listOf(DirConService(service,listOf(DirConCharacteristic(measurement,4,payload))))
            override fun readCharacteristic(uuid: UUID) = payload
            override fun writeCharacteristic(uuid: UUID,value: ByteArray): Boolean = error("No control writes allowed")
        }
        return DirConServer(context,bridge,{"local-fixture"},port).also { servers.add(it) }
    }
    private fun socket(server: DirConServer) = DirConServer::class.java.getDeclaredField("serverSocket")
        .also { it.isAccessible = true }.get(server) as ServerSocket?
    private fun awaitStatus(server: DirConServer, expected: DirConStatus) {
        val until = System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
        while (server.status.value != expected && System.nanoTime() < until) Thread.sleep(5)
        assertEquals(expected,server.status.value)
    }
    private fun connect(server: DirConServer): Socket {
        awaitStatus(server,DirConStatus.Listening)
        return Socket("127.0.0.1",checkNotNull(socket(server)).localPort).also { it.soTimeout = 2000 }
    }
    private fun read(client: Socket): DirConMessage {
        fun bytes(size: Int): ByteArray {
            val b = ByteArray(size); var offset = 0
            while (offset < size) { val n = client.getInputStream().read(b,offset,size-offset); check(n > 0); offset += n }
            return b
        }
        val header = bytes(6)
        val size = ((header[4].toInt() and 255) shl 8) or (header[5].toInt() and 255)
        return DirConCodec.decode(header+bytes(size))!!.message
    }
    private fun subscribe(client: Socket, enabled: Boolean = true) {
        // Same wire format as the independent field probe; no write/control request is sent.
        val uuidBytes = java.nio.ByteBuffer.allocate(16).putLong(measurement.mostSignificantBits)
            .putLong(measurement.leastSignificantBits).array()
        client.getOutputStream().write(byteArrayOf(1,5,10,0,0,17)+uuidBytes+byteArrayOf(if (enabled) 1 else 0))
        val ack = read(client)
        assertEquals(5,ack.identifier); assertEquals(10,ack.sequenceNumber); assertEquals(0,ack.responseCode)
    }
    @Test fun probeSubscriptionReceivesNotificationsAndUnsubscribeIsClientLocal() {
        val server = server(); server.start()
        connect(server).use { first -> connect(server).use { second ->
            subscribe(first); subscribe(second); subscribe(first,false)
            server.notifyCharacteristicChanged(measurement,payload)
            val notification = read(second)
            assertEquals(6,notification.identifier); assertEquals(measurement,notification.uuid)
            assertArrayEquals(payload,notification.data)
            first.soTimeout = 100
            assertThrows(SocketTimeoutException::class.java) { read(first) }
        } }
    }
    @Test fun concurrentBleStartsAndStopStartReuseOneTransportOwner() {
        mockkConstructor(DirConServer::class)
        every { anyConstructed<DirConServer>().start() } just Runs
        every { anyConstructed<DirConServer>().stop() } just Runs
        val ble = BleServer(context,mockk<BluetoothManager>(relaxed = true),mockk<SensorInterface>(relaxed = true),
            object : com.spop.poverlay.ble.TimeProvider { override fun elapsedRealtime() = 0L })
        val services = BleServer::class.java.getDeclaredField("registeredServices").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (services.get(ble) as MutableList<BaseBleService>).add(mockk(relaxed = true))
        val start = BleServer::class.java.getDeclaredMethod("startDirCon").also { it.isAccessible = true }
        val stop = BleServer::class.java.getDeclaredMethod("stopDirCon").also { it.isAccessible = true }
        val pool = Executors.newFixedThreadPool(4)
        try {
            (0 until 20).map { pool.submit { start.invoke(ble) } }.forEach { it.get(4,TimeUnit.SECONDS) }
            stop.invoke(ble); start.invoke(ble)
            verify(exactly = 1) { context.getSystemService(Context.NSD_SERVICE) }
            verify(exactly = 21) { anyConstructed<DirConServer>().start() }
        } finally { pool.shutdownNow(); ble.coroutineContext.cancel() }
    }
    @Test fun occupiedPortDoesNotAdvertiseAndRetryWorksAfterPortIsFreed() {
        val occupied = ServerSocket(0)
        val server = server(occupied.localPort)
        try {
            server.start(); awaitStatus(server,DirConStatus.Failed)
            verify(exactly = 0) { anyConstructed<DirConMdnsAdvertiser>().start(any(),any(),any()) }
        } finally { occupied.close() }
        // Failed status is published before finally retires the job; wait for cleanup deterministically.
        val job = DirConServer::class.java.getDeclaredField("acceptJob").also { it.isAccessible = true }
        val until = System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
        while (job.get(server) != null && System.nanoTime() < until) Thread.sleep(5)
        assertNull(job.get(server))
        server.start()
        connect(server).use { client ->
            subscribe(client); server.notifyCharacteristicChanged(measurement,payload)
            assertArrayEquals(payload,read(client).data)
        }
    }
    @Test fun concurrentStartsBindOnceAndKeepNotificationsOnTheListeningInstance() {
        val server = server()
        val pool = Executors.newFixedThreadPool(4)
        try {
            (0 until 20).map { pool.submit { server.start() } }.forEach { it.get(4,TimeUnit.SECONDS) }
            connect(server).use { client ->
                subscribe(client); server.notifyCharacteristicChanged(measurement,payload)
                assertArrayEquals(payload,read(client).data)
            }
            verify(exactly = 1) { anyConstructed<DirConMdnsAdvertiser>().start(any(),any(),any()) }
        } finally { pool.shutdownNow() }
    }
    @Test fun rapidStopStartCannotLetAnOldGenerationCloseTheNewListener() {
        val server = server()
        repeat(40) { server.start(); server.stop() }
        server.start()
        connect(server).use { client ->
            subscribe(client)
            repeat(4) {
                server.notifyCharacteristicChanged(measurement,payload)
                assertArrayEquals(payload,read(client).data)
            }
        }
        assertEquals(DirConStatus.Listening,server.status.value)
    }
    @Test fun stopClosesSubscribedClientsAndRestartAcceptsFreshClients() {
        val server = server(); server.start()
        connect(server).use { client ->
            subscribe(client); server.stop()
            assertEquals(DirConStatus.Stopped,server.status.value)
            assertEquals(-1,client.getInputStream().read())
        }
        server.start()
        connect(server).use { client ->
            subscribe(client); server.notifyCharacteristicChanged(measurement,payload)
            assertArrayEquals(payload,read(client).data)
        }
    }
}
