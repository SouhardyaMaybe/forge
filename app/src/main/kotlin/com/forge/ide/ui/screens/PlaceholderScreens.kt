package com.forge.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared placeholder used by features that are still on the roadmap. */
@Composable
fun PlaceholderScreen(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun TerminalScreen(modifier: Modifier = Modifier) = PlaceholderScreen(
    title = "Terminal",
    body = "tmux-backed durable shell sessions (native PTY) arrive next.",
    modifier = modifier,
)

@Composable
fun AboutScreen(modifier: Modifier = Modifier) = PlaceholderScreen(
    title = "About Forge",
    body = "Forge is an open-source native IDE for Android: file explorer, editor, " +
        "terminal, on-device Gradle builds and a host for CLI coding agents.",
    modifier = modifier,
)
