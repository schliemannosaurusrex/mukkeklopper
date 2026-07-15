package de.schliemannosaurusrex.mukkeklopper.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavHostController
import de.schliemannosaurusrex.mukkeklopper.library.LibraryViewModel
import de.schliemannosaurusrex.mukkeklopper.player.PlayerViewModel

// Alle Navigationen müssen dieses Muster verwenden: ein Eintrag, der per plain
// navigate() gepusht wurde, übersteht das popUpTo/restoreState der Bottom-Bar
// und macht den Library-Tab wirkungslos.
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun MukkeKlopperApp() {
    val navController = rememberNavController()
    val playerViewModel: PlayerViewModel = viewModel()
    val libraryViewModel: LibraryViewModel = viewModel()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    LaunchedEffect(navController) {
        @SuppressLint("RestrictedApi")
        navController.currentBackStack.collect { stack ->
            Log.d("MukkeNav", "stack=" + stack.joinToString(" > ") {
                it.destination.route ?: it.destination.displayName
            })
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavScreens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            Log.d("MukkeNav", "tabClick=${screen.route}")
                            navController.navigateToTab(screen.route)
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Library.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Library.route) {
                LibraryScreen(
                    viewModel = libraryViewModel,
                    playerViewModel = playerViewModel,
                    onNavigateToNowPlaying = { navController.navigateToTab(Screen.NowPlaying.route) }
                )
            }
            composable(Screen.NowPlaying.route) {
                NowPlayingScreen(
                    viewModel = playerViewModel,
                    onNavigateToEqualizer = { navController.navigate("equalizer") },
                    onNavigateToQueue = { navController.navigate("queue") },
                )
            }
            composable("equalizer") {
                EqualizerScreen(onBack = { navController.popBackStack() })
            }
            composable("queue") {
                QueueScreen(viewModel = playerViewModel, onBack = { navController.popBackStack() })
            }
            composable(Screen.Sync.route) {
                SyncScreen(onNavigateToFailures = { navController.navigate("sync_failures") })
            }
            composable("sync_failures") {
                SyncFailuresScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Settings.route) {
                SettingsScreen(onNavigateToDebugLog = { navController.navigate("debug_log") })
            }
            composable("debug_log") {
                DebugLogScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
