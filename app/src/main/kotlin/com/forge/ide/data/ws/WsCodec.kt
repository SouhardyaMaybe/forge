package com.forge.ide.data.ws

/**
 * Minimal RFC 6455 WebSocket frame codec.
 *
 * The app cannot afford an HTTP client library: every dependency costs APK
 * size, dex memory on CI, and resident memory on a 3 GB phone. This codec is
 * therefore part of the app itself and is unit-tested on the JVM.
 *
 * Client → server frames are masked, as the RFC requires. Server → client
 * frames are unmasked. Continuation frames are reassembled by
 * [ServerFrameReader].
 */
object WsCodec {

    const val OPCODE_CONTINUATION = 0x0
    const val OPCODE_TEXT = 0x1
    const val OPCODE_BINARY = 0x2
    const val OPCODE_CLOSE = 0x8
    const val OPCODE_PING = 0x9
    const val OPCODE_PONG = 0xA

    /** Builds a masked text frame from [payload]. */
    fun encodeText(payload: String): ByteArray {
        val data = payload.toByteArray(Charsets.UTF_8)
        return encodeMasked(OPCODE_TEXT, data)
    }

    /** Builds a masked close frame (status 1000) with an optional reason. */
    fun encodeClose(reason: String = ""): ByteArray {
        val reasonBytes = reason.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(2 + reasonBytes.size)
        payload[0] = 0x03.toByte() // 1000 >> 8
        payload[1] = 0xE8.toByte() // 1000 & 0xff
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.size)
        return encodeMasked(OPCODE_CLOSE, payload)
    }

    /** Builds a masked pong echoing [payload]. */
    fun encodePong(payload: ByteArray): ByteArray = encodeMasked(OPCODE_PONG, payload)

    private fun encodeMasked(opcode: Int, data: ByteArray): ByteArray {
        val mask = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
        val masked = ByteArray(data.size) { i -> (data[i].toInt() xor mask[i % 4].toInt()).toByte() }
        val lengthField = lengthFieldBytes(data.size, masked = true)
        val out = java.io.ByteArrayOutputStream(1 + lengthField.size + 4 + masked.size)
        out.write((0x80 or opcode).toByte())
        out.write(lengthField)
        out.write(mask)
        out.write(masked)
        return out.toByteArray()
    }

    /**
     * The full payload-length field: the 7-bit indicator byte plus any
     * extended-length bytes. Writing only the indicator (a bug the tests
     * caught) produces malformed frames for payloads of 126 bytes or more.
     */
    internal fun lengthFieldBytes(length: Int, masked: Boolean): ByteArray {
        require(length <= 0xFFFF) { "payload too large for this codec" }
        val maskBit = if (masked) 0x80 else 0x00
        return when {
            length < 126 -> byteArrayOf((maskBit or length).toByte())

            length <= 0xFFFF -> byteArrayOf(
                (maskBit or 126).toByte(),
                ((length shr 8) and 0xFF).toByte(),
                (length and 0xFF).toByte(),
            )

            else -> {
                val bytes = ByteArray(9)
                bytes[0] = (maskBit or 127).toByte()
                var value = length.toLong()
                for (i in 8 downTo 1) {
                    bytes[i] = (value and 0xFF).toByte()
                    value = value shr 8
                }
                bytes
            }
        }
    }

    /** Number of extra bytes used for the extended length, after the header byte. */
    internal fun extendedLengthBytes(firstLengthByte: Int): Int = when (firstLengthByte and 0x7F) {
        126 -> 2
        127 -> 8
        else -> 0
    }
}

/** One complete message reassembled from one or more frames. */
data class ServerMessage(val opcode: Int, val payload: ByteArray) {
    val text: String get() = String(payload, Charsets.UTF_8)

    override fun equals(other: Any?): Boolean =
        other is ServerMessage && opcode == other.opcode && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * opcode + payload.contentHashCode()
}

/**
 * Incremental reader for server→client frames.
 *
 * Feed bytes with [feed]; complete messages are returned. Incomplete tails
 * stay buffered, so callers can hand over arbitrary socket chunks.
 */
class ServerFrameReader {
    private val buffer = java.io.ByteArrayOutputStream()

    fun feed(chunk: ByteArray, length: Int = chunk.size): List<ServerMessage> {
        buffer.write(chunk, 0, length)
        return drain()
    }

    private fun drain(): List<ServerMessage> {
        val messages = mutableListOf<ServerMessage>()
        while (true) {
            val bytes = buffer.toByteArray()
            if (bytes.size < 2) break

            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            val fin = (first and 0x80) != 0
            val opcode = first and 0x0F
            val masked = (second and 0x80) != 0 // servers must not mask
            var payloadLength = second and 0x7F
            var offset = 2

            when (payloadLength) {
                126 -> {
                    if (bytes.size < offset + 2) break
                    payloadLength = ((bytes[offset].toInt() and 0xFF) shl 8) or
                        (bytes[offset + 1].toInt() and 0xFF)
                    offset += 2
                }
                127 -> {
                    if (bytes.size < offset + 8) break
                    var length = 0L
                    for (i in 0 until 8) {
                        length = (length shl 8) or (bytes[offset + i].toLong() and 0xFF)
                    }
                    require(length <= Int.MAX_VALUE.toLong()) { "frame too large" }
                    payloadLength = length.toInt()
                    offset += 8
                }
            }

            if (masked) {
                offset += 4 // should not happen; skip the mask defensively
            }
            if (bytes.size < offset + payloadLength) break

            val payload = bytes.copyOfRange(offset, offset + payloadLength)
            // Drop the consumed bytes from the front of the buffer.
            val remaining = bytes.copyOfRange(offset + payloadLength, bytes.size)
            buffer.reset()
            buffer.write(remaining)

            if (opcode == WsCodec.OPCODE_PING || opcode == WsCodec.OPCODE_PONG ||
                opcode == WsCodec.OPCODE_CLOSE ||
                (fin && opcode != WsCodec.OPCODE_CONTINUATION)
            ) {
                messages += ServerMessage(opcode, payload)
                if (opcode == WsCodec.OPCODE_CONTINUATION) continue
            } else {
                // Continuation handling: accumulate until FIN.
                if (fin) {
                    val accumulated = continuationBuffer?.plus(payload) ?: payload
                    messages += ServerMessage(continuationOpcode, accumulated)
                    continuationBuffer = null
                    continuationOpcode = 0
                } else {
                    continuationBuffer = (continuationBuffer ?: ByteArray(0)) + payload
                    if (continuationOpcode == 0) continuationOpcode = opcode
                }
            }
        }
        return messages
    }

    private var continuationBuffer: ByteArray? = null
    private var continuationOpcode: Int = 0
}
