package de.schliemannosaurusrex.mukkeklopper.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.schliemannosaurusrex.mukkeklopper.player.EqualizerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    viewModel: EqualizerViewModel = viewModel(),
    onBack: () -> Unit,
) {
    val capabilities by viewModel.capabilities.collectAsState()
    val settings by viewModel.settings.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Equalizer") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        val caps = capabilities
        if (caps == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Equalizer unavailable — start playback first", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Enable equalizer",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = settings.enabled, onCheckedChange = { viewModel.setEnabled(it) })
            }

            Spacer(Modifier.height(16.dp))
            Text("Preset", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                caps.presets.forEachIndexed { index, name ->
                    FilterChip(
                        selected = settings.presetIndex == index,
                        onClick = { viewModel.setPreset(index) },
                        enabled = settings.enabled,
                        label = { Text(name) },
                    )
                }
                FilterChip(
                    selected = settings.presetIndex == -1,
                    onClick = {},
                    enabled = false,
                    label = { Text("Custom") },
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("Bands", style = MaterialTheme.typography.labelLarge)
            caps.bandCenterFreqHz.forEachIndexed { index, freq ->
                val level = settings.bandLevels.getOrElse(index) { 0 }
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatFrequency(freq), style = MaterialTheme.typography.bodySmall)
                        Text(formatDecibel(level), style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = level.toFloat(),
                        onValueChange = { viewModel.setBandLevel(index, it.toInt()) },
                        valueRange = caps.minLevelMillibel.toFloat()..caps.maxLevelMillibel.toFloat(),
                        enabled = settings.enabled,
                    )
                }
            }

            if (caps.bassBoostSupported) {
                Spacer(Modifier.height(12.dp))
                Text("Bass boost", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = settings.bassBoostStrength.toFloat(),
                    onValueChange = { viewModel.setBassBoostStrength(it.toInt()) },
                    valueRange = caps.strengthRange.first.toFloat()..caps.strengthRange.last.toFloat(),
                    enabled = settings.enabled,
                )
            }
            if (caps.virtualizerSupported) {
                Spacer(Modifier.height(12.dp))
                Text("Virtualizer", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = settings.virtualizerStrength.toFloat(),
                    onValueChange = { viewModel.setVirtualizerStrength(it.toInt()) },
                    valueRange = caps.strengthRange.first.toFloat()..caps.strengthRange.last.toFloat(),
                    enabled = settings.enabled,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun formatFrequency(hz: Int): String =
    if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"

private fun formatDecibel(millibel: Int): String =
    "%.1f dB".format(millibel / 100.0)
