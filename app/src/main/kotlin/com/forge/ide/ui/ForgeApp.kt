package com.forge.ide.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.forge.ide.ui.screens.AboutScreen
import com.forge.ide.ui.screens.FilesScreen
import com.forge.ide.ui.screens.HomeScreen
import com.forge.ide.ui.screens.PlaceholderScreen
import com.forge.ide.ui.screens.TerminalScreen

// ---------------------------------------------------------------------------
// Routes (Navigation 3). Routes are plain data objects/classes; the back stack
// is a SnapshotStateList<Any>. Feature screens register entries via entryProvider.
// ---------------------------------------------------------------------------

data object HomeRoute
data object FilesRoute
data object TerminalRoute
data object BuildRoute
data object AboutRoute

private data class TopLevelDestination(
    val route: Any,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun ForgeApp() {
    val backStack = remember { mutableStateListOf<Any>(HomeRoute) }

    val destinations = listOf(
        TopLevelDestination(HomeRoute, "Home", Icons.Filled.Home),
        TopLevelDestination(FilesRoute, "Files", Icons.Filled.List),
        TopLevelDestination(TerminalRoute, "Terminal", Icons.Filled.PlayArrow),
        TopLevelDestination(BuildRoute, "Build", Icons.Filled.Build),
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = backStack.lastOrNull() == destination.route,
                        onClick = {
                            // Replace the top of the stack with the destination
                            // (top-level destinations do not accumulate history).
                            if (backStack.lastOrNull() != destination.route) {
                                backStack[backStack.lastIndex] = destination.route
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        }
    ) { innerPadding ->
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            modifier = Modifier.padding(innerPadding),
            entryProvider = { key ->
                when (key) {
                    is HomeRoute -> NavEntry(key) { HomeScreen() }
                    is FilesRoute -> NavEntry(key) { FilesScreen() }
                    is TerminalRoute -> NavEntry(key) { TerminalScreen() }
                    is BuildRoute -> NavEntry(key) { BuildScreenStub() }
                    is AboutRoute -> NavEntry(key) { AboutScreen() }
                    else -> error("Unknown route: $key")
                }
            },
        )
    }
}

@Composable
private fun BuildScreenStub() {
    PlaceholderScreen(
        title = "Build",
        body = "Build orchestration arrives in milestone M1.\n\n" +
            "Gradle tiers, the Resource Governor and the debug/release pipeline " +
            "will live here.",
    )
}
