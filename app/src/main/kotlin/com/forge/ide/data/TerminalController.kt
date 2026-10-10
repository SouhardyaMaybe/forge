package com.forge.ide.data

import com.forge.ide.core.backend.ForgeRuntime
import com.forge.ide.core.backendapi.Event
import com.forge.ide.core.backendapi.Events
import com.forge.ide.core.backendapi.Methods
import com.forge.ide.core.backendapi.Request
import com.forge.ide.core.backendapi.Response
import com.forge.ide.core.backendapi.TermOpenParams
import com.forge.ide.core.backendapi.TermOpenResult
import com.forge.ide.data.ws.MiniWebSocketClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * Speaks the terminal protocol to the backend over a WebSocket.
 *
 * Owns one session id for the lifetime of the controller, re-opens the session
 * on reconnect (the backend keeps PTYs alive server-side), and exposes plain
 * callbacks so the UI stays declarative.
 */
class TerminalController(
    private val endpoint: StateFlow<ForgeRuntime.Endpoint?>,
    val sessionId: String = UUID.randomUUID().toString(),
) {
    var onOutput: ((String) -> Unit)? = null
    var onExit: ((Int) -> Unit)? = null
    var onConnectionChange: ((Boolean) -> Unit)? = null

    private var client: MiniWebSocketClient? = null
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Response>>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var nextId = 1L

    val connected: Boolean
        get() = client?.isConnected == true

    fun connect(scope: CoroutineScope) {
        if (client?.isConnected == true) return
        val address = endpoint.value ?: run {
            onConnectionChange?.invoke(false)
            return
        }

        val ws = MiniWebSocketClient(
            host = address.host,
            port = address.port,
            pathAndQuery = "/ws?token=${address.token}",
        )
        client = ws

        scope.launch {
            ws.incoming.collect { text -> handleFrame(text) }
        }
        scope.launch {
            ws.closed.collect { onConnectionChange?.invoke(false) }
        }

        if (!ws.connect()) {
            onConnectionChange?.invoke(false)
            return
        }
        onConnectionChange?.invoke(true)

        // Open (or re-attach to) this session server-side.
        scope.launch {
            try {
                request(
                    Methods.TERM_OPEN,
                    json.encodeToJsonElement(
                        TermOpenParams.serializer(),
                        TermOpenParams(sessionId = sessionId, rows = 24, cols = 80),
                    ).let { it as JsonObject },
                )
            } catch (t: Throwable) {
                onConnectionChange?.invoke(false)
            }
        }
    }

    private suspend fun request(method: String, params: JsonObject): Response? {
        val ws = client ?: return null
        val id = nextId++
        val deferred = CompletableDeferred<Response>()
        pending[id] = deferred
        val envelope = json.encodeToString(
            Request.serializer(),
            Request(id = id, method = method, params = params),
        )
        if (!ws.sendText(envelope)) {
            pending.remove(id)
            return null
        }
        return try {
            kotlinx.coroutines.withTimeout(10_000) { deferred.await() }
        } catch (t: Throwable) {
            null
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun handleFrame(text: String) {
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return
        val obj = element as? JsonObject ?: return

        if (obj.containsKey("id")) {
            // Response to one of our requests.
            val response = runCatching {
                json.decodeFromJsonElement(Response.serializer(), obj)
            }.getOrNull() ?: return
            pending.remove(response.id)?.complete(response)
            return
        }

        // Event: an Event has "name" and "params".
        val event = runCatching { json.decodeFromJsonElement(Event.serializer(), obj) }.getOrNull() ?: return
        if (event.name == "welcome") return
        if (event.name == Events.TERM_OUTPUT) {
            val session = event.params["sessionId"]?.toString()?.trim('"')
            if (session == sessionId) {
                onOutput?.invoke(event.params["data"]?.toString()?.let { unescapeJson(it) } ?: "")
            }
        } else if (event.name == Events.TERM_EXIT) {
            val session = event.params["sessionId"]?.toString()?.trim('"')
            if (session == sessionId) {
                onExit?.invoke(event.params["exitCode"]?.toString()?.toIntOrNull() ?: -1)
            }
        }
    }

    fun sendInput(text: String) {
        val ws = client ?: return
        val envelope = json.encodeToString(
            Request.serializer(),
            Request(
                id = nextId++,
                method = Methods.TERM_INPUT,
                params = buildJsonObject {
                    put("sessionId", sessionId)
                    put("data", text)
                },
            ),
        )
        ws.sendText(envelope)
    }

    fun resize(rows: Int, cols: Int) {
        val ws = client ?: return
        val envelope = json.encodeToString(
            Request.serializer(),
            Request(
                id = nextId++,
                method = Methods.TERM_RESIZE,
                params = buildJsonObject {
                    put("sessionId", sessionId)
                    put("rows", rows)
                    put("cols", cols)
                },
            ),
        )
        ws.sendText(envelope)
    }

    fun open(cwd: String) {
        val ws = client ?: return
        val envelope = json.encodeToString(
            Request.serializer(),
            Request(
                id = nextId++,
                method = Methods.TERM_OPEN,
                params = json.encodeToJsonElement(
                    TermOpenParams.serializer(),
                    TermOpenParams(sessionId = sessionId, cwd = cwd),
                ).let { it as JsonObject },
            ),
        )
        ws.sendText(envelope)
    }

    fun disconnect() {
        client?.close()
        client = null
        onConnectionChange?.invoke(false)
    }
}

/** JSON string-unescaping without pulling in extra machinery. */
internal fun unescapeJson(raw: String): String {
    val inner = raw.removePrefix("\"").removeSuffix("\"")
    if (!inner.contains('\\')) return inner
    val out = StringBuilder(inner.length)
    var i = 0
    while (i < inner.length) {
        val c = inner[i]
        if (c == '\\' && i + 1 < inner.length) {
            when (val next = inner[i + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'u' -> {
                    if (i + 5 < inner.length + 1 && i + 6 <= inner.length) {
                        val hex = inner.substring(i + 2, i + 6)
                        out.append(hex.toInt(16).toChar())
                        i += 4
                    }
                }
                else -> out.append(next)
            }
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}
