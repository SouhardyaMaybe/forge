package com.forge.ide.core.backend

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide view of the backend server endpoint.
 *
 * The UI process observes this to connect; the `:backend` process publishes to
 * it when the Ktor server has bound its port. The token is generated per boot
 * and never persisted to disk.
 */
object ForgeRuntime {

    data class Endpoint(
        val host: String,
        val port: Int,
        val token: String,
    ) {
        val baseUrl: String get() = "http://$host:$port"
        val wsUrl: String get() = "ws://$host:$port/ws"
    }

    private val _endpoint = MutableStateFlow<Endpoint?>(null)
    val endpoint: StateFlow<Endpoint?> = _endpoint

    fun init() {
        // Reserved for future process-level init.
    }

    fun onServerStarted(host: String, port: Int, token: String) {
        _endpoint.value = Endpoint(host, port, token)
    }

    fun onServerStopped() {
        _endpoint.value = null
    }
}
