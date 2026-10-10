package com.forge.ide.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.forge.ide.data.ForgeServices
import com.forge.ide.data.TerminalController
import kotlinx.coroutines.launch

/** Max lines retained in the terminal buffer — keeps RSS bounded on 3 GB phones. */
private const val MAX_BUFFER_LINES = 2_000

/**
 * Terminal backed by the backend's real PTY.
 *
 * M1 renders output as plain text (ANSI escapes are stripped so the stream
 * stays readable); a full VT100 renderer is the next step. Input goes through
 * the accessory chips and the text field, exactly like a phone shell should.
 */
@Composable
fun TerminalScreen(modifier: Modifier = Modifier) {
    val controller = remember { TerminalController(ForgeServices.endpoint) }
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var exitInfo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        controller.onOutput = { chunk ->
            val parts = chunk.split('\n')
            parts.forEachIndexed { index, part ->
                if (index == 0) {
                    if (lines.isNotEmpty()) {
                        lines[lines.lastIndex] = lines.last() + stripAnsi(part).replace("\r", "")
                    } else if (part.isNotEmpty()) {
                        lines += stripAnsi(part)
                    }
                } else {
                    lines += stripAnsi(part)
                }
            }
            while (lines.size > MAX_BUFFER_LINES) lines.removeAt(0)
        }
        controller.onExit = { code -> exitInfo = "process exited ($code)" }
        controller.onConnectionChange = { connected = it }
        controller.connect(scope)
    }

    // Keep the view pinned to the newest output.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    connected -> "shell · connected"
                    exitInfo != null -> exitInfo!!
                    else -> "shell · connecting…"
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            if (!connected && exitInfo == null) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text(
                text = "${lines.size} lines",
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (lines.isEmpty() && !connected) {
                Text(
                    "Starting the shell in the :backend process…",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    items(lines) { line ->
                        Text(
                            text = line.ifEmpty { " " },
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            ),
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }

        AccessoryRow(
            onSend = { controller.sendInput(it) },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                placeholder = { Text("ls -la") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (input.isNotEmpty()) {
                            controller.sendInput(input + "\n")
                            input = ""
                        }
                    },
                ),
            )
            IconButton(
                onClick = {
                    if (input.isNotEmpty()) {
                        controller.sendInput(input + "\n")
                        input = ""
                    }
                },
            ) {
                Icon(Icons.Filled.Send, contentDescription = "Run command")
            }
        }
    }
}

@Composable
private fun AccessoryRow(onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val keys = listOf(
            "Tab" to "\t",
            "Esc" to "\u001B",
            "Ctrl+C" to "\u0003",
            "Ctrl+D" to "\u0004",
            "Ctrl+Z" to "\u001A",
            "↑" to "\u001B[A",
            "↓" to "\u001B[B",
            "~" to "~",
            "/" to "/",
            "|" to "|",
            "clear" to "clear\n",
        )
        keys.forEach { (label, payload) ->
            AssistChip(
                onClick = { onSend(payload) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

/** Strips CSI/OSC escape sequences so raw shell output stays readable. */
internal fun stripAnsi(text: String): String {
    val csi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    val osc = Regex("\u001B\\][^\u0007]*(\u0007|\u001B\\\\)")
    val other = Regex("\u001B[@-Z\\\\-_]")
    return text.replace(osc, "").replace(csi, "").replace(other, "")
}
