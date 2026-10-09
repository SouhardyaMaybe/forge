package com.forge.ide.core.backend

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * Wiring for everything the backend process owns.
 *
 * Created once by [ForgeServerService] and handed to the Ktor module. Keeping
 * this construction in one place makes the backend trivially testable: host
 * tests can build the same graph with a temp directory.
 */
class BackendServices private constructor(
    val eventBus: EventBus,
    val governor: ResourceGovernor,
    val fileService: FileService,
    val terminalService: TerminalService,
    val router: WsRouter,
) {
    fun shutdown() {
        terminalService.closeAll()
    }

    companion object {
        fun create(context: Context, scope: CoroutineScope): BackendServices {
            val workspace = File(context.filesDir, "projects").apply { mkdirs() }

            val eventBus = EventBus()
            val governor = ResourceGovernor(scope)
            val fileService = FileService(listOf(workspace))
            val terminalService = TerminalService(
                scope = scope,
                launcher = ShellLauncher(cwd = workspace.path),
                eventBus = eventBus,
            )
            val router = WsRouter(
                fileService = fileService,
                terminalService = terminalService,
                governor = governor,
                eventBus = eventBus,
            )

            governor.startSampling(eventBus)
            return BackendServices(eventBus, governor, fileService, terminalService, router)
        }

        /** Test-friendly factory that uses [workspace] instead of app storage. */
        fun createForTest(workspace: File, scope: CoroutineScope): BackendServices {
            val eventBus = EventBus()
            val governor = ResourceGovernor(scope)
            val fileService = FileService(listOf(workspace))
            val terminalService = TerminalService(
                scope = scope,
                launcher = ShellLauncher(cwd = workspace.path),
                eventBus = eventBus,
            )
            val router = WsRouter(fileService, terminalService, governor, eventBus)
            return BackendServices(eventBus, governor, fileService, terminalService, router)
        }
    }
}
