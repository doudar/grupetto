package com.spop.poverlay.dircon

import android.content.Context
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

enum class DirConStatus { Stopped, Starting, Listening, Failed }

class DirConServer(
    context: Context,
    private val bridge: DirConGattBridge,
    private val serialNumberProvider: () -> String,
    private val port: Int = DirConConstants.TcpPort
) : CoroutineScope {
    override val coroutineContext = SupervisorJob() + Dispatchers.IO

    private val mdnsAdvertiser = DirConMdnsAdvertiser(context.applicationContext)
    private val clients = Collections.synchronizedSet(mutableSetOf<ClientSession>())
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val lifecycleLock = Any()
    private var generation = 0L
    private var requested = false
    private val mutableStatus = MutableStateFlow(DirConStatus.Stopped)
    val status = mutableStatus.asStateFlow()

    fun start() {
        // Read bridge state before taking the lifecycle lock: the bridge may hold BleServer's monitor.
        val serviceUuids = bridge.services()
            .map { it.uuid.toShortUuidString() }
            .toDirConMdnsServiceUuidList()
        val serial = serialNumberProvider()
        val job = synchronized(lifecycleLock) {
            if (requested) return
            requested = true
            val token = ++generation
            mutableStatus.value = DirConStatus.Starting
            launch(start = CoroutineStart.LAZY) { listen(token, serviceUuids, serial) }
                .also { acceptJob = it }
        }
        job.start()
    }

    private suspend fun listen(token: Long, serviceUuids: List<String>, serial: String) = coroutineScope {
        var ownedSocket: ServerSocket? = null
        try {
            // Binding happens on IO. Stop and a newer generation cannot overtake publication/close.
            val socket = synchronized(lifecycleLock) {
                if (!requested || generation != token || !isActive) return@coroutineScope
                ServerSocket(port).also {
                    ownedSocket = it
                    serverSocket = it
                    mdnsAdvertiser.start(it.localPort, serviceUuids, serial)
                    mutableStatus.value = DirConStatus.Listening
                }
            }
            Timber.i("DIRCON TCP server listening on port ${socket.localPort}")
            while (isActive) {
                val client = socket.accept()
                val session = ClientSession(client)
                val accepted = synchronized(lifecycleLock) {
                    if (!requested || generation != token || !isActive) false
                    else { clients.add(session); true }
                }
                if (accepted) launch { session.run() } else session.close()
            }
        } catch (error: Exception) {
            val failed = synchronized(lifecycleLock) {
                (requested && generation == token).also { if (it) mutableStatus.value = DirConStatus.Failed }
            }
            if (failed) {
                Timber.e(error, "DIRCON TCP server stopped unexpectedly")
            }
        } finally {
            val abandoned = synchronized(lifecycleLock) {
                runCatching { ownedSocket?.close() }
                if (generation == token) {
                    requested = false
                    acceptJob = null
                    serverSocket = null
                    mdnsAdvertiser.stop()
                    if (mutableStatus.value != DirConStatus.Failed) mutableStatus.value = DirConStatus.Stopped
                    drainClients()
                } else emptyList()
            }
            abandoned.forEach { it.close() }
        }
    }

    private fun drainClients(): List<ClientSession> =
        synchronized(clients) {
            clients.toList().also { clients.clear() }
        }

    fun stop() {
        val abandoned = synchronized(lifecycleLock) {
            requested = false
            generation++
            acceptJob?.cancel()
            acceptJob = null
            runCatching { serverSocket?.close() }
            serverSocket = null
            mdnsAdvertiser.stop()
            mutableStatus.value = DirConStatus.Stopped
            drainClients()
        }
        // Disconnect callbacks may acquire BleServer's monitor; never call them under lifecycleLock.
        abandoned.forEach { it.close() }
    }

    fun notifyCharacteristicChanged(uuid: UUID, value: ByteArray, client: String? = null) {
        val message = DirConMessage(
            identifier = DirConConstants.MessageUnsolicitedCharacteristicNotification,
            sequenceNumber = 0,
            uuid = uuid,
            data = value
        )
        val encoded = DirConCodec.encode(message)

        synchronized(clients) {
            clients
                .filter { it.isSubscribed(uuid) && (client == null || client == it.id) }
                .forEach { it.write(encoded) }
        }
    }

    private fun handle(message: DirConMessage, session: ClientSession): DirConMessage {
        if (message.version != DirConConstants.ProtocolVersion) {
            return errorResponse(message, DirConConstants.ResponseUnknownProtocol)
        }

        return when (message.identifier) {
            DirConConstants.MessageDiscoverServices -> {
                DirConMessage(
                    identifier = message.identifier,
                    sequenceNumber = message.sequenceNumber,
                    uuids = bridge.services().map { it.uuid }
                )
            }
            DirConConstants.MessageDiscoverCharacteristics -> {
                val serviceUuid = message.uuid
                    ?: return errorResponse(message, DirConConstants.ResponseServiceNotFound)
                val service = bridge.services().firstOrNull { it.uuid == serviceUuid }
                    ?: return errorResponse(message, DirConConstants.ResponseServiceNotFound)
                DirConMessage(
                    identifier = message.identifier,
                    sequenceNumber = message.sequenceNumber,
                    uuid = service.uuid,
                    uuids = service.characteristics.map { it.uuid },
                    properties = service.characteristics.map { it.properties }
                )
            }
            DirConConstants.MessageReadCharacteristic -> {
                val characteristicUuid = message.uuid
                    ?: return errorResponse(message, DirConConstants.ResponseCharacteristicNotFound)
                val value = bridge.readCharacteristic(characteristicUuid)
                    ?: return errorResponse(message, DirConConstants.ResponseCharacteristicNotFound)
                DirConMessage(
                    identifier = message.identifier,
                    sequenceNumber = message.sequenceNumber,
                    uuid = characteristicUuid,
                    data = value
                )
            }
            DirConConstants.MessageWriteCharacteristic -> {
                val characteristicUuid = message.uuid
                    ?: return errorResponse(message, DirConConstants.ResponseCharacteristicNotFound)
                if (!bridge.writeCharacteristic(session.id, characteristicUuid, message.data)) {
                    return errorResponse(message, DirConConstants.ResponseCharacteristicWriteFailed)
                }
                DirConMessage(
                    identifier = message.identifier,
                    sequenceNumber = message.sequenceNumber,
                    uuid = characteristicUuid
                )
            }
            DirConConstants.MessageEnableCharacteristicNotifications -> {
                val characteristicUuid = message.uuid
                    ?: return errorResponse(message, DirConConstants.ResponseCharacteristicNotFound)
                val exists = bridge.services().any { service ->
                    service.characteristics.any { it.uuid == characteristicUuid }
                }
                if (!exists) {
                    return errorResponse(message, DirConConstants.ResponseCharacteristicNotFound)
                }

                val enabled = message.data.firstOrNull()?.toInt()?.and(0xFF) != 0
                if (enabled) session.subscribe(characteristicUuid) else session.unsubscribe(characteristicUuid)
                DirConMessage(
                    identifier = message.identifier,
                    sequenceNumber = message.sequenceNumber,
                    uuid = characteristicUuid
                )
            }
            else -> errorResponse(message, DirConConstants.ResponseUnknownMessageType)
        }
    }

    private fun errorResponse(message: DirConMessage, code: Int): DirConMessage =
        DirConMessage(
            identifier = message.identifier,
            sequenceNumber = message.sequenceNumber,
            responseCode = code
        )

    private inner class ClientSession(private val socket: Socket) {
        val id = "dircon:${UUID.randomUUID()}"
        private val subscriptions = Collections.synchronizedSet(mutableSetOf<UUID>())

        fun run() {
            Timber.i("DIRCON client connected from ${socket.remoteSocketAddress}")
            try {
                val input = socket.getInputStream()
                val readBuffer = ByteArray(512)
                var pending = ByteArray(0)

                while (isActive && !socket.isClosed) {
                    val read = input.read(readBuffer)
                    if (read < 0) throw EOFException()
                    pending += readBuffer.copyOf(read)

                    var offset = 0
                    while (offset < pending.size) {
                        val decoded = runCatching {
                            DirConCodec.decode(pending, offset, pending.size - offset)
                        }.getOrElse { error ->
                            Timber.w(error, "Invalid DIRCON frame")
                            null
                        } ?: break

                        val response = handle(decoded.message, this)
                        write(DirConCodec.encode(response))
                        offset += decoded.bytesConsumed
                    }
                    pending = pending.copyOfRange(offset, pending.size)
                }
            } catch (_: EOFException) {
                Timber.i("DIRCON client disconnected")
            } catch (_: SocketException) {
                Timber.i("DIRCON client socket closed")
            } catch (error: Exception) {
                Timber.e(error, "DIRCON client failed")
            } finally {
                clients.remove(this)
                close()
            }
        }

        fun subscribe(uuid: UUID) {
            subscriptions.add(uuid)
        }

        fun unsubscribe(uuid: UUID) {
            subscriptions.remove(uuid)
        }

        fun isSubscribed(uuid: UUID): Boolean = subscriptions.contains(uuid)

        fun write(bytes: ByteArray) {
            runCatching {
                synchronized(socket) {
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }
            }.onFailure { Timber.d(it, "Failed to write DIRCON frame") }
        }

        fun close() {
            bridge.disconnected(id)
            runCatching { socket.close() }
        }
    }
}

private fun UUID.toShortUuidString(): String {
    val text = toString()
    return if (text.endsWith("-0000-1000-8000-00805f9b34fb", ignoreCase = true)) {
        text.substring(4, 8).lowercase()
    } else {
        text.lowercase()
    }
}

internal fun List<String>.toDirConMdnsServiceUuidList(): List<String> {
    val available = map { it.lowercase() }.toSet()
    val preferred = listOf(
        "1816", // Cycling Speed and Cadence
        "1818", // Cycling Power Service
        "180d", // Heart Rate
        "1826"  // Fitness Machine Service
    )
    val hasTrainerService = available.any { it in setOf("1816", "1818", "1826") }
    return if (hasTrainerService) preferred else emptyList()
}
