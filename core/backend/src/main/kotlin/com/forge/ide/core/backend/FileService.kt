package com.forge.ide.core.backend

import com.forge.ide.core.backendapi.FsDeleteParams
import com.forge.ide.core.backendapi.FsEntry
import com.forge.ide.core.backendapi.FsListParams
import com.forge.ide.core.backendapi.FsListResult
import com.forge.ide.core.backendapi.FsMkdirParams
import com.forge.ide.core.backendapi.FsOpResult
import com.forge.ide.core.backendapi.FsReadParams
import com.forge.ide.core.backendapi.FsReadResult
import com.forge.ide.core.backendapi.FsRenameParams
import com.forge.ide.core.backendapi.FsWriteParams
import com.forge.ide.core.backendapi.FsWriteResult
import java.io.File

/**
 * Filesystem operations confined to [roots].
 *
 * Every path coming off the wire is resolved and checked against the workspace
 * roots, so a hostile or buggy client cannot escape with `../` traversal or
 * absolute paths outside the roots. This is the security boundary the UI, the
 * build orchestrator and agents all share.
 */
class FileService(private val roots: List<File>) {

    init {
        roots.forEach { it.mkdirs() }
    }

    val defaultRoot: File
        get() = roots.first()

    fun list(params: FsListParams): FsListResult {
        val dir = resolve(params.path)
        require(dir.isDirectory) { "not a directory: ${params.path}" }
        val depth = params.depth.coerceIn(1, 8)

        val entries = mutableListOf<FsEntry>()
        fun walk(current: File, level: Int) {
            val children = current.listFiles() ?: return
            children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .forEach { child ->
                    entries += child.toEntry()
                    if (depth > 1 && child.isDirectory && level < depth) {
                        walk(child, level + 1)
                    }
                }
        }
        walk(dir, 1)
        return FsListResult(params.path, entries)
    }

    fun read(params: FsReadParams): FsReadResult {
        val file = resolve(params.path)
        require(file.isFile) { "not a file: ${params.path}" }
        val total = file.length()
        val limit = params.limit.coerceIn(1, MAX_READ_BYTES)

        val buffer = ByteArray(limit)
        val read = file.inputStream().use { input ->
            var offset = 0
            while (offset < limit) {
                val n = input.read(buffer, offset, limit - offset)
                if (n <= 0) break
                offset += n
            }
            offset
        }

        // Files that are not valid UTF-8 surface as replacement characters;
        // the editor then opens them read-only. The size guard keeps a huge
        // file from ever being fully materialised in the backend process.
        val truncated = total > read
        return FsReadResult(
            path = params.path,
            content = String(buffer, 0, read, Charsets.UTF_8),
            truncated = truncated,
            totalSize = total,
        )
    }

    fun write(params: FsWriteParams): FsWriteResult {
        val target = resolve(params.path)
        if (params.createParents) {
            target.parentFile?.mkdirs()
        }
        val bytes = params.content.toByteArray(Charsets.UTF_8)
        target.outputStream().use { it.write(bytes); it.flush() }
        return FsWriteResult(target.path, bytes.size.toLong())
    }

    fun delete(params: FsDeleteParams): FsOpResult {
        val target = resolve(params.path)
        require(defaultRoot.canonicalPath != target.canonicalPath) {
            "refusing to delete the workspace root"
        }
        if (params.toTrash) {
            val trash = File(defaultRoot, TRASH_DIR).apply { mkdirs() }
            val destination = uniqueTrashName(trash, target.name)
            if (target.renameTo(destination)) {
                return FsOpResult(destination.path)
            }
        }
        val deleted = target.deleteRecursively()
        require(deleted) { "failed to delete: ${params.path}" }
        return FsOpResult(params.path)
    }

    fun mkdir(params: FsMkdirParams): FsOpResult {
        val target = resolve(params.path)
        check(target.mkdirs()) { "failed to create directory: ${params.path}" }
        return FsOpResult(target.path)
    }

    fun rename(params: FsRenameParams): FsOpResult {
        val from = resolve(params.from)
        val to = resolve(params.to)
        to.parentFile?.mkdirs()
        val moved = from.renameTo(to)
        require(moved) { "failed to move ${params.from} -> ${params.to}" }
        return FsOpResult(to.path)
    }

    fun exists(path: String): Boolean = resolve(path).exists()

    /** Resolves [path] against the filesystem and enforces the root boundary. */
    fun resolve(path: String): File {
        val candidate = File(path).canonicalFile
        val allowed = roots.any { root ->
            val rootPath = root.canonicalPath
            candidate.path == rootPath || candidate.path.startsWith(rootPath + File.separator)
        }
        if (!allowed) throw SecurityException("path outside workspace roots: $path")
        return candidate
    }

    private fun File.toEntry(): FsEntry {
        val children = if (isDirectory) listFiles()?.size else null
        return FsEntry(
            path = path,
            name = name,
            isDirectory = isDirectory,
            size = if (isDirectory) 0 else length(),
            modifiedAt = lastModified(),
            childCount = children,
        )
    }

    private fun uniqueTrashName(trash: File, name: String): File {
        var candidate = File(trash, name)
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(trash, "$name.$suffix")
            suffix++
        }
        return candidate
    }

    companion object {
        const val MAX_READ_BYTES = 4 * 1024 * 1024
        const val TRASH_DIR = ".forge/trash"
    }
}
