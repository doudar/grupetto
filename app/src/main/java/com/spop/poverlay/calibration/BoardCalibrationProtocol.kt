package com.spop.poverlay.calibration

/** V1Native.getLxxProgramCommand captures CURRENT AD; there is no numeric AD argument. */
internal object BoardCalibrationProtocol {
    const val rawAd = "F5,4A,3F,F6"
    const val cadence = "F5,41,36,F6"
    fun readPoint(index: Int): String {
        require(index in 0..30)
        return packet(0xF7, index)
    }
    fun capturePoint(index: Int): String {
        require(index in 1..29) { "Factory endpoints cannot be written" }
        return packet(0xFC, index)
    }
    private fun packet(opcode: Int, index: Int) = listOf(opcode, index, (opcode + index) and 255, 0xF6)
        .joinToString(",") { it.toString(16).uppercase().padStart(2, '0') }
    fun decode(hex: String?, opcode: Int, digits: Int): Int? {
        if (hex == null || hex.length > 128) return null
        val tokens = hex.trim().split(Regex("[\\s,]+"))
        if (tokens.size != digits + 5) return null
        val bytes = tokens.map { token ->
            if (token.length != 2) return null
            token.toIntOrNull(16)?.takeIf { it in 0..255 } ?: return null
        }
        if (bytes[0] != 0xF1 || bytes[1] != opcode || bytes[2] != digits || bytes.last() != 0xF6 ||
            (bytes.dropLast(2).sum() and 255) != bytes[bytes.lastIndex - 1]) return null
        var value = 0
        for (index in (3 until 3 + digits).reversed()) {
            val digit = bytes[index] - 0x30
            if (digit !in 0..9) return null
            value = value * 10 + digit
        }
        return value
    }
}

internal data class BoardPosition(val ad: Int, val rpm: Int)
internal interface CalibrationBoardTransport : AutoCloseable {
    suspend fun position(): BoardPosition
    suspend fun readTable(): List<Int>
    suspend fun capturePoint(index: Int)
}

internal data class BoardWriteJournal(val original: List<Int>, val target: List<Int>, val expected: List<Int>,
    val frameSerial: String, val pendingIndex: Int? = null, val complete: Boolean = false, val restoring: Boolean = false)

/** A durable journal is mandatory BEFORE the first command, including interrupted writes. */
internal interface BoardJournalStore {
    fun load(): BoardWriteJournal?
    fun save(journal: BoardWriteJournal)
}
