package de.schliemannosaurusrex.kaniamp.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

// Ab Android 17 (API 37) blockt Local Network Protection LAN-Zugriffe ohne diese
// Runtime-Permission — Pakete werden stillschweigend verworfen (Connect-Timeout).
// compileSdk 35 kennt die Manifest-Konstante noch nicht, daher als String.
private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
private const val API_ANDROID_17 = 37

/**
 * Umhüllt [action] mit dem ACCESS_LOCAL_NETWORK-Request: auf API < 37 läuft die
 * Aktion direkt, sonst erst nach erteilter Permission (Request bei Bedarf).
 */
@Composable
fun rememberWithLocalNetworkPermission(action: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentAction by rememberUpdatedState(action)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) currentAction()
    }
    return remember(context, launcher) {
        {
            val granted = Build.VERSION.SDK_INT < API_ANDROID_17 ||
                ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) currentAction() else launcher.launch(LOCAL_NETWORK_PERMISSION)
        }
    }
}
