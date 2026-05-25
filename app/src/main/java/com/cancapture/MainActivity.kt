package com.cancapture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cancapture.ui.CapturesScreen
import com.cancapture.ui.RecordScreen
import com.cancapture.ui.SettingsScreen
import com.cancapture.ui.theme.CanCaptureTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CanCaptureTheme { AppRoot() } }
    }
}

private sealed class Dest(val route: String, val label: String) {
    data object Record : Dest("record", "Record")
    data object Captures : Dest("captures", "Captures")
    data object Settings : Dest("settings", "Settings")
}

private val DESTINATIONS = listOf(Dest.Record, Dest.Captures, Dest.Settings)

@Composable
private fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                DESTINATIONS.forEach { dest ->
                    NavigationBarItem(
                        selected = backStackEntry?.destination?.hierarchy?.any { it.route == dest.route } == true,
                        onClick = {
                            if (currentRoute != dest.route) {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = when (dest) {
                                    Dest.Record -> Icons.Filled.FiberManualRecord
                                    Dest.Captures -> Icons.Filled.Folder
                                    Dest.Settings -> Icons.Filled.Settings
                                },
                                contentDescription = dest.label
                            )
                        },
                        label = { Text(dest.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.Record.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Dest.Record.route) { RecordScreen() }
            composable(Dest.Captures.route) { CapturesScreen() }
            composable(Dest.Settings.route) { SettingsScreen() }
        }
    }
}
