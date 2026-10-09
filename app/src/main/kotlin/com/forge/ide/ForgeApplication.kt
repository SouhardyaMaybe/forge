package com.forge.ide

import android.app.Application
import com.forge.ide.core.backend.ForgeRuntime

/**
 * Forge application entry point.
 *
 * Holds process-wide singletons that must be visible to both the UI process
 * and the `:backend` process. The runtime exposes the backend server endpoint
 * (port + per-boot token) once the [com.forge.ide.core.backend.ForgeServerService]
 * has started.
 */
class ForgeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ForgeRuntime.init()
    }
}
