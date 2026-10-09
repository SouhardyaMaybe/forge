package com.forge.ide.core.backend

import com.forge.ide.core.backendapi.Event
import com.forge.ide.core.backendapi.FsDeleteParams
import com.forge.ide.core.backendapi.FsListParams
import com.forge.ide.core.backendapi.FsMkdirParams
import com.forge.ide.core.backendapi.FsReadParams
import com.forge.ide.core.backendapi.FsRenameParams
import com.forge.ide.core.backendapi.FsWriteParams
import com.forge.ide.core.backendapi.Methods
import com.forge.ide.core.backendapi.ProtocolError
import com.forge.ide.core.backendapi.Request
import com.forge.ide.core.backendapi.Response
import com.forge.ide.core.backendapi.TermCloseParams
import com.forge.ide.core.backendapi.TermInputParams
import com.forge.ide.core.backendapi.TermOpenParams
import com.forge.ide.core.backendapi.TermResizeParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * Dispatches protocol requests to services and serialises results back.
 *
 * The router is deliberately transport-free: it takes a `send` suspend lambda
 * so it can be exercised by host tests without a socket. Errors are returned as
 * `Response(ok = false)` — a bad request must never take down the connection.
 */
class WsRouter(
    private val fileService: FileService,
    private val terminalService: TerminalService,
    private val governor: ResourceGovernor,
    private val eventBus: EventBus,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    suspend fun handle(send: suspend (Response) -> Unit, payload: String) {
        val request = try {
            json.decodeFromString(Request.serializer(), payload)
        } catch (t: Throwable) {
            send(errorResponse(id = -1, code = "bad_request", message = t.message ?: "malformed envelope"))
            return
        }

        try {
            val result = dispatch(request)
            send(Response(id = request.id, ok = true, result = result))
        } catch (t: SecurityException) {
            send(errorResponse(request.id, "outside_workspace", t.message ?: "denied"))
        } catch (t: NotImplementedMethod) {
            send(errorResponse(request.id, "not_implemented", t.message ?: "not implemented"))
        } catch (t: Throwable) {
            send(errorResponse(request.id, "internal", t.message ?: t::class.simpleName ?: "error"))
        }
    }

    private suspend fun dispatch(request: Request): JsonObject = when (request.method) {
        Methods.HEALTH -> buildJsonObject { put("status", "ok") }

        Methods.FS_LIST -> json.encodeToJsonElement(
            com.forge.ide.core.backendapi.FsListResult.serializer(),
            fileService.list(json.decodeFromJsonElement(FsListParams.serializer(), request.params)),
        )

        Methods.FS_READ -> json.encodeToJsonElement(
            com.forge.ide.core.backendapi.FsReadResult.serializer(),
            fileService.read(json.decodeFromJsonElement(FsReadParams.serializer(), request.params)),
        )

        Methods.FS_WRITE -> json.encodeToJsonElement(
            com.forge.ide.core.backendapi.FsWriteResult.serializer(),
            fileService.write(json.decodeFromJsonElement(FsWriteParams.serializer(), request.params)),
        )

        Methods.FS_DELETE -> json.encodeToJsonElement(
            com.forge.ide.core.backendapi.FsOpResult.serializer(),
            fileService.delete(json.decodeFromJsonElement(FsDeleteParams.serializer(), request.params)),
        )

        Methods.FS_MKDIR -> json.encodeToJsonElement(
            com.forge.ide.core.backendapi.FsOpResult.serializer(),
            fileService.mkdir(json.decodeFromJsonElement(FsMkdirParams.serializer(), request.params)),
        )

        Methods.FS_RENAME -> json.encodeToJsonElement(
            com.forge.ide.core.backendapi.FsOpResult.serializer(),
            fileService.rename(json.decodeFromJsonElement(FsRenameParams.serializer(), request.params)),
        )

        Methods.TERM_OPEN -> json.encodeToJsonElement(
            com.forge.ide.core.backendapi.TermOpenResult.serializer(),
            terminalService.open(json.decodeFromJsonElement(TermOpenParams.serializer(), request.params)),
        )

        Methods.TERM_INPUT -> {
            terminalService.input(json.decodeFromJsonElement(TermInputParams.serializer(), request.params))
            buildJsonObject { put("ok", true) }
        }

        Methods.TERM_RESIZE -> {
            terminalService.resize(json.decodeFromJsonElement(TermResizeParams.serializer(), request.params))
            buildJsonObject { put("ok", true) }
        }

        Methods.TERM_CLOSE -> {
            terminalService.close(json.decodeFromJsonElement(TermCloseParams.serializer(), request.params))
            buildJsonObject { put("ok", true) }
        }

        Methods.GOVERNOR_STATE -> json.encodeToJsonElement(
            com.forge.ide.core.backendapi.GovernorStateParams.serializer(),
            governor.state.value,
        )

        Methods.BUILD_START, Methods.BUILD_CANCEL ->
            throw NotImplementedMethod("build orchestration lands in milestone M1b")

        Methods.AGENT_RUN, Methods.AGENT_PERMISSION_RESPOND ->
            throw NotImplementedMethod("agent host lands in milestone M3")

        else -> throw NotImplementedMethod("unknown method: ${request.method}")
    }

    private fun errorResponse(id: Long, code: String, message: String) = Response(
        id = id,
        ok = false,
        error = ProtocolError(code, message),
    )

    private class NotImplementedMethod(message: String) : Exception(message)
}
