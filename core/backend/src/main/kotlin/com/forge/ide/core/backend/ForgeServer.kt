package com.forge.ide.core.backend

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import com.forge.ide.core.backendapi.Response
/**
 * Backend HTTP/WS surface.
 *
 * `GET /health`      → liveness probe (used by the UI and the watchdog)
 * `WS   /ws`         → the protocol connection: requests in, events out
 *
 * Events are broadcast to every connected client; per-session routing arrives
 * with the agent host in M3, when more than one panel needs private streams.
 */
fun Application.forgeServerModule(services: BackendServices) {
    install(WebSockets)

    routing {
        get("/health") {
            call.respondText("forge-ok")
        }

        webSocket("/ws") {
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

            // Welcome frame carries the protocol version so a mismatched UI can
            // fail fast instead of hanging.
            send(
                Frame.Text(
                    json.encodeToString(
                        com.forge.ide.core.backendapi.Event.serializer(),
                        com.forge.ide.core.backendapi.Event(
                            seq = services.eventBus.currentSeq(),
                            name = "welcome",
                        ),
                    ),
                ),
            )

            val pump = launch {
                services.eventBus.events.collect { event ->
                    send(
                        Frame.Text(
                            json.encodeToString(
                                com.forge.ide.core.backendapi.Event.serializer(),
                                event,
                            ),
                        ),
                    )
                }
            }

            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        services.router.handle(
                            { response ->
                                send(
                                    Frame.Text(
                                        json.encodeToString(Response.serializer(), response),
                                    ),
                                )
                            },
                            frame.readText(),
                        )
                    }
                }
            } finally {
                pump.cancel()
            }
        }
    }
}
