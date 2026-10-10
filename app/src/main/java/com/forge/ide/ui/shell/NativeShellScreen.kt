package com.forge.ide.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.ide.runtime.NativeShellSession
import kotlinx.coroutines.launch

/** One tab's worth of shell state. */
private class ShellTab(val id: String) {
    val lines = mutableStateListOf<String>()
    var alive by mutableStateOf(true)
    val history = mutableStateListOf<String>()
    var historyIndex by mutableStateOf(-1)
    var partialLine by mutableStateOf("")
}

/**
 * Termux-style terminal that runs with zero setup.
 *
 * The shell is `/system/bin/sh` on a real PTY, so this screen works on first
 * launch: no Ubuntu bundle, no agent, no API key, no project. Multiple shell
 * tabs run side by side, each with its own process.
 */
@Composable
fun NativeShellScreen(modifier: Modifier = Modifier) {
    val tabs = remember { mutableStateListOf(ShellTab("shell-1")) }
    var activeIndex by remember { mutableStateOf(0) }
    var input by remember { mutableStateOf("") }
    var fontSize by remember { mutableStateOf(12f) }
    val listState = rememberLazyListState()
    val sessions = remember { mutableMapOf<String, NativeShellSession>() }
    val scope = rememberCoroutineScope()

    fun activeTab(): ShellTab = tabs[activeIndex.coerceIn(tabs.indices)]

    fun sessionFor(tab: ShellTab): NativeShellSession {
        return sessions.getOrPut(tab.id) {
            NativeShellSession(tab.id).also { session ->
                session.onOutput = { chunk ->
                    val text = chunk.replace("\r\n", "\n").replace("\r", "\n")
                    text.split('\n').forEachIndexed { index, part ->
                        if (index == 0) {
                            if (tab.lines.isNotEmpty()) {
                                tab.lines[tab.lines.lastIndex] = tab.lines.last() + part
                            } else if (part.isNotEmpty()) {
                                tab.lines += part
                            }
                        } else {
                            tab.lines += part
                        }
                    }
                    while (tab.lines.size > MAX_LINES) tab.lines.removeAt(0)
                }
                session.onExit = { tab.alive = false }
                session.startReader()
            }
        }
    }

    fun sendToActive(payload: String) {
        sessionFor(activeTab()).send(payload)
    }

    fun submit() {
        val command = input
        if (command.isNotBlank()) {
            activeTab().history.add(command)
            activeTab().historyIndex = -1
            sendToActive("$command\n")
        }
        input = ""
    }

    DisposableEffectShutdown {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    LaunchedEffect(activeTab().lines.size) {
        if (activeTab().lines.isNotEmpty()) listState.animateScrollToItem(activeTab().lines.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // --- session tabs -------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == activeIndex
                AssistChip(
                    onClick = { activeIndex = index },
                    label = {
                        Text(
                            text = tab.id.removePrefix("shell-"),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close",
                            modifier = Modifier
                                .size(14.dp)
                                .clickable {
                                    sessions.remove(tab.id)?.close()
                                    tabs.remove(tab)
                                    if (activeIndex >= tabs.size) activeIndex = tabs.lastIndex.coerceAtLeast(0)
                                },
                        )
                    },
                    colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            IconButton(
                onClick = {
                    tabs += ShellTab("shell-${tabs.size + 1}")
                    activeIndex = tabs.lastIndex
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New shell")
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "pid ${sessionFor(activeTab()).pid}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(end = 6.dp),
            )
            IconButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(8f) }) {
                Text("A-", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(24f) }) {
                Text("A+", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(
                onClick = {
                    activeTab().lines.clear()
                },
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Clear", modifier = Modifier.size(16.dp))
            }
        }

        // --- output -------------------------------------------------------
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            val tab = activeTab()
            if (tab.lines.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Device shell ready — no setup required.\nTry: ls, cd /sdcard, pwd, cat /proc/meminfo",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
                ) {
                    items(tab.lines) { line ->
                        Text(
                            text = line.ifEmpty { " " },
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.35f).sp,
                            ),
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }

        // --- input --------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                placeholder = { Text("command") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        submit()
                    },
                ),
            )
            IconButton(onClick = { submit() }) {
                Text("⏎", fontSize = 16.sp)
            }
        }

        // --- accessory keys (Termux style, two rows) ----------------------
        AccessoryRow(
            modifier = Modifier.fillMaxWidth(),
            onKey = { sendToActive(it) },
            onHistoryUp = {
                val tab = activeTab()
                if (tab.history.isNotEmpty()) {
                    tab.historyIndex = if (tab.historyIndex < 0) tab.history.lastIndex else (tab.historyIndex - 1).coerceAtLeast(0)
                    input = tab.history[tab.historyIndex]
                }
            },
            onHistoryDown = {
                val tab = activeTab()
                if (tab.history.isNotEmpty()) {
                    tab.historyIndex = (tab.historyIndex + 1).coerceAtMost(tab.history.lastIndex)
                    input = tab.history[tab.historyIndex]
                }
            },
        )
    }
}

@Composable
private fun AccessoryRow(
    onKey: (String) -> Unit,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
        val rowOne = listOf(
            "ESC" to "\u001B",
            "TAB" to "\t",
            "CTRL" to "\u0003", // labelled separately below
            "↑" to null,
            "↓" to null,
            "←" to "\u001B[D",
            "→" to "\u001B[C",
        )
        val rowTwo = listOf(
            "HOME" to "\u001B[H",
            "END" to "\u001B[F",
            "PGUP" to "\u001B[5~",
            "PGDN" to "\u001B[6~",
            "~" to "~",
            "/" to "/",
            "|" to "|",
            "-" to "-",
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            rowOne.forEach { (label, payload) ->
                AssistChip(
                    onClick = {
                        when (label) {
                            "↑" -> onHistoryUp()
                            "↓" -> onHistoryDown()
                            else -> payload?.let(onKey)
                        }
                    },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(30.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            rowTwo.forEach { (label, payload) ->
                AssistChip(
                    onClick = { onKey(payload) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(30.dp),
                )
            }
        }
    }
}

/** Runs [block] on dispose; kept local so the screen stays self-contained. */
@Composable
private fun DisposableEffectShutdown(block: () -> Unit) {
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { block() }
    }
}

private const val MAX_LINES = 3_000
