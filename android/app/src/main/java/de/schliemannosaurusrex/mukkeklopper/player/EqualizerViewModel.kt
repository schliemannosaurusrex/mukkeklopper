package de.schliemannosaurusrex.mukkeklopper.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

class EqualizerViewModel(application: Application) : AndroidViewModel(application) {

    val capabilities: StateFlow<EqualizerCapabilities?> = EqualizerManager.capabilities
    val settings: StateFlow<EqualizerSettings> = EqualizerManager.settings

    fun setEnabled(enabled: Boolean) = EqualizerManager.setEnabled(getApplication(), enabled)

    fun setPreset(index: Int) = EqualizerManager.setPreset(getApplication(), index)

    fun setBandLevel(band: Int, levelMillibel: Int) =
        EqualizerManager.setBandLevel(getApplication(), band, levelMillibel)

    fun setBassBoostStrength(strength: Int) = EqualizerManager.setBassBoostStrength(getApplication(), strength)

    fun setVirtualizerStrength(strength: Int) = EqualizerManager.setVirtualizerStrength(getApplication(), strength)
}
