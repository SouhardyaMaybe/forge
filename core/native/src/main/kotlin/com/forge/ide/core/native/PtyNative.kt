package com.forge.ide.core.native

/*
 * JNI bindings for the native PTY layer (forge_jni.c).
 *
 * These are top-level `external` functions so their JNI symbol names are
 * stable: Java_com_forge_ide_core_native_PtyNativeKt_<name>.
 *
 * Do not call these directly from business code — use [PtySession] in Pty.kt,
 * which owns threads, buffers and lifecycle.
 */

internal external fun nativeOpen(
    argv: Array<String>,
    cwd: String?,
    envp: Array<String>?,
    rows: Int,
    cols: Int,
): Long

internal external fun nativeRead(
    handle: Long,
    buffer: ByteArray,
    offset: Int,
    length: Int,
): Int

internal external fun nativeWrite(
    handle: Long,
    buffer: ByteArray,
    offset: Int,
    length: Int,
): Int

internal external fun nativeResize(handle: Long, rows: Int, cols: Int)

internal external fun nativeSignal(handle: Long, signal: Int)

internal external fun nativeClose(handle: Long)

/** Returns the child's exit status, or -1 when it is still running. */
internal external fun nativeWait(handle: Long): Int

/** Returns [totalKb, availableKb] from /proc/meminfo, or [-1, -1] on failure. */
internal external fun nativeMemInfo(): IntArray
