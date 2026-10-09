package com.forge.ide.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forge.ide.core.backendapi.FsEntry
import com.forge.ide.data.ForgeServices
import kotlinx.coroutines.launch

/** A flattened tree row: one file or directory at a known depth. */
private data class TreeRow(val entry: FsEntry, val depth: Int, val expanded: Boolean)

/**
 * File explorer backed by the backend's `fs.list`.
 *
 * The UI keeps only expanded directories in memory (path -> listing) and renders
 * a flattened list, so a 10k-file workspace never materialises in the UI
 * process. Rows are virtualised by LazyColumn.
 */
@Composable
fun FilesScreen(modifier: Modifier = Modifier) {
    val repository = ForgeServices.repository
    val scope = rememberCoroutineScope()

    var rootPath by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableStateOf(0) }

    // path -> listing of that directory; only expanded dirs are present.
    val expandedListings = remember { mutableStateMapOf<String, List<FsEntry>>() }

    fun loadRoot() {
        loading = true
        error = null
        scope.launch {
            try {
                val root = repository.rootPath()
                rootPath = root
                expandedListings[root] = repository.list(root, depth = 1).entries
            } catch (t: Throwable) {
                error = t.message ?: "backend not reachable"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(reloadToken) { loadRoot() }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = rootPath?.substringAfterLast('/') ?: "Files",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { reloadToken++ }, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload")
            }
        }

        when {
            loading && rootPath == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.size(8.dp))
                    Text("Connecting to backend…", style = MaterialTheme.typography.bodySmall)
                }
            }

            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(error ?: "unknown error", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "The backend server runs in the :backend process. If this persists, " +
                            "check the Forge notification and restart the app.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            else -> {
                val rows = buildRows(rootPath, expandedListings)
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(rows, key = { it.entry.path }) { row ->
                        TreeRowView(
                            row = row,
                            onToggle = { entry ->
                                if (expandedListings.containsKey(entry.path)) {
                                    expandedListings.remove(entry.path)
                                } else {
                                    scope.launch {
                                        try {
                                            expandedListings[entry.path] =
                                                repository.list(entry.path, depth = 1).entries
                                        } catch (t: Throwable) {
                                            error = t.message
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Flattens the expanded-directory map into a depth-ordered row list. */
private fun buildRows(rootPath: String?, listings: Map<String, List<FsEntry>>): List<TreeRow> {
    if (rootPath == null) return emptyList()
    val rows = mutableListOf<TreeRow>()
    val visited = mutableSetOf<String>()

    fun walk(dirPath: String, depth: Int) {
        if (!visited.add(dirPath)) return // guard against symlink cycles
        listings[dirPath]?.forEach { entry ->
            val isExpanded = listings.containsKey(entry.path)
            rows += TreeRow(entry, depth, isExpanded)
            if (entry.isDirectory && isExpanded) walk(entry.path, depth + 1)
        }
    }
    walk(rootPath, 0)
    return rows
}

@Composable
private fun TreeRowView(row: TreeRow, onToggle: (FsEntry) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.entry.isDirectory) { onToggle(row.entry) }
            .padding(start = (12 + row.depth * 16).dp, top = 10.dp, bottom = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = when {
                row.entry.isDirectory && row.expanded -> "▾"
                row.entry.isDirectory -> "▸"
                else -> "·"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = row.entry.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!row.entry.isDirectory) {
            Text(
                text = formatSize(row.entry.size),
                style = MaterialTheme.typography.labelSmall,
            )
        } else if (row.entry.childCount != null && row.expanded) {
            Text(
                text = "${row.entry.childCount}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / (1024L * 1024 * 1024)} GB"
}
