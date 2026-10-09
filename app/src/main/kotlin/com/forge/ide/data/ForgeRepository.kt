package com.forge.ide.data

import com.forge.ide.core.backend.ForgeRuntime
import com.forge.ide.core.backendapi.FsListResult
import com.forge.ide.core.backendapi.FsReadResult
import com.forge.ide.core.backendapi.FsRootResult
import com.forge.ide.core.backendapi.GovernorStateParams
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.StateFlow

/**
 * HTTP view of the backend server.
 *
 * The UI process is a *client*: it watches [ForgeRuntime.endpoint] for the
 * server address (published by the `:backend` process once Ktor binds a port)
 * and issues plain REST calls. Streaming surfaces (terminal, agents) come next
 * and will ride the WebSocket.
 */
class ForgeRepository(private val endpoint: StateFlow<ForgeRuntime.Endpoint?>) {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
    }

    private fun baseUrl(): String =
        endpoint.value?.baseUrl ?: error("backend server not connected")

    suspend fun isHealthy(): Boolean = try {
        client.get("${baseUrl()}/health").status.value == 200
    } catch (t: Throwable) {
        false
    }

    suspend fun rootPath(): String =
        client.get("${baseUrl()}/api/fs/root").body<FsRootResult>().path

    suspend fun list(path: String, depth: Int = 1): FsListResult =
        client.get("${baseUrl()}/api/fs/list") {
            parameter("path", path)
            parameter("depth", depth)
        }.body()

    suspend fun read(path: String): FsReadResult =
        client.get("${baseUrl()}/api/fs/read") { parameter("path", path) }.body()

    suspend fun governorState(): GovernorStateParams =
        client.get("${baseUrl()}/api/governor").body()

    fun close() {
        client.close()
    }
}
