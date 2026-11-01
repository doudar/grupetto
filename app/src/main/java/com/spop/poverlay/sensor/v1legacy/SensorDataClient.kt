package com.spop.poverlay.sensor.v1legacy

import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One outstanding request at a time, correlated by ID so late replies cannot become new samples. */
internal class SensorDataClient(
    binder: IBinder,
    private val requestTimeoutMillis: Long = 1_500L,
) : AutoCloseable {
    private data class Response(val requestId: String?, val hex: String?)
    private val remote = Messenger(binder)
    private val replies = Channel<Response>(Channel.BUFFERED)
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val sessionId = UUID.randomUUID().toString()
    private var sequence = 0L
    private val handler = Handler(Looper.getMainLooper()) { message ->
        if (!closed.get() && message.what == 14) {
            val data = message.data
            replies.trySend(Response(data.getString("requestId"), data.getString("responseHexString")))
        }
        true
    }
    private val replyMessenger = Messenger(handler)

    suspend fun read(read: SensorDataRead): Float? = mutex.withLock {
        check(!closed.get()) { "SensorData client is closed" }
        val requestId = "$sessionId:${sequence++}"
        val message = Message.obtain(null, 12).apply {
            data = Bundle().apply {
                putString("command", read.command)
                putString("requestId", requestId)
            }
            replyTo = replyMessenger
        }
        remote.send(message)
        withTimeoutOrNull(requestTimeoutMillis) {
            var response = replies.receive()
            while (response.requestId != requestId) response = replies.receive()
            read.decodeResponse(response.hex)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            handler.removeCallbacksAndMessages(null)
            replies.close()
        }
    }
}
