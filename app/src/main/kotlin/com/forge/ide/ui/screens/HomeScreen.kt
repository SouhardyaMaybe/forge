package com.forge.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Forge",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "A native, agentic IDE for Android.",
            style = MaterialTheme.typography.bodyLarge,
        )

        Spacer(Modifier.height(8.dp))

        StatusCard(
            title = "Backend server",
            body = "Not started yet. M1 will boot the proot environment and the " +
                "localhost Ktor server in the :backend process.",
        )
        StatusCard(
            title = "Toolchain",
            body = "Not provisioned. On-device setup (rootfs, JDK, Android SDK) " +
                "ships with the S0 spike scripts.",
        )
        StatusCard(
            title = "Agents",
            body = "Claude Code / OpenCode / Codex / Aider hosting is roadmap M3.",
        )
        StatusCard(
            title = "Device profile",
            body = "RAM tier detection and the Resource Governor land in M1.",
        )
    }
}

@Composable
private fun StatusCard(title: String, body: String) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
