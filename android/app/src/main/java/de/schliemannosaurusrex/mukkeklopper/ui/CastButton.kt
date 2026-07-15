package de.schliemannosaurusrex.mukkeklopper.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

/**
 * Cast-Button (Library- und Now-Playing-Topbar, PLAN „Debug-Log, Sync-Fix, Queue, Cast"
 * Punkt 4). `MediaRouteButton` öffnet den System-Gerätewähler selbst und lässt sich nicht
 * abfangen — die Local-Network-Permission (für [de.schliemannosaurusrex.mukkeklopper.cast.LocalMediaServer])
 * wird deshalb proaktiv angefragt, sobald der Button das erste Mal sichtbar wird, statt erst
 * beim Klick.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    val requestLocalNetworkPermission = rememberWithLocalNetworkPermission { }
    LaunchedEffect(Unit) { requestLocalNetworkPermission() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            MediaRouteButton(context).also { button ->
                CastButtonFactory.setUpMediaRouteButton(context, button)
            }
        },
    )
}
