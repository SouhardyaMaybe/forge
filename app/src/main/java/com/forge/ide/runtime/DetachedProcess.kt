package com.forge.ide.runtime

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A long-lived process that must survive the app process.
 *
 * Android's low-memory killer kills child processes along with the app, which
 * is what makes agent sessions "restart". Processes launched through here are
 * daemonised by [NativeSpawn.spawnDetached]: they get their own session, are
 * re-parented to init, and ignore SIGHUP, so closing the app (or the app being
 * killed while the user multitasks) leaves them running.
 *
 * Re-attaching is the point: stdio is wired to a FIFO (stdin) and an append-only
 * log (stdout/stderr), so a fresh app process can open the same session again
 * from the registry and keep reading/writing without restarting anything.
 *
 * Presents the same `Process`-shaped surface the agent bridges already use.
 */
internal class DetachedProcess private constructor(
    val pid: Int,
    private val logFile: File,
    val fifoFile: File,
    private val stdinWriter: OutputStream,
) : Process() {

    @Volatile private var destroyed = false
    private var exitStatus: Int? = null

    override fun getOutputStream(): OutputStream = stdinWriter

    /** The session log; readers should poll [logFile] for appended bytes. */
    val outputFile: File get() = logFile

    override fun getInputStream(): InputStream = FileInputStream(logFile)

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        while (isAlive) {
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return exitStatus ?: 0
    }

    override fun exitValue(): Int {
        if (!isAlive) return exitStatus ?: 0
        throw IllegalThreadStateException("Process is still running")
    }

    override fun destroy() {
        destroyed = true
        NativeSpawn.kill(pid, 15)
        // Give the CLI a moment to flush its own state, then force it.
        Thread {
            Thread.sleep(3_000)
            if (isAlive) NativeSpawn.kill(pid, 9)
        }.apply { isDaemon = true }.start()
    }

    override fun destroyForcibly(): Process {
        NativeSpawn.kill(pid, 9)
        return this
    }

    override fun isAlive(): Boolean {
        if (destroyed) return false
        // kill(pid, 0) is the portable liveness probe; EPERM would also mean alive.
        return NativeSpawn.kill(pid, 0) == 0 || isProbablyAlive()
    }

    private fun isProbablyAlive(): Boolean {
        val status = NativeSpawn.waitFor(pid, true)
        return status == NativeSpawn.STILL_RUNNING
    }

    /** Sends the same interrupt a Ctrl+C produces in a real terminal. */
    fun interrupt() {
        NativeSpawn.kill(pid, 2)
    }

    companion object {
        fun start(
            argv: List<String>,
            environment: Map<String, String>,
            cwd: String,
            logFile: File,
            fifoFile: File,
        ): DetachedProcess {
            logFile.parentFile?.mkdirs()
            val spawned = NativeSpawn.spawnDetached(
                argv.toTypedArray(),
                environment.map { "${it.key}=${it.value}" }.toTypedArray(),
                cwd,
                logFile.absolutePath,
                fifoFile.absolutePath,
            )
            check(spawned > 0) { "Detached runtime launch failed" }
            // O_RDWR keeps the pipe open even when no client is attached, so the
            // session never sees EOF on stdin.
            val writer = FileOutputStream(fifoFile.absolutePath, true)
            return DetachedProcess(spawned, logFile, fifoFile, writer)
        }

        /** Adopts a session that is already running (after an app restart). */
        fun adopt(pid: Int, logFile: File, fifoFile: File): DetachedProcess {
            val writer = FileOutputStream(fifoFile.absolutePath, true)
            return DetachedProcess(pid, logFile, fifoFile, writer)
        }
    }
}

private object NativeSpawn {
    const val STILL_RUNNING = -2

    init {
        System.loadLibrary("pocketspawn")
    }

    external fun spawnDetached(
        argv: Array<String>,
        environment: Array<String>,
        cwd: String,
        logPath: String,
        fifoPath: String,
    ): Int

    external fun kill(pid: Int, signal: Int): Int
    external fun waitFor(pid: Int, noHang: Boolean): Int
}
