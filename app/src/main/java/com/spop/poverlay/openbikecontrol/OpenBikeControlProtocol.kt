package com.spop.poverlay.openbikecontrol

import java.util.UUID

/**
 * OpenBikeControl: an open protocol for remotes (shifters, buttons, BikeControl) that
 * control trainer apps. Grupetto is the app side: it connects to a remote over Wi-Fi
 * (mDNS + TCP) or Bluetooth and receives button presses.
 * Spec: https://github.com/OpenBikeControl/openbikecontrol-protocol
 */
object OpenBikeControl {
    const val MdnsServiceType = "_openbikecontrol._tcp"
    val Service: UUID = UUID.fromString("d273f680-d548-419d-b9d1-fa0472345229")
    val ButtonState: UUID = UUID.fromString("d273f681-d548-419d-b9d1-fa0472345229")
    val AppInfo: UUID = UUID.fromString("d273f683-d548-419d-b9d1-fa0472345229")
    val Cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val TypeButtonState = 0x01
    const val TypeDeviceStatus = 0x02
    const val TypeAppInfo = 0x04

    const val ShiftUp = 0x01
    const val ShiftDown = 0x02
    const val IncreaseDifficulty = 0x30
    const val DecreaseDifficulty = 0x31

    /** The buttons Grupetto acts on, announced in App Info so the remote only offers these. */
    val SupportedButtons = listOf(ShiftUp, ShiftDown, IncreaseDifficulty, DecreaseDifficulty)

    const val AppId = "grupetto"

    /** Shift direction for a button, or null for buttons Grupetto ignores.
     * Grupetto has no virtual gears: a shift is one step of the current mode
     * (watts in ERG, resistance in Sim and Manual), the same as the on-screen shifters.
     * "Difficulty" buttons do the same thing, so either mapping on the remote works. */
    fun shiftDirection(button: Int): Int? = when (button) {
        ShiftUp, IncreaseDifficulty -> 1
        ShiftDown, DecreaseDifficulty -> -1
        else -> null
    }

    /** App Info (type 0x04). BikeControl treats the app as connected once it arrives. */
    fun encodeAppInfo(appVersion: String, buttons: List<Int> = SupportedButtons, appId: String = AppId): ByteArray {
        val id = appId.toByteArray(Charsets.UTF_8).take(32)
        val version = appVersion.toByteArray(Charsets.UTF_8).take(32)
        val ids = buttons.take(255)
        return (listOf(TypeAppInfo, 0x01, id.size).map(Int::toByte) + id +
            version.size.toByte() + version + ids.size.toByte() + ids.map(Int::toByte)).toByteArray()
    }
}

sealed class ObcMessage {
    data class Buttons(val states: List<Pair<Int, Int>>) : ObcMessage()
    data class DeviceStatus(val battery: Int?, val connected: Boolean) : ObcMessage()
}

/** One complete message, as delivered by a BLE notification. */
fun decodeObcMessage(bytes: ByteArray): ObcMessage? {
    if (bytes.isEmpty()) return null
    val u = bytes.map { it.toInt() and 255 }
    return when (u[0]) {
        OpenBikeControl.TypeButtonState ->
            if (u.size % 2 == 1) ObcMessage.Buttons((1 until u.size step 2).map { u[it] to u[it + 1] }) else null
        OpenBikeControl.TypeDeviceStatus ->
            if (u.size == 3) ObcMessage.DeviceStatus(u[1].takeIf { it <= 100 }, u[2] == 1) else null
        else -> null
    }
}

/**
 * Splits the TCP byte stream into messages. The protocol has no length field, and a remote
 * that writes a press and its release back to back (BikeControl does) can arrive as one
 * read: `01 01 01 01 01 00`. Every message has a fixed type byte and an odd length
 * (`01` + id/state pairs, or `02 battery connected`), so the buffer is split into the
 * shortest valid messages. A buffer that can't be split yet waits for the next read,
 * and is dropped once it is older than [staleMs] or longer than [maxBytes].
 */
class ObcStreamDecoder(private val staleMs: Long = 500, private val maxBytes: Int = 64) {
    private var pending = ByteArray(0)
    private var pendingSince = 0L

    fun reset() { pending = ByteArray(0) }

    fun offer(bytes: ByteArray, now: Long): List<ObcMessage> {
        if (pending.isNotEmpty() && now - pendingSince > staleMs) pending = ByteArray(0)
        if (pending.isEmpty()) pendingSince = now
        val buffer = pending + bytes
        val split = split(buffer)
        if (split != null) { pending = ByteArray(0); return split }
        val first = buffer.firstOrNull()?.toInt()?.and(255)
        pending = if (buffer.size > maxBytes || (first != OpenBikeControl.TypeButtonState &&
                first != OpenBikeControl.TypeDeviceStatus)) ByteArray(0) else buffer
        return emptyList()
    }

    private fun split(buffer: ByteArray): List<ObcMessage>? {
        // A lone `01` is a button state naming no buttons; it changes nothing.
        if (buffer.size == 1 && buffer[0].toInt() == OpenBikeControl.TypeButtonState) return listOf(ObcMessage.Buttons(emptyList()))
        val memo = HashMap<Int, List<ObcMessage>?>()
        fun from(start: Int): List<ObcMessage>? {
            if (start == buffer.size) return emptyList()
            if (memo.containsKey(start)) return memo[start]
            val lengths = when (buffer[start].toInt() and 255) {
                OpenBikeControl.TypeButtonState -> (3..buffer.size - start step 2)
                OpenBikeControl.TypeDeviceStatus -> (3..3)
                else -> IntRange.EMPTY
            }
            var result: List<ObcMessage>? = null
            for (length in lengths) {
                if (start + length > buffer.size) break
                val message = decodeObcMessage(buffer.copyOfRange(start, start + length)) ?: continue
                val rest = from(start + length) ?: continue
                result = listOf(message) + rest
                break
            }
            memo[start] = result
            return result
        }
        return from(0)
    }
}

/** Acts once per press: a held button, or a repeated pressed state, shifts once. */
class ObcButtonTracker(private val onShift: (direction: Int, button: Int) -> Unit) {
    private val pressed = HashSet<Int>()

    fun reset() = pressed.clear()

    fun handle(message: ObcMessage) {
        if (message !is ObcMessage.Buttons) return
        for ((button, state) in message.states) {
            if (state == 0) { pressed.remove(button); continue }
            if (!pressed.add(button)) continue
            OpenBikeControl.shiftDirection(button)?.let { onShift(it, button) }
        }
    }
}
