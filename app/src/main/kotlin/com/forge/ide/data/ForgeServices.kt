package com.forge.ide.data

import android.content.Context
import com.forge.ide.core.backend.ForgeRuntime
import com.forge.ide.core.backend.ForgeServerService
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide singletons for the UI side.
 *
 * A hand-rolled locator stands in for Hilt until M1 stabilises; the surface is
 * small on purpose (one endpoint flow, one repository) so the swap later is a
 * mechanical change.
 */
object ForgeServices {

    /** Published by the `:backend` process once Ktor binds its port. */
    val endpoint: StateFlow<ForgeRuntime.Endpoint?> = ForgeRuntime.endpoint

    val repository: ForgeRepository by lazy { ForgeRepository(endpoint) }

    fun startBackend(context: Context) {
        ForgeServerService.start(context)
    }
}
