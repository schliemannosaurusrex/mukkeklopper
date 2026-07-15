package de.schliemannosaurusrex.mukkeklopper.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Library : Screen("library", "Library", Icons.Default.LibraryMusic)
    object NowPlaying : Screen("now_playing", "Playing", Icons.Default.MusicNote)
    object Sync : Screen("sync", "Sync", Icons.Default.Sync)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

val bottomNavScreens = listOf(Screen.Library, Screen.NowPlaying, Screen.Sync, Screen.Settings)
