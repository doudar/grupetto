package com.spop.poverlay.dircon

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.spop.poverlay.ble.BleServer

// DirCon protocol constants
private const val DIRCON_MESSAGE_HEADER_LENGTH = 6

private const val DIRCON_CHAR_PROP_FLAG_READ = 0x01
private const val DIRCON_CHAR_PROP_FLAG_WRITE = 0x02
private const val DIRCON_CHAR_PROP_FLAG_NOTIFY = 0x04

private const val DIRCON_MSGID_ERROR: Int = 0xFF
private const val DIRCON_MSGID_DISCOVER_SERVICES: Int = 0x01
private const val DIRCON_MSGID_DISCOVER_CHARACTERISTICS: Int = 0x02
private const val DIRCON_MSGID_READ_CHARACTERISTIC: Int = 0x03
private const val DIRCON_MSGID_WRITE_CHARACTERISTIC: Int = 0x04
private const val DIRCON_MSGID_ENABLE_CHARACTERISTIC_NOTIFICATIONS: Int = 0x05
private const val DIRCON_MSGID_UNSOLICITED_CHARACTERISTIC_NOTIFICATION: Int = 0x06

private const val DIRCON_RESPCODE_SUCCESS_REQUEST: Int = 0x00
private const val DIRCON_RESPCODE_UNKNOWN_MESSAGE_TYPE: Int = 0x01
private const val DIRCON_RESPCODE_UNEXPECTED_ERROR: Int = 0x02
private const val DIRCON_RESPCODE_SERVICE_NOT_FOUND: Int = 0x03
private const val DIRCON_RESPCODE_CHARACTERISTIC_NOT_FOUND: Int = 0x04
private const val DIRCON_RESPCODE_CHARACTERISTIC_OPERATION_NOT_SUPPORTED: Int = 0x05
private const val DIRCON_RESPCODE_CHARACTERISTIC_WRITE_FAILED: Int = 0x06
private const val DIRCON_RESPCODE_UNKNOWN_PROTOCOL: Int = 0x07

private const val MDNS_SERVICE_TYPE = "_wahoo-fitness-tnp._tcp."
private const val TCP_PORT = 8081
private const val MAX_CLIENTS = 1
private const val RECEIVE_BUFFER_SIZE = 256
private const val SEND_BUFFER_SIZE = 256
private const val MAX_SUBS_TRACKED = 64

// Minimal DirCon message codec (Kotlin)
private data class DirConMessage(
    var messageVersion: Int = 1,
    var identifier: Int = DIRCON_MSGID_ERROR,
    var sequenceNumber: Int = 0,
    var responseCode: Int = DIRCON_RESPCODE_SUCCESS_REQUEST,
    var length: Int = 0,
    var uuid: UUID? = null,
    val additionalUuids: MutableList<UUID> = mutableListOf(),
    val additionalData: MutableList<Byte> = mutableListOf(),
    var isRequest: Boolean = false
) {
    fun encode(lastSequence: Int): ByteArray {
        // Sequence logic
        if (isRequest) {
            sequenceNumber = (sequenceNumber + 1) and 0xFF
        } else if (identifier == DIRCON_MSGID_UNSOLICITED_CHARACTERISTIC_NOTIFICATION) {
            sequenceNumber = 0
        } else {
            sequenceNumber = lastSequence
        }

        val out = ByteArrayOutputStream(SEND_BUFFER_SIZE)

        fun putHeader(len: Int) {
            out.write(messageVersion)
            out.write(identifier)
            out.write(sequenceNumber)
            out.write(responseCode)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
        }

        when {
            !isRequest && responseCode != DIRCON_RESPCODE_SUCCESS_REQUEST -> {
                length = 0
                putHeader(0)
            }
            identifier == DIRCON_MSGID_DISCOVER_SERVICES -> {
                if (isRequest) {
                    length = 0
                    putHeader(0)
                } else {
                    length = additionalUuids.size * 16
                    putHeader(length)
                    additionalUuids.forEach { out.write(uuidToDirconBytes(it)) }
                }
            }
            identifier == DIRCON_MSGID_DISCOVER_CHARACTERISTICS && !isRequest -> {
                val perChar = 16 + 1 // uuid + properties byte
                length = 16 + additionalUuids.size * perChar
                putHeader(length)
                out.write(uuidToDirconBytes(requireNotNull(uuid)))
                var dataIdx = 0
                additionalUuids.forEach {
                    out.write(uuidToDirconBytes(it))
                    out.write(additionalData[dataIdx].toInt() and 0xFF)
                    dataIdx++
                }
            }
            ((identifier == DIRCON_MSGID_READ_CHARACTERISTIC || identifier == DIRCON_MSGID_DISCOVER_CHARACTERISTICS) && isRequest) ||
            (identifier == DIRCON_MSGID_ENABLE_CHARACTERISTIC_NOTIFICATIONS && !isRequest) -> {
                length = 16
                putHeader(length)
                out.write(uuidToDirconBytes(requireNotNull(uuid)))
            }
            identifier == DIRCON_MSGID_WRITE_CHARACTERISTIC ||
            identifier == DIRCON_MSGID_UNSOLICITED_CHARACTERISTIC_NOTIFICATION ||
            (identifier == DIRCON_MSGID_READ_CHARACTERISTIC && !isRequest) ||
            (identifier == DIRCON_MSGID_ENABLE_CHARACTERISTIC_NOTIFICATIONS && isRequest) -> {
                length = 16 + additionalData.size
                putHeader(length)
                out.write(uuidToDirconBytes(requireNotNull(uuid)))
                additionalData.forEach { out.write(it.toInt() and 0xFF) }
            }
            else -> {
                // Default empty error
                identifier = DIRCON_MSGID_ERROR
                responseCode = DIRCON_RESPCODE_UNKNOWN_MESSAGE_TYPE
                length = 0
                putHeader(0)
            }
        }
        return out.toByteArray()
    }

    companion object {
        fun parse(buf: ByteArray, offset: Int, available: Int, lastSeq: Int): Pair<DirConMessage?, Int> {
            if (available < DIRCON_MESSAGE_HEADER_LENGTH) return null to 0
            val mv = buf[offset].toUByte().toInt()
            val id = buf[offset + 1].toUByte().toInt()
            val seq = buf[offset + 2].toUByte().toInt()
            val rc = buf[offset + 3].toUByte().toInt()
            val len = ((buf[offset + 4].toUByte().toInt() shl 8) or buf[offset + 5].toUByte().toInt())
            val total = DIRCON_MESSAGE_HEADER_LENGTH + len
            if (available < total) return null to 0

            val m = DirConMessage(
                messageVersion = mv,
                identifier = id,
                sequenceNumber = seq,
                responseCode = rc,
                length = len,
                isRequest = rc == DIRCON_RESPCODE_SUCCESS_REQUEST && (lastSeq <= 0 || lastSeq != seq)
            )

            var parsed = DIRCON_MESSAGE_HEADER_LENGTH
            fun slice(start: Int, count: Int) = buf.copyOfRange(offset + start, offset + start + count)

            when (id) {
                DIRCON_MSGID_DISCOVER_SERVICES -> {
                    if (len % 16 != 0) return null to total
                    var idx = 0
                    while (idx + 16 <= len) {
                        m.additionalUuids += bytesToDirconUuid(slice(DIRCON_MESSAGE_HEADER_LENGTH + idx, 16))
                        idx += 16
                    }
                    parsed += len
                }
                DIRCON_MSGID_DISCOVER_CHARACTERISTICS -> {
                    if (len >= 16) {
                        m.uuid = bytesToDirconUuid(slice(DIRCON_MESSAGE_HEADER_LENGTH, 16))
                        parsed += 16
                        if (len > 16) {
                            parsed += (len - 16)
                        }
                    }
                }
                DIRCON_MSGID_READ_CHARACTERISTIC -> {
                    if (len >= 16) {
                        m.uuid = bytesToDirconUuid(slice(DIRCON_MESSAGE_HEADER_LENGTH, 16))
                        parsed += 16
                        if (len > 16) {
                            val data = slice(DIRCON_MESSAGE_HEADER_LENGTH + 16, len - 16)
                            data.forEach { m.additionalData += it }
                            parsed += (len - 16)
                        }
                    }
                }
                DIRCON_MSGID_WRITE_CHARACTERISTIC -> {
                    if (len > 16) {
                        m.uuid = bytesToDirconUuid(slice(DIRCON_MESSAGE_HEADER_LENGTH, 16))
                        val data = slice(DIRCON_MESSAGE_HEADER_LENGTH + 16, len - 16)
                        data.forEach { m.additionalData += it }
                        m.isRequest = true
                        parsed += len
                    }
                }
                DIRCON_MSGID_ENABLE_CHARACTERISTIC_NOTIFICATIONS -> {
                    if (len == 16 || len == 17) {
                        m.uuid = bytesToDirconUuid(slice(DIRCON_MESSAGE_HEADER_LENGTH, 16))
                        if (len == 17) {
                            m.isRequest = true
                            m.additionalData += buf[offset + DIRCON_MESSAGE_HEADER_LENGTH + 16]
                        }
                        parsed += len
                    }
                }
                DIRCON_MSGID_UNSOLICITED_CHARACTERISTIC_NOTIFICATION -> {
                    if (len > 16) {
                        m.uuid = bytesToDirconUuid(slice(DIRCON_MESSAGE_HEADER_LENGTH, 16))
                        val data = slice(DIRCON_MESSAGE_HEADER_LENGTH + 16, len - 16)
                        data.forEach { m.additionalData += it }
                        parsed += len
                    }
                }
                else -> return null to total
            }
            return m to total
        }
    }
}

// UUID <-> DirCon bytes (matches the ESP32 impl that reverses byte order)
private fun uuidToDirconBytes(uuid: UUID): ByteArray {
    val bb = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
    bb.putLong(uuid.mostSignificantBits)
    bb.putLong(uuid.leastSignificantBits)
    return bb.array().reversedArray()
}

private fun bytesToDirconUuid(bytes16: ByteArray): UUID {
    val rev = bytes16.reversedArray()
    val bb = ByteBuffer.wrap(rev).order(ByteOrder.BIG_ENDIAN)
    val msb = bb.getLong()
    val lsb = bb.getLong()
    return UUID(msb, lsb)
}

private fun shortUuidOrFull(uuid: UUID): String {
    val base = UUID.fromString("00000000-0000-1000-8000-00805f9b34fb")
    val msb = uuid.mostSignificantBits
    val lsb = uuid.leastSignificantBits
    val isSig = (lsb == base.leastSignificantBits) &&
        ((msb and -0x100000000000000L) == (base.mostSignificantBits and -0x100000000000000L))
    return if (isSig) {
        val short = ((msb ushr 32) and 0xFFFF).toInt()
        "%04X".format(short)
    } else uuid.toString()
}

private data class Client(
    val socket: Socket,
    val inStream: BufferedInputStream,
    val outStream: BufferedOutputStream,
    var lastSequence: Int = 0,
    val recvBuf: ByteArray = ByteArray(RECEIVE_BUFFER_SIZE),
    var recvLen: Int = 0
)

class DirConServer(
    private val context: Context,
    private val bleServer: BleServer
) : CoroutineScope {
    override val coroutineContext = SupervisorJob() + Dispatchers.IO

    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val clients = mutableListOf<Client>()
    private val subscriptions = ConcurrentHashMap<Int, MutableSet<UUID>>() // clientIndex -> set of characteristic UUIDs

    // mDNS/NSD
    private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun start() {
        if (serverJob?.isActive == true) return
        startNsd()
        serverJob = launch {
            startTcpServer()
        }
        Timber.i("DirCon: started")
    }

    fun stop() {
        try {
            stopNsd()
            serverJob?.cancel()
            synchronized(clients) {
                clients.forEach { runCatching { it.socket.close() } }
                clients.clear()
            }
            runCatching { serverSocket?.close() }
            serverSocket = null
        } catch (_: Exception) {
        } finally {
            Timber.i("DirCon: stopped")
        }
    }

    // Broadcast BLE notifications to subscribed DirCon clients
    fun broadcastNotification(characteristicUuid: UUID, data: ByteArray) {
        val message = DirConMessage(
            identifier = DIRCON_MSGID_UNSOLICITED_CHARACTERISTIC_NOTIFICATION,
            responseCode = DIRCON_RESPCODE_SUCCESS_REQUEST,
            uuid = characteristicUuid,
            isRequest = false
        ).apply {
            additionalData += data.toList()
        }
        val payload = message.encode(0)

        synchronized(clients) {
            clients.forEachIndexed { idx, client ->
                val subs = subscriptions[idx] ?: mutableSetOf()
                if (!client.socket.isConnected || !subs.contains(characteristicUuid)) return@forEachIndexed
                runCatching {
                    client.outStream.write(payload)
                    client.outStream.flush()
                }.onFailure { Timber.w(it, "DirCon: notify write failed") }
            }
        }
    }

    // --- NSD (mDNS) publish ---
    private fun startNsd() {
        val info = NsdServiceInfo().apply {
            serviceType = MDNS_SERVICE_TYPE
            serviceName = "Grupetto"
            port = TCP_PORT
            // TXT records (attributes)
            val serial = "GRUP-${bleServer.serialNumber()}"
            setAttribute("serial-number", serial)
            val uuids = bleServer.getGattServices().joinToString(",") { svc: BluetoothGattService -> shortUuidOrFull(svc.uuid) }
            setAttribute("ble-service-uuids", uuids)
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Timber.e("DirCon NSD registration failed: $errorCode")
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Timber.e("DirCon NSD unregistration failed: $errorCode")
            }
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                Timber.i("DirCon NSD registered: ${serviceInfo?.serviceName}")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                Timber.i("DirCon NSD unregistered")
            }
        }
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Timber.w(e, "DirCon: NSD register failed")
        }
    }

    private fun stopNsd() {
        registrationListener?.let {
            runCatching { nsdManager.unregisterService(it) }
        }
        registrationListener = null
    }

    // --- TCP server / client handling ---
    private suspend fun startTcpServer() = withContext(Dispatchers.IO) {
        serverSocket = ServerSocket(TCP_PORT)
        acceptLoop@ while (isActive) {
            try {
                val socket = serverSocket!!.accept()
                addClient(socket)
            } catch (e: Exception) {
                if (isActive) Timber.w(e, "DirCon: accept failed")
                break@acceptLoop
            }
        }
    }

    private fun addClient(socket: Socket) {
        synchronized(clients) {
            if (clients.size >= MAX_CLIENTS) {
                runCatching { socket.close() }
                Timber.w("DirCon: rejected client, max reached")
                return
            }
            val client = Client(
                socket = socket,
                inStream = BufferedInputStream(socket.getInputStream()),
                outStream = BufferedOutputStream(socket.getOutputStream())
            )
            clients += client
            subscriptions[clients.lastIndex] = mutableSetOf()
            Timber.i("DirCon: client connected ${socket.inetAddress.hostAddress}")
            launch { handleClient(clients.lastIndex) }
        }
    }

    private suspend fun handleClient(index: Int) = withContext(Dispatchers.IO) {
        val client = synchronized(clients) { clients.getOrNull(index) } ?: return@withContext
        try {
            while (isActive && client.socket.isConnected) {
                // Read available bytes (non-blocking-ish)
                val read = client.inStream.read(client.recvBuf, client.recvLen, client.recvBuf.size - client.recvLen)
                if (read <= 0) {
                    delay(10)
                    continue
                }
                client.recvLen += read

                var processed = 0
                while (processed + DIRCON_MESSAGE_HEADER_LENGTH <= client.recvLen) {
                    val available = client.recvLen - processed
                    val (msg, totalBytes) = DirConMessage.parse(client.recvBuf, processed, available, client.lastSequence)
                    if (totalBytes == 0) break
                    if (msg != null && msg.identifier != DIRCON_MSGID_ERROR) {
                        client.lastSequence = msg.sequenceNumber
                        processMessage(index, client, msg)
                    }
                    processed += totalBytes
                }

                // Slide remaining
                if (processed > 0) {
                    if (processed < client.recvLen) {
                        System.arraycopy(client.recvBuf, processed, client.recvBuf, 0, client.recvLen - processed)
                        client.recvLen -= processed
                    } else {
                        client.recvLen = 0
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "DirCon: client loop error")
        } finally {
            closeClient(index)
        }
    }

    private fun closeClient(index: Int) {
        synchronized(clients) {
            val c = clients.getOrNull(index) ?: return
            runCatching { c.socket.close() }
            clients.removeAt(index)
            subscriptions.remove(index)
            Timber.i("DirCon: client disconnected")
        }
    }

    // --- Message handling ---
    private fun processMessage(index: Int, client: Client, msg: DirConMessage) {
        if (!msg.isRequest) return
        when (msg.identifier) {
            DIRCON_MSGID_DISCOVER_SERVICES -> {
                val resp = DirConMessage(
                    identifier = DIRCON_MSGID_DISCOVER_SERVICES,
                    responseCode = DIRCON_RESPCODE_SUCCESS_REQUEST,
                    isRequest = false
                )
                bleServer.getGattServices().forEach { svc: BluetoothGattService ->
                    resp.additionalUuids += svc.uuid
                }
                sendResponse(index, client, resp)
            }
            DIRCON_MSGID_DISCOVER_CHARACTERISTICS -> {
                val svcUuid = msg.uuid
                val resp = DirConMessage(
                    identifier = DIRCON_MSGID_DISCOVER_CHARACTERISTICS,
                    responseCode = DIRCON_RESPCODE_SUCCESS_REQUEST,
                    uuid = svcUuid,
                    isRequest = false
                )
                val service = bleServer.getGattServices().firstOrNull { it: BluetoothGattService -> it.uuid == svcUuid }
                if (service == null) {
                    sendError(index, client, DIRCON_MSGID_DISCOVER_CHARACTERISTICS, msg.sequenceNumber, DIRCON_RESPCODE_SERVICE_NOT_FOUND)
                    return
                }
                service.characteristics.forEach { ch: BluetoothGattCharacteristic ->
                    resp.additionalUuids += ch.uuid
                    resp.additionalData += mapAndroidPropsToDirCon(ch.properties).toByte()
                }
                sendResponse(index, client, resp)
            }
            DIRCON_MSGID_READ_CHARACTERISTIC -> {
                val cuuid = msg.uuid
                val ch = cuuid?.let { bleServer.findCharacteristicByUuid(it) }
                if (ch == null) {
                    sendError(index, client, DIRCON_MSGID_READ_CHARACTERISTIC, msg.sequenceNumber, DIRCON_RESPCODE_CHARACTERISTIC_NOT_FOUND); return
                }
                if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
                    sendError(index, client, DIRCON_MSGID_READ_CHARACTERISTIC, msg.sequenceNumber, DIRCON_RESPCODE_CHARACTERISTIC_OPERATION_NOT_SUPPORTED); return
                }
                val resp = DirConMessage(
                    identifier = DIRCON_MSGID_READ_CHARACTERISTIC,
                    responseCode = DIRCON_RESPCODE_SUCCESS_REQUEST,
                    uuid = cuuid,
                    isRequest = false
                )
                (ch.value ?: ByteArray(0)).forEach { resp.additionalData += it }
                sendResponse(index, client, resp)
            }
            DIRCON_MSGID_WRITE_CHARACTERISTIC -> {
                val cuuid = msg.uuid
                val ch = cuuid?.let { bleServer.findCharacteristicByUuid(it) }
                if (ch == null) {
                    sendError(index, client, DIRCON_MSGID_WRITE_CHARACTERISTIC, msg.sequenceNumber, DIRCON_RESPCODE_CHARACTERISTIC_NOT_FOUND); return
                }
                if ((ch.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
                    sendError(index, client, DIRCON_MSGID_WRITE_CHARACTERISTIC, msg.sequenceNumber, DIRCON_RESPCODE_CHARACTERISTIC_OPERATION_NOT_SUPPORTED); return
                }
                val ok = bleServer.performLocalWrite(cuuid, msg.additionalData.toByteArray())
                if (!ok) {
                    sendError(index, client, DIRCON_MSGID_WRITE_CHARACTERISTIC, msg.sequenceNumber, DIRCON_RESPCODE_CHARACTERISTIC_WRITE_FAILED); return
                }
                val resp = DirConMessage(
                    identifier = DIRCON_MSGID_WRITE_CHARACTERISTIC,
                    responseCode = DIRCON_RESPCODE_SUCCESS_REQUEST,
                    uuid = cuuid,
                    isRequest = false
                )
                // Optional: echo current characteristic value
                (ch.value ?: ByteArray(0)).forEach { resp.additionalData += it }
                sendResponse(index, client, resp)
            }
            DIRCON_MSGID_ENABLE_CHARACTERISTIC_NOTIFICATIONS -> {
                val cuuid = msg.uuid
                val ch = cuuid?.let { bleServer.findCharacteristicByUuid(it) }
                if (ch == null) {
                    sendError(index, client, DIRCON_MSGID_ENABLE_CHARACTERISTIC_NOTIFICATIONS, msg.sequenceNumber, DIRCON_RESPCODE_CHARACTERISTIC_NOT_FOUND); return
                }
                if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                    sendError(index, client, DIRCON_MSGID_ENABLE_CHARACTERISTIC_NOTIFICATIONS, msg.sequenceNumber, DIRCON_RESPCODE_CHARACTERISTIC_OPERATION_NOT_SUPPORTED); return
                }
                val enable = msg.additionalData.firstOrNull()?.toInt()?.let { it != 0 } ?: false
                val set = subscriptions[index] ?: mutableSetOf<UUID>().also { subscriptions[index] = it }
                cuuid?.let { if (enable) set += it else set -= it }
                val resp = DirConMessage(
                    identifier = DIRCON_MSGID_ENABLE_CHARACTERISTIC_NOTIFICATIONS,
                    responseCode = DIRCON_RESPCODE_SUCCESS_REQUEST,
                    uuid = cuuid,
                    isRequest = false
                )
                sendResponse(index, client, resp)
            }
            else -> {
                sendError(index, client, msg.identifier, msg.sequenceNumber, DIRCON_RESPCODE_UNKNOWN_MESSAGE_TYPE)
            }
        }
    }

    private fun sendError(index: Int, client: Client, msgId: Int, seq: Int, code: Int) {
        val err = DirConMessage(
            identifier = msgId,
            sequenceNumber = seq,
            responseCode = code,
            isRequest = false
        )
        write(index, client, err.encode(client.lastSequence))
    }

    private fun sendResponse(index: Int, client: Client, resp: DirConMessage) {
        write(index, client, resp.encode(client.lastSequence))
    }

    private fun write(index: Int, client: Client, data: ByteArray) {
        runCatching {
            client.outStream.write(data)
            client.outStream.flush()
        }.onFailure {
            Timber.w(it, "DirCon: send failed; closing client")
            closeClient(index)
        }
    }

    private fun mapAndroidPropsToDirCon(props: Int): Int {
        var out = 0
        if ((props and BluetoothGattCharacteristic.PROPERTY_READ) != 0) out = out or DIRCON_CHAR_PROP_FLAG_READ
        if ((props and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) out = out or DIRCON_CHAR_PROP_FLAG_WRITE
        if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) out = out or DIRCON_CHAR_PROP_FLAG_NOTIFY
        return out
    }
}