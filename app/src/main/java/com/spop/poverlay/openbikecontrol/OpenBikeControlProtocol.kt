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
    /** The decoder dropped bytes it couldn't place; button states may have been lost. */
    object Resync : ObcMessage()
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
 * Splits the TCP byte stream into messages. The protocol has no length field. A remote
 * that writes a press and its release back to back (BikeControl does) can arrive as one
 * read, `01 01 01 01 01 00`, and TCP may also split a message across reads.
 *
 * Every message has a type byte and an odd length (`01` + id/state pairs, or
 * `02 battery connected`). Remotes send one message per state change, so a read is first
 * split into one-button messages. Only if that fails is a multi-button message considered.
 * When the bytes fit both readings, for example `01 30 01 01 01` (two buttons, or one
 * press plus the start of the next message), the read is held: more bytes settle it,
 * and if none come within [settleMs], [flush] reads it as a multi-button message.
 * A button message must be plausible: no button twice, and Grupetto's digital buttons
 * only pressed (1) or released (0).
 *
 * Bytes that can't be placed, or a fragment older than [staleMs] or longer than [maxBytes],
 * are dropped and reported as [ObcMessage.Resync]. A button-state message holds at most
 * [maxButtons] buttons, which keeps splitting linear in the read size.
 */
class ObcStreamDecoder(
    private val staleMs: Long = 500,
    private val maxBytes: Int = 64,
    private val maxButtons: Int = 16,
    val settleMs: Long = 40
) {
    private var pending = ByteArray(0)
    private var pendingSince = 0L
    private var ambiguous = false

    fun reset() { pending = ByteArray(0); ambiguous = false }

    /** How long the reader may wait for more bytes before calling [flush]; null = no limit. */
    fun waitMs(now: Long): Long? = when {
        pending.isEmpty() -> null
        ambiguous -> settleMs
        else -> (staleMs - (now - pendingSince)).coerceIn(1, staleMs)
    }

    fun offer(bytes: ByteArray, now: Long): List<ObcMessage> {
        val out = mutableListOf<ObcMessage>()
        if (pending.isNotEmpty() && !ambiguous && now - pendingSince > staleMs) { pending = ByteArray(0); out += ObcMessage.Resync }
        if (pending.isEmpty()) pendingSince = now
        ambiguous = false
        val buffer = pending + bytes
        pending = ByteArray(0)
        split(buffer, 1, allowTail = false)?.let { out += it.first; return out }
        val multi = split(buffer, maxButtons, allowTail = false)
        val singleTail = split(buffer, 1, allowTail = true)
        when {
            multi != null && singleTail != null && buffer.size <= maxBytes -> { pending = buffer; ambiguous = true }
            multi != null -> out += multi.first
            singleTail != null -> keep(buffer, singleTail, now, out)
            else -> split(buffer, maxButtons, allowTail = true)?.let { keep(buffer, it, now, out) } ?: run {
                val first = buffer.firstOrNull()?.toInt()?.and(255)
                if (buffer.size > maxBytes || (first != OpenBikeControl.TypeButtonState && first != OpenBikeControl.TypeDeviceStatus))
                    out += ObcMessage.Resync
                else pending = buffer
            }
        }
        return out
    }

    /** No more bytes arrived in time: settle a held read, or drop a stale fragment. */
    fun flush(now: Long): List<ObcMessage> {
        if (ambiguous) {
            ambiguous = false
            val held = pending
            pending = ByteArray(0)
            return split(held, maxButtons, allowTail = false)?.first ?: listOf(ObcMessage.Resync)
        }
        if (pending.isNotEmpty() && now - pendingSince > staleMs) { pending = ByteArray(0); return listOf(ObcMessage.Resync) }
        return emptyList()
    }

    private fun keep(buffer: ByteArray, parsed: Pair<List<ObcMessage>, Int>, now: Long, out: MutableList<ObcMessage>) {
        out += parsed.first
        pending = buffer.copyOfRange(parsed.second, buffer.size)
        if (parsed.second > 0) pendingSince = now
    }

    private fun isType(byte: Byte) = (byte.toInt() and 255).let {
        it == OpenBikeControl.TypeButtonState || it == OpenBikeControl.TypeDeviceStatus
    }

    private fun plausible(message: ObcMessage): Boolean {
        if (message !is ObcMessage.Buttons) return true
        val ids = message.states.map { it.first }
        return ids.size == ids.toSet().size &&
            message.states.none { (id, state) -> id in OpenBikeControl.SupportedButtons && state > 1 }
    }

    /** Messages of at most [buttons] buttons and the number of bytes they use. With
     * [allowTail], up to two trailing bytes that start a message are left unused. */
    private fun split(buffer: ByteArray, buttons: Int, allowTail: Boolean): Pair<List<ObcMessage>, Int>? {
        val memo = HashMap<Int, Pair<List<ObcMessage>, Int>?>()
        fun from(start: Int): Pair<List<ObcMessage>, Int>? {
            if (start == buffer.size) return emptyList<ObcMessage>() to start
            if (memo.containsKey(start)) return memo[start]
            var result: Pair<List<ObcMessage>, Int>? = null
            if (allowTail && buffer.size - start < 3 && isType(buffer[start])) result = emptyList<ObcMessage>() to start
            else {
                val lengths = when (buffer[start].toInt() and 255) {
                    OpenBikeControl.TypeButtonState -> (3..minOf(buffer.size - start, 1 + 2 * buttons) step 2)
                    OpenBikeControl.TypeDeviceStatus -> (3..3)
                    else -> IntRange.EMPTY
                }
                for (length in lengths) {
                    if (start + length > buffer.size) break
                    val message = decodeObcMessage(buffer.copyOfRange(start, start + length)) ?: continue
                    if (!plausible(message)) continue
                    val rest = from(start + length) ?: continue
                    result = (listOf(message) + rest.first) to rest.second
                    break
                }
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
        if (message is ObcMessage.Resync) { reset(); return }
        if (message !is ObcMessage.Buttons) return
        for ((button, state) in message.states) {
            if (state == 0) { pressed.remove(button); continue }
            if (!pressed.add(button)) continue
            OpenBikeControl.shiftDirection(button)?.let { onShift(it, button) }
        }
    }
}
