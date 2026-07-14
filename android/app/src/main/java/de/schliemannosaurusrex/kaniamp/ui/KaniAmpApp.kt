package de.schliemannosaurusrex.kaniamp.ui

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
import de.schliemannosaurusrex.kaniamp.library.LibraryViewModel
import de.schliemannosaurusrex.kaniamp.player.PlayerViewModel

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
fun KaniAmpApp() {
    val navController = rememberNavController()
    val playerViewModel: PlayerViewModel = viewModel()
    val libraryViewModel: LibraryViewModel = viewModel()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    LaunchedEffect(navController) {
        @SuppressLint("RestrictedApi")
        navController.currentBackStack.collect { stack ->
            Log.d("KaniNav", "stack=" + stack.joinToString(" > ") {
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
                            Log.d("KaniNav", "tabClick=${screen.route}")
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
                NowPlayingScreen(viewModel = playerViewModel)
            }
            composable(Screen.Sync.route) {
                SyncScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
