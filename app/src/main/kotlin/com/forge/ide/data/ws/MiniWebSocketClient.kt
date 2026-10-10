package com.forge.ide.data.ws

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Minimal WebSocket client over a plain [Socket].
 *
 * Handles the RFC 6455 opening handshake, text frames, pings/pongs and close.
 * Reconnection is the caller's job (see [com.forge.ide.data.TerminalController]),
 * because reconnect policy belongs with session semantics, not transport.
 */
class MiniWebSocketClient(
    private val host: String,
    private val port: Int,
    private val pathAndQuery: String = "/ws",
) {
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var readerThread: Thread? = null

    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    /** Emits once when the connection drops (server close, error, or [close]). */
    private val _closed = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val closed: SharedFlow<Unit> = _closed.asSharedFlow()

    @Volatile
    var isConnected: Boolean = false
        private set

    /** Opens the connection and starts the reader thread. Returns false if the handshake fails. */
    fun connect(timeoutMs: Int = 5_000): Boolean {
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = 0
            socket = s
            output = s.getOutputStream()

            val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val keyBase64 = Base64.getEncoder().encodeToString(key)
            val request = buildString {
                append("GET ").append(pathAndQuery).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append(':').append(port).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ").append(keyBase64).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("\r\n")
            }
            output?.write(request.toByteArray(Charsets.US_ASCII))
            output?.flush()

            val input = s.getInputStream()
            if (!readHandshakeResponse(input)) {
                close()
                return false
            }

            isConnected = true
            readerThread = thread(name = "forge-ws-reader") {
                readLoop(input)
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "connect failed: ${t.message}")
            close()
            isConnected = false
            false
        }
    }

    private fun readHandshakeResponse(input: InputStream): Boolean {
        val builder = StringBuilder()
        var previous = ' '
        while (builder.length < 8_192) {
            val b = input.read()
            if (b < 0) return false
            val c = b.toChar()
            builder.append(c)
            if (previous == '\n' && c == '\n') break
            previous = c
        }
        val response = builder.toString()
        val ok = response.startsWith("HTTP/1.1 101") && response.contains("Upgrade", ignoreCase = true)
        if (!ok) Log.w(TAG, "handshake rejected: ${response.lineSequence().firstOrNull()}")
        return ok
    }

    private fun readLoop(input: InputStream) {
        val reader = ServerFrameReader()
        val chunk = ByteArray(16 * 1024)
        try {
            while (isConnected) {
                val read = input.read(chunk)
                if (read < 0) break
                for (message in reader.feed(chunk, read)) {
                    when (message.opcode) {
                        WsCodec.OPCODE_TEXT -> _incoming.tryEmit(message.text)
                        WsCodec.OPCODE_BINARY -> _incoming.tryEmit(String(message.payload, Charsets.UTF_8))
                        WsCodec.OPCODE_PING -> sendRaw(WsCodec.encodePong(message.payload))
                        WsCodec.OPCODE_CLOSE -> {
                            isConnected = false
                            _closed.tryEmit(Unit)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "read loop ended: ${t.message}")
        } finally {
            isConnected = false
            _closed.tryEmit(Unit)
        }
    }

    fun sendText(text: String): Boolean {
        val stream = output ?: return false
        return try {
            stream.write(WsCodec.encodeText(text))
            stream.flush()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "send failed: ${t.message}")
            isConnected = false
            false
        }
    }

    private fun sendRaw(bytes: ByteArray) {
        try {
            output?.write(bytes)
            output?.flush()
        } catch (t: Throwable) {
            isConnected = false
        }
    }

    fun close() {
        isConnected = false
        try {
            if (socket?.isConnected == true) sendRaw(WsCodec.encodeClose("client closing"))
        } catch (_: Throwable) {
            // Best effort: the socket teardown below is what matters.
        }
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        output = null
        readerThread?.interrupt()
        readerThread = null
    }

    private companion object {
        const val TAG = "ForgeWS"
    }
}
