package com.forge.ide.core.backend

import android.util.Log
import com.forge.ide.core.backendapi.Events
import com.forge.ide.core.backendapi.TermCloseParams
import com.forge.ide.core.backendapi.TermInputParams
import com.forge.ide.core.backendapi.TermOpenParams
import com.forge.ide.core.backendapi.TermOpenResult
import com.forge.ide.core.backendapi.TermResizeParams
import com.forge.ide.core.backendapi.TermSessionInfo
import com.forge.ide.core.native.PtySession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Decides how a terminal session's process is launched. */
interface TerminalLauncher {
    /** Program + args, e.g. `["/system/bin/sh"]`. */
    fun argv(): List<String>

    /** Default working directory for new sessions. */
    fun defaultCwd(): String

    /** Environment entries in `KEY=VALUE` form. */
    fun envp(): List<String>?
}

/**
 * Plain on-device shell. Milestone M4 replaces the [argv] with a proot-launched
 * Debian login shell once the rootfs is provisioned; nothing above this class
 * changes.
 */
class ShellLauncher(private val cwd: String) : TerminalLauncher {
    override fun argv(): List<String> = listOf("/system/bin/sh")

    override fun defaultCwd(): String = cwd

    override fun envp(): List<String> = listOf(
        "TERM=xterm-256color",
        "HOME=$cwd",
        "PATH=/system/bin:/system/xbin:/vendor/bin",
        "PS1=\\$ ",
    )
}

private class ManagedTerminal(
    val sessionId: String,
    val pty: PtySession,
    val cwd: String,
    val createdAt: Long,
) {
    var readerJob: Job? = null
    val output = Channel<ByteArray>(Channel.BUFFERED)

    fun info(): TermSessionInfo = TermSessionInfo(
        sessionId = sessionId,
        title = pty.argv.firstOrNull()?.substringAfterLast('/') ?: "shell",
        cwd = cwd,
        pid = pty.childPid,
        alive = !pty.isFinished,
        createdAt = createdAt,
    )
}

/**
 * Owns every terminal session in the backend process.
 *
 * Sessions are deliberately *not* tied to a WebSocket connection: the PTY lives
 * here, output is journaled through the event bus, and a reconnecting UI simply
 * re-opens the same session id and receives subsequent output. Process death is
 * handled by the launcher (M4: tmux) — until then, a backend restart loses only
 * the in-flight scrollback, which the on-disk journal keeps.
 */
class TerminalService(
    private val scope: CoroutineScope,
    private val launcher: TerminalLauncher,
    private val eventBus: EventBus,
) {
    private val sessions = ConcurrentHashMap<String, ManagedTerminal>()

    fun open(params: TermOpenParams): TermOpenResult {
        val requestedId = params.sessionId
        val existing = requestedId?.let { sessions[it] }
        if (existing != null) {
            // Re-open of a live session: just apply the new window size.
            existing.pty.resize(params.rows, params.cols)
            return TermOpenResult(existing.sessionId, existing.pty.childPid, existing.cwd)
        }

        val sessionId = requestedId ?: UUID.randomUUID().toString()
        val cwd = params.cwd ?: launcher.defaultCwd()
        val pty = PtySession.open(
            argv = launcher.argv(),
            cwd = cwd,
            envp = launcher.envp(),
            rows = params.rows,
            cols = params.cols,
        )

        val managed = ManagedTerminal(sessionId, pty, cwd, System.currentTimeMillis())
        sessions[sessionId] = managed

        // Reader thread: blocking read on Dispatchers.IO, forwarded through a
        // channel so events are emitted in order and a slow client can never
        // block the PTY.
        managed.readerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(64 * 1024)
            pty.readLoop(
                buffer = buffer,
                onOutput = { bytes, length ->
                    if (managed.output.trySend(bytes.copyOf(length)).isFailure) {
                        droppedOutput.incrementAndGet()
                    }
                },
                onExit = { code ->
                    sessions.remove(sessionId)
                    scope.launch {
                        eventBus.emit(
                            Events.TERM_EXIT,
                            buildJsonObject {
                                put("sessionId", sessionId)
                                put("exitCode", code)
                            },
                        )
                    }
                    managed.output.close()
                },
            )
        }

        // Emitter: drains the channel into the event bus.
        scope.launch {
            for (chunk in managed.output) {
                eventBus.emit(
                    Events.TERM_OUTPUT,
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("data", String(chunk, Charsets.UTF_8))
                    },
                )
            }
        }

        Log.i(TAG, "terminal opened: $sessionId pid=${pty.childPid} cwd=$cwd")
        return TermOpenResult(sessionId, pty.childPid, cwd)
    }

    fun input(params: TermInputParams) {
        val session = sessions[params.sessionId] ?: error("no session: ${params.sessionId}")
        session.pty.write(params.data)
    }

    fun resize(params: TermResizeParams) {
        sessions[params.sessionId]?.pty?.resize(params.rows, params.cols)
    }

    fun close(params: TermCloseParams) {
        val session = sessions.remove(params.sessionId) ?: return
        session.pty.signal(PtySession.SIGHUP)
        session.pty.close()
        session.readerJob?.cancel()
    }

    fun list(): List<TermSessionInfo> = sessions.values.map { it.info() }

    fun closeAll() {
        sessions.keys.toList().forEach { id -> close(TermCloseParams(id)) }
    }

    companion object {
        private const val TAG = "ForgeTerminal"
        private val droppedOutput = java.util.concurrent.atomic.AtomicLong(0)
    }
}
