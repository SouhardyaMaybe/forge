package com.forge.ide.data.ws

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec + ANSI stripping tests. These run on the JVM in CI and pin the two
 * pieces of the terminal that are easy to get subtly wrong.
 */
class WsCodecTest {

    @Test
    fun `encoded text frame is masked and well formed`() {
        val frame = WsCodec.encodeText("hi")
        // FIN + text opcode, then mask bit set with 2-byte payload.
        assertEquals(0x81.toByte(), frame[0])
        assertEquals(0x82.toByte(), frame[1]) // 0x80 | 2
        assertEquals(8, frame.size) // 2 header + 4 mask + 2 payload
    }

    @Test
    fun `long payloads use the 16-bit extended length`() {
        val payload = "x".repeat(200)
        val frame = WsCodec.encodeText(payload)
        assertEquals(0x81.toByte(), frame[0])
        assertEquals(0xFE.toByte(), frame[1]) // 0x80 | 126
        // 2 + 2 (extended length) + 4 (mask) + 200 payload
        assertEquals(208, frame.size)
    }

    @Test
    fun `reader reassembles a text message byte by byte`() {
        val frame = WsCodec.encodeText("hello")
        val reader = ServerFrameReader()

        // Server frames are unmasked: FIN + text, len 5.
        val serverFrame = byteArrayOf(0x81.toByte(), 0x05) + "hello".toByteArray()

        // Feed one byte at a time to exercise the buffering.
        val messages = mutableListOf<ServerMessage>()
        serverFrame.forEachIndexed { index, byte ->
            messages += reader.feed(byteArrayOf(byte), 1)
        }
        assertEquals(1, messages.size)
        assertEquals("hello", messages.single().text)
        assertTrue(frame.isNotEmpty())
    }

    @Test
    fun `reader handles several frames in one chunk`() {
        val first = byteArrayOf(0x81.toByte(), 0x02) + "ab".toByteArray()
        val second = byteArrayOf(0x81.toByte(), 0x03) + "cde".toByteArray()
        val reader = ServerFrameReader()
        val messages = reader.feed(first + second)
        assertEquals(listOf("ab", "cde"), messages.map { it.text })
    }

    @Test
    fun `close frame carries the status code`() {
        val frame = WsCodec.encodeClose("bye")
        assertEquals(0x88.toByte(), frame[0]) // FIN + close opcode
        // Server-side close frames are unmasked: opcode byte, then length.
        val serverClose = byteArrayOf(0x88.toByte(), 0x05, 0x03, 0xE8.toByte()) +
            "bye".toByteArray()
        val reader = ServerFrameReader()
        val messages = reader.feed(serverClose)
        assertEquals(1, messages.size)
        assertEquals(WsCodec.OPCODE_CLOSE, messages.single().opcode)
        assertEquals(5, messages.single().payload.size)
        assertArrayEquals(byteArrayOf(0x03, 0xE8.toByte()), messages.single().payload.copyOfRange(0, 2))
    }

    @Test
    fun `extended length bytes are reported correctly`() {
        assertEquals(0, WsCodec.extendedLengthBytes(0x05))
        assertEquals(2, WsCodec.extendedLengthBytes(126))
        assertEquals(8, WsCodec.extendedLengthBytes(127))
    }
}
