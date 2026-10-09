package com.forge.ide.core.backendapi

import kotlinx.serialization.Serializable

/*
 * Typed payloads for the wire protocol defined in Envelope.kt.
 *
 * Params ride inside the generic JsonObject of a Request; results ride inside
 * the JsonObject of a Response. Events carry these same shapes as params.
 * Everything here is pure Kotlin so the UI process, the backend process and
 * host tests share one definition.
 */

// --------------------------------------------------------------------- files

@Serializable
data class FsEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
    val childCount: Int? = null,
)

@Serializable
data class FsRootResult(val path: String)

@Serializable
data class FsListParams(val path: String, val depth: Int = 1)

@Serializable
data class FsListResult(val path: String, val entries: List<FsEntry>)

/**
 * File contents are transferred as UTF-8 text. [truncated] is true when the
 * file exceeded [FsReadParams.limit] and only a prefix is returned; the editor
 * then offers read-only preview mode.
 */
@Serializable
data class FsReadParams(val path: String, val offset: Long = 0, val limit: Int = 256 * 1024)

@Serializable
data class FsReadResult(
    val path: String,
    val content: String,
    val truncated: Boolean,
    val totalSize: Long,
    val encoding: String = "utf-8",
)

@Serializable
data class FsWriteParams(val path: String, val content: String, val createParents: Boolean = true)

@Serializable
data class FsWriteResult(val path: String, val bytesWritten: Long)

@Serializable
data class FsDeleteParams(val path: String, val toTrash: Boolean = true)

@Serializable
data class FsOpResult(val path: String, val ok: Boolean = true)

@Serializable
data class FsMkdirParams(val path: String)

@Serializable
data class FsRenameParams(val from: String, val to: String)

// ------------------------------------------------------------------ terminal

@Serializable
data class TermOpenParams(
    val sessionId: String? = null,
    val rows: Int = 24,
    val cols: Int = 80,
    val cwd: String? = null,
)

@Serializable
data class TermOpenResult(val sessionId: String, val pid: Int, val cwd: String)

@Serializable
data class TermInputParams(val sessionId: String, val data: String)

@Serializable
data class TermResizeParams(val sessionId: String, val rows: Int, val cols: Int)

@Serializable
data class TermCloseParams(val sessionId: String)

@Serializable
data class TermOutputParams(val sessionId: String, val data: String)

@Serializable
data class TermExitParams(val sessionId: String, val exitCode: Int)

/** Everything the UI needs to render a session row in the session switcher. */
@Serializable
data class TermSessionInfo(
    val sessionId: String,
    val title: String,
    val cwd: String,
    val pid: Int,
    val alive: Boolean,
    val createdAt: Long,
)

// ------------------------------------------------------------------ governor

@Serializable
data class GovernorStateParams(
    val activeOp: String?,
    val memAvailableKb: Long,
    val memTotalKb: Long,
    val thermalThrottled: Boolean,
    val queued: List<String>,
)
