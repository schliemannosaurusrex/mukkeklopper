package de.schliemannosaurusrex.mukkeklopper

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import de.schliemannosaurusrex.mukkeklopper.ui.MukkeKlopperApp
import de.schliemannosaurusrex.mukkeklopper.ui.theme.MukkeKlopperTheme

/**
 * [FragmentActivity] statt [androidx.activity.ComponentActivity] — androidx.mediarouter's
 * `MediaRouteButton` (Cast-Button) zeigt seinen Geräte-Auswahldialog über eine `DialogFragment`
 * und wirft sonst `IllegalStateException: The activity must be a subclass of FragmentActivity`
 * beim Antippen (am Gerät verifizierter Crash). `setContent`/Compose funktionieren unverändert,
 * da `FragmentActivity` selbst eine `ComponentActivity`-Unterklasse ist.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MukkeKlopperTheme {
                MukkeKlopperApp()
            }
        }
    }
}
