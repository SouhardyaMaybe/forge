package com.forge.ide.runtime

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * An interactive shell backed by a real PTY, running `/system/bin/sh`.
 *
 * This is the zero-dependency terminal: no PRoot, no Ubuntu bundle, no agent,
 * no API key — it works the moment the app starts, on any device. When the
 * Linux toolchain is installed the user can still pick the Ubuntu shell; this
 * class exists so the terminal is never gated behind a download.
 *
 * Output arrives through a file pump (the same mechanism the agent bridges
 * use), which keeps reads off the main thread and survives the session being
 * attached to by a new UI instance.
 */
class NativeShellSession(
    val id: String,
    val title: String = "shell",
) {
    private val pty: NativeSpawnProcess
    private var outputOffset = 0L
    private val logFile: File

    @Volatile private var readerRunning = true
    private var readerThread: Thread? = null

    /** Called for every chunk of terminal output. */
    var onOutput: ((String) -> Unit)? = null

    /** Called once when the shell exits. */
    var onExit: ((Int) -> Unit)? = null

    init {
        val home = File(System.getenv("HOME") ?: "/data/local/tmp")
        logFile = File(home, ".forge/shells/$id.log").apply { parentFile?.mkdirs() }
        pty = NativeSpawnProcess.start(
            argv = listOf("/system/bin/sh", "-i"),
            environment = mapOf(
                "TERM" to "xterm-256color",
                "HOME" to home.absolutePath,
                "PATH" to "/system/bin:/system/xbin:/vendor/bin",
                "PS1" to "forge $ ",
                "PS2" to "> ",
            ),
            cwd = home.absolutePath,
            outputFile = logFile,
            pseudoTerminal = true,
            ptyRows = DEFAULT_ROWS,
            ptyColumns = DEFAULT_COLUMNS,
        )
        startReader()
    }

    val pid: Int get() = pty.pid

    val outputPath: String get() = logFile.absolutePath

    fun startReader() {
        if (readerThread != null) return
        readerThread = Thread({
            var idleSpins = 0
            while (readerRunning) {
                try {
                    val available = logFile.length() - outputOffset
                    if (available > 0) {
                        idleSpins = 0
                        val bytes = ByteArray(minOf(available, 16 * 1024).toInt())
                        val count = RandomAccessFile(logFile, "r").use { input ->
                            input.seek(outputOffset)
                            input.read(bytes)
                        }
                        if (count > 0) {
                            outputOffset += count
                            val chunk = String(bytes, 0, count, Charsets.UTF_8)
                            onOutput?.invoke(chunk)
                        }
                        continue
                    }
                    if (!pty.isAlive) {
                        // Drain anything the shell wrote just before exiting.
                        val tail = logFile.length() - outputOffset
                        if (tail > 0) continue
                        idleSpins++
                        if (idleSpins > 4) break
                    }
                    Thread.sleep(60)
                } catch (t: Throwable) {
                    Log.w(TAG, "reader stopped: ${t.message}")
                    break
                }
            }
            readerRunning = false
            onExit?.invoke(kotlin.runCatching { pty.exitValue() }.getOrDefault(-1))
        }, "forge-shell-$id").apply {
            isDaemon = true
            start()
        }
    }

    /** Types [text] into the shell. Use "\n" to submit. */
    fun send(text: String) {
        runCatching {
            pty.outputStream.write(text.toByteArray(Charsets.UTF_8))
            pty.outputStream.flush()
        }.onFailure { Log.w(TAG, "write failed: ${it.message}") }
    }

    fun sendLine(command: String) = send("$command\n")

    fun resize(rows: Int, columns: Int) = pty.resize(rows, columns)

    fun interrupt() {
        runCatching { pty.interrupt() }
    }

    fun isAlive(): Boolean = runCatching { pty.isAlive }.getOrDefault(false)

    fun close() {
        readerRunning = false
        runCatching { pty.destroyForcibly() }
    }

    /** Re-attaches to a shell that is already running (after a UI restart). */
    fun adopt() {
        readerRunning = true
        outputOffset = logFile.length() // continue from where the log ends
        startReader()
    }

    companion object {
        private const val TAG = "ForgeShell"
        const val DEFAULT_ROWS = 32
        const val DEFAULT_COLUMNS = 120
    }
}

