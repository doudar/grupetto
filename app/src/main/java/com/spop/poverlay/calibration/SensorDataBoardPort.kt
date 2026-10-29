package com.spop.poverlay.calibration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.*
import com.spop.poverlay.sensor.BoundBikeService
import com.spop.poverlay.sensor.bindBikeService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Separate from normal telemetry: only this opt-in stopped-bike writer can send FC.
 * It never stops Affernet, changes sensor mode, or invalidates factory calibration.
 */
internal class SensorDataBoardPort private constructor(private val binding: BoundBikeService) : CalibrationBoardTransport {
    private val remote = Messenger(binding.binder)
    private val replies = Channel<Pair<String?, String?>>(Channel.BUFFERED)
    private val session = UUID.randomUUID().toString()
    private var sequence = 0
    private var closed = false
    private val handler = Handler(Looper.getMainLooper()) { message ->
        if (!closed && message.what == 14) replies.trySend(message.data.getString("requestId") to message.data.getString("responseHexString"))
        true
    }
    private val receiver = Messenger(handler)
    private suspend fun exchange(command: String): String? {
        check(!closed)
        val id = "$session:${sequence++}"
        remote.send(Message.obtain(null, 12).apply {
            data = Bundle().apply { putString("command", command); putString("requestId", id) }
            replyTo = receiver
        })
        val response = withTimeoutOrNull(1500) {
            var response = replies.receive()
            while (response.first != id) response = replies.receive()
            response.second
        }
        delay(50)
        return response
    }
    override suspend fun position(): BoardPosition {
        val rpm = BoardCalibrationProtocol.decode(exchange(BoardCalibrationProtocol.cadence), 0x41, 3)
        val ad = BoardCalibrationProtocol.decode(exchange(BoardCalibrationProtocol.rawAd), 0x4A, 4)
        check(rpm != null && ad != null) {
            "Sensor connection unavailable. Affernet must release it before board setup; no write was sent."
        }
        return BoardPosition(ad, rpm)
    }
    override suspend fun readTable(): List<Int> = (0..30).map { index ->
        checkNotNull(BoardCalibrationProtocol.decode(exchange(BoardCalibrationProtocol.readPoint(index)), 0xF7, 4)) {
            "Board point $index could not be read. Session stopped."
        }
    }
    override suspend fun capturePoint(index: Int) { exchange(BoardCalibrationProtocol.capturePoint(index)) }
    override fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        replies.close()
        binding.close()
    }
    companion object {
        suspend fun connect(context: Context): SensorDataBoardPort {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo("com.peloton.service.SensorData", 0)
            check(info.versionName == "2.8.3016") { "Board setup needs the verified SensorData 2.8.3016 transport." }
            val binding = withTimeout(5000) { bindBikeService(context) {
                Intent("android.intent.action.peloton.SensorData")
                    .setComponent(ComponentName("com.peloton.service.SensorData", "com.peloton.sensor.SensorService"))
                    .addCategory("com.peloton.sensor.category.BIKE")
            } }
            try {
                check(binding.binder.interfaceDescriptor == "android.os.IMessenger") { "Unsupported sensor interface." }
                return SensorDataBoardPort(binding)
            } catch (e: Exception) { binding.close(); throw e }
        }
    }
}
