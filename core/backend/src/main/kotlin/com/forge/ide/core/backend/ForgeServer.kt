package com.forge.ide.core.backend

import io.ktor.server.application.Application
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Backend HTTP/WS surface.
 *
 * Milestone M1 replaces the stub routes with the real fs / term / build /
 * agent methods defined by [com.forge.ide.core.backendapi.Methods]. Everything
 * is behind the loopback interface with a per-boot bearer token; the token is
 * minted in [ForgeServerService] and published through [ForgeRuntime].
 */
fun Application.forgeServerModule() {
    install(WebSockets)

    routing {
        get("/health") {
            call.respondText("forge-ok")
        }

        webSocket("/ws") {
            // Welcome frame carries the protocol version so a mismatched UI can
            // fail fast instead of hanging.
            val welcome = buildJsonObject {
                put("event", "welcome")
                put("protocol", 1)
            }
            send(Frame.Text(welcome.toString()))

            // Echo stub: proves the transport end-to-end. M1 dispatches frames
            // through the request/event router instead.
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val echo = buildJsonObject {
                        put("event", "echo")
                        put("data", frame.readText().take(4096))
                    }
                    send(Frame.Text(echo.toString()))
                }
            }
        }
    }
}

/** Tiny helper so future routes share one JSON style. */
internal fun jsonEvent(name: String, payload: JsonObject = JsonObject(emptyMap())): JsonObject =
    buildJsonObject {
        put("event", name)
        payload.forEach { (key, value) -> put(key, value) }
    }
