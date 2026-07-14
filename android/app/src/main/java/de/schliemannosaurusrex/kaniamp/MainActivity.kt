package de.schliemannosaurusrex.kaniamp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.schliemannosaurusrex.kaniamp.ui.KaniAmpApp
import de.schliemannosaurusrex.kaniamp.ui.theme.KaniAmpTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KaniAmpTheme {
                KaniAmpApp()
            }
        }
    }
}
