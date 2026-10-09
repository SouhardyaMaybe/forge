package com.forge.ide.core.native

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Safe Kotlin wrapper over the native PTY.
 *
 * A [PtySession] owns one pseudoterminal and its child process. The reader runs
 * on a dedicated daemon thread: `read()` blocks until bytes arrive, so the
 * caller must never invoke it from the main thread. Output is delivered through
 * a [PtySession.output] callback; when the child exits the callback receives a
 * zero-length read and [exitCode] becomes available.
 *
 * Long-running shells started through this class should attach to tmux so the
 * session survives process death (milestone M1 wires this up).
 */
class PtySession private constructor(
    private val handle: Long,
    val argv: List<String>,
    val cwd: String?,
) {
    val masterFd: Int = (handle ushr 32).toInt()
    val childPid: Int = (handle and 0xFFFFFFFFL).toInt()

    private val closed = AtomicBoolean(false)
    private var exitStatus: Int? = null

    /** True once the child has exited and been reaped (or the pty was closed). */
    val isFinished: Boolean
        get() = exitStatus != null

    fun exitCode(): Int? = exitStatus

    /**
     * Blocking read loop. Call on a background thread.
     *
     * @param buffer scratch buffer reused across reads (keep it 64 KiB or less)
     * @param onOutput invoked for every chunk of bytes read from the pty
     * @param onExit invoked exactly once when the child exits or the pty fails
     */
    fun readLoop(buffer: ByteArray = ByteArray(64 * 1024), onOutput: (ByteArray, Int) -> Unit, onExit: (Int) -> Unit) {
        var pendingSignal = 0
        while (!closed.get()) {
            val read = nativeRead(handle, buffer, 0, buffer.size)
            if (read < 0) {
                pendingSignal++
                if (pendingSignal >= 2) break
                continue
            }
            pendingSignal = 0
            if (read == 0) {
                // EOF: the child side of the pty closed.
                break
            }
            onOutput(buffer, read)
        }

        if (closed.get()) {
            onExit(exitStatus ?: -1)
            return
        }

        // Reap the child with a bounded wait so the thread cannot hang forever.
        var status = nativeWait(handle)
        var spins = 0
        while (status < 0 && spins < 20) {
            Thread.sleep(50)
            status = nativeWait(handle)
            spins++
        }
        exitStatus = status
        if (status < 0) {
            nativeSignal(handle, SIGKILL)
            status = nativeWait(handle)
            exitStatus = status
        }
        onExit(exitStatus ?: -1)
    }

    fun write(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        nativeWrite(handle, bytes, 0, bytes.size)
    }

    fun resize(rows: Int, cols: Int) {
        nativeResize(handle, rows, cols)
    }

    fun signal(signal: Int) {
        nativeSignal(handle, signal)
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            nativeClose(handle)
        }
    }

    companion object {
        const val SIGHUP = 1
        const val SIGINT = 2
        const val SIGKILL = 9
        const val SIGTERM = 15

        fun loadNativeLibrary() {
            System.loadLibrary("forge_jni")
        }

        /**
         * Opens a command in a fresh pseudoterminal.
         *
         * @param argv program + args, e.g. listOf("/bin/sh", "-c", "exec bash")
         * @param cwd working directory for the child
         * @param envp environment entries in `KEY=VALUE` form (null = inherit)
         */
        fun open(
            argv: List<String>,
            cwd: String? = null,
            envp: List<String>? = null,
            rows: Int = 24,
            cols: Int = 80,
        ): PtySession {
            loadNativeLibrary()
            require(argv.isNotEmpty()) { "argv must not be empty" }
            val handle = nativeOpen(
                argv.toTypedArray(),
                cwd,
                envp?.toTypedArray(),
                rows,
                cols,
            )
            require(handle >= 0) { "nativeOpen failed for ${argv.first()}" }
            return PtySession(handle, argv, cwd)
        }

        /** Best-effort memory snapshot in KB; -1 when unavailable. */
        fun memInfoKb(): Pair<Long, Long> {
            val info = nativeMemInfo()
            val total = if (info.size > 0) info[0].toLong() else -1L
            val available = if (info.size > 1) info[1].toLong() else -1L
            return total to available
        }

        /** True when the file exists and is executable. */
        fun isExecutable(path: String): Boolean =
            File(path).let { it.exists() && it.canExecute() }
    }
}
