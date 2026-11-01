package com.spop.poverlay.sensor.v1legacy

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Messenger/Handler transport; no Peloton service or hardware is contacted. */
@RunWith(AndroidJUnit4::class)
class SensorDataClientTest {
    private data class Request(val what: Int, val command: String?, val id: String?, val replyTo: Messenger) {
        fun reply(hex: String, event: Int = 14) {
            replyTo.send(Message.obtain(null, event).apply {
                data = Bundle().apply {
                    putString("requestId", id)
                    putString("responseHexString", hex)
                    // Deliberately wrong: production must validate the raw packet, not trust this default.
                    putFloat("data", 0f)
                }
            })
        }
    }

    private class Service : AutoCloseable {
        val requests = Channel<Request>(Channel.UNLIMITED)
        private val handler = Handler(Looper.getMainLooper()) { message ->
            requests.trySend(Request(message.what, message.data.getString("command"),
                message.data.getString("requestId"), message.replyTo))
            true
        }
        val binder = Messenger(handler).binder
        suspend fun next() = withTimeout(3_000) { requests.receive() }
        override fun close() {
            handler.removeCallbacksAndMessages(null)
            requests.close()
        }
    }

    @Test
    fun usesVerifiedReadOnlyMessengerContract() = runBlocking {
        Service().use { service ->
            SensorDataClient(service.binder).use { client ->
                val result = async { client.read(SensorDataRead.Power) }
                val request = service.next()
                assertEquals(12, request.what)
                assertEquals("F5,44,39,F6", request.command)
                assertTrue(!request.id.isNullOrBlank())
                request.reply("F1 44 05 35 30 35 32 30 36 F6")
                assertEquals(250.5f, result.await())
            }
        }
    }

    @Test
    fun missingReplyTimesOutAndLateReplyCannotSatisfyNextRequest() = runBlocking {
        Service().use { service ->
            SensorDataClient(service.binder, requestTimeoutMillis = 300).use { client ->
                val first = async { client.read(SensorDataRead.Cadence) }
                val oldRequest = service.next()
                assertNull(first.await())
                val second = async { client.read(SensorDataRead.Cadence) }
                val request = service.next()
                assertNotEquals(oldRequest.id, request.id)
                oldRequest.reply("F1 41 03 37 38 30 D4 F6")
                request.reply("F1 41 03 37 38 30 D4 F6", event = 13)
                request.reply("F1 41 03 30 30 30 C5 F6")
                assertEquals(0f, second.await())
            }
        }
    }

    @Test
    fun timeoutMarkerAndWrongOpcodeAreNotEmittedAsZero() = runBlocking {
        Service().use { service ->
            SensorDataClient(service.binder).use { client ->
                for (hex in listOf("TIME_OUT", "F1 49 03 31 33 30 D1 F6")) {
                    val result = async { client.read(SensorDataRead.Cadence) }
                    service.next().reply(hex)
                    assertNull(result.await())
                }
            }
        }
    }

    @Test
    fun cancelledRequestDoesNotHoldUpNextRead() = runBlocking {
        Service().use { service ->
            SensorDataClient(service.binder).use { client ->
                val first = async { client.read(SensorDataRead.Resistance) }
                val oldRequest = service.next()
                first.cancelAndJoin()
                val next = async { client.read(SensorDataRead.Resistance) }
                val request = service.next()
                oldRequest.reply("F1 49 03 30 30 31 CE F6")
                request.reply("F1 49 03 31 33 30 D1 F6")
                assertEquals(31f, next.await())
            }
        }
    }

    @Test
    fun sharedPollerPublishesThreeMetricsAndStopsOnCancellation() = runBlocking {
        Service().use { service ->
            SensorDataSensor(service.binder).use { sensor ->
                val cadence = async { sensor.cadence.first() }
                val power = async { sensor.power.first() }
                val resistance = async { sensor.resistance.first() }
                val polling = launch { sensor.run() }
                try {
                    val reads = listOf(
                        "F5,41,36,F6" to "F1 41 03 30 30 30 C5 F6",
                        "F5,44,39,F6" to "F1 44 05 35 30 35 32 30 36 F6",
                        "F5,49,3E,F6" to "F1 49 03 31 33 30 D1 F6",
                    )
                    for ((command, response) in reads) {
                        val request = service.next()
                        assertEquals(12, request.what)
                        assertEquals(command, request.command)
                        request.reply(response)
                    }
                    withTimeout(3_000) {
                        assertEquals(0f, cadence.await())
                        assertEquals(250.5f, power.await())
                        assertEquals(31f, resistance.await())
                    }
                } finally {
                    polling.cancelAndJoin()
                }
                assertNull(withTimeoutOrNull(400) { service.requests.receive() })
            }
        }
    }
}
