package com.forge.ide.data

import com.forge.ide.core.backend.ForgeRuntime
import com.forge.ide.core.backendapi.FsListResult
import com.forge.ide.core.backendapi.FsReadResult
import com.forge.ide.core.backendapi.FsRootResult
import com.forge.ide.core.backendapi.GovernorStateParams
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * HTTP view of the backend server.
 *
 * Deliberately built on `HttpURLConnection` rather than an HTTP client library:
 * the app needs a handful of JSON GETs, and every dependency here costs APK size,
 * D8 memory and (on a 3 GB phone) resident memory. Parsing uses the shared
 * kotlinx.serialization models from `core:backend-api`.
 *
 * The UI process is a *client*: it watches [ForgeRuntime.endpoint] for the
 * server address published by the `:backend` process.
 */
class ForgeRepository(private val endpoint: StateFlow<ForgeRuntime.Endpoint?>) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun baseUrl(): String =
        endpoint.value?.baseUrl ?: error("backend server not connected")

    private fun getJson(path: String, query: Map<String, String> = emptyMap()): String {
        val queryString = query.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        val url = if (queryString.isEmpty()) baseUrl() + path else "$baseUrl$path?$queryString"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $path")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            throw t
        } finally {
            connection.disconnect()
        }
    }

    suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        try {
            getJson("/health")
            true
        } catch (t: Throwable) {
            false
        }
    }

    suspend fun rootPath(): String = withContext(Dispatchers.IO) {
        json.decodeFromString<FsRootResult>(getJson("/api/fs/root")).path
    }

    suspend fun list(path: String, depth: Int = 1): FsListResult = withContext(Dispatchers.IO) {
        json.decodeFromString<FsListResult>(getJson("/api/fs/list", mapOf("path" to path, "depth" to "$depth")))
    }

    suspend fun read(path: String): FsReadResult = withContext(Dispatchers.IO) {
        json.decodeFromString<FsReadResult>(getJson("/api/fs/read", mapOf("path" to path)))
    }

    suspend fun governorState(): GovernorStateParams = withContext(Dispatchers.IO) {
        json.decodeFromString<GovernorStateParams>(getJson("/api/governor"))
    }

    fun close() {
        // No connection pool to release: connections are opened per call.
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 15_000
    }
}
