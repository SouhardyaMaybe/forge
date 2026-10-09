package com.forge.ide.core.backendapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire protocol between the UI process and the backend server.
 *
 * One WebSocket connection carries JSON envelopes. Requests carry an `id` for
 * idempotent retry; events are server-push. Mutating operations carry an
 * `idempotencyKey` so a reconnecting client never applies them twice.
 *
 * This module is intentionally free of Android dependencies so the protocol can
 * be consumed by the UI process, the backend process and (later) host tests.
 */
@Serializable
sealed interface Envelope

@Serializable
@SerialName("request")
data class Request(
    val id: Long,
    val method: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val idempotencyKey: String? = null,
) : Envelope

@Serializable
@SerialName("response")
data class Response(
    val id: Long,
    val ok: Boolean,
    val result: JsonObject? = null,
    val error: ProtocolError? = null,
) : Envelope

@Serializable
@SerialName("event")
data class Event(
    val seq: Long,
    val name: String,
    val params: JsonObject = JsonObject(emptyMap()),
) : Envelope

@Serializable
data class ProtocolError(
    val code: String,
    val message: String,
)

/** Well-known method names. Implementations arrive with milestone M1. */
object Methods {
    const val HEALTH = "health"
    const val FS_LIST = "fs.list"
    const val FS_READ = "fs.read"
    const val FS_WRITE = "fs.write"
    const val FS_DELETE = "fs.delete"
    const val FS_MKDIR = "fs.mkdir"
    const val FS_RENAME = "fs.rename"
    const val TERM_OPEN = "term.open"
    const val TERM_INPUT = "term.input"
    const val TERM_RESIZE = "term.resize"
    const val TERM_CLOSE = "term.close"
    const val GOVERNOR_STATE = "governor.state"
    const val BUILD_START = "build.start"
    const val BUILD_CANCEL = "build.cancel"
    const val AGENT_RUN = "agent.run"
    const val AGENT_PERMISSION_RESPOND = "agent.permission.respond"
}

/** Well-known event names. */
object Events {
    const val TERM_OUTPUT = "term.output"
    const val BUILD_PROGRESS = "build.progress"
    const val BUILD_FINISHED = "build.finished"
    const val AGENT_EVENT = "agent.event"
    const val AGENT_PERMISSION_REQUEST = "agent.permission.request"
    const val GOVERNOR_STATE = "governor.state"
}
