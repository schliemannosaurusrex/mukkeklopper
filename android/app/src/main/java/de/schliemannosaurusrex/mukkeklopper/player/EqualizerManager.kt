package de.schliemannosaurusrex.mukkeklopper.player

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import de.schliemannosaurusrex.mukkeklopper.debug.AppLog
import de.schliemannosaurusrex.mukkeklopper.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Persistierte Equalizer-Einstellungen (PLAN.md Phase 9). */
@Serializable
data class EqualizerSettings(
    val enabled: Boolean = false,
    /** -1 = benutzerdefiniert (Bänder manuell verstellt), sonst Preset-Index. */
    val presetIndex: Int = -1,
    /** Pegel je Band in Millibel. */
    val bandLevels: List<Int> = emptyList(),
    val bassBoostStrength: Int = 0,
    val virtualizerStrength: Int = 0,
)

/** Statische Eigenschaften des am aktuellen Audio-Session angehängten Equalizers. */
data class EqualizerCapabilities(
    val numberOfBands: Int,
    val minLevelMillibel: Int,
    val maxLevelMillibel: Int,
    val bandCenterFreqHz: List<Int>,
    val presets: List<String>,
    val bassBoostSupported: Boolean,
    val virtualizerSupported: Boolean,
    val strengthRange: IntRange = 0..1000,
)

/**
 * Prozess-Singleton (analog [de.schliemannosaurusrex.mukkeklopper.sync.SyncManager]), das die
 * [Equalizer]/[BassBoost]/[Virtualizer]-Effekte an der aktuellen ExoPlayer-Audio-Session hält.
 * Der Player-Service meldet Sitzungswechsel über [attach]/[release]; die UI liest/ändert
 * Einstellungen ausschließlich über dieses Objekt, unabhängig davon, ob der Service läuft.
 */
object EqualizerManager {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var currentSessionId: Int = 0
    private var scope: CoroutineScope? = null

    private val _capabilities = MutableStateFlow<EqualizerCapabilities?>(null)
    val capabilities: StateFlow<EqualizerCapabilities?> = _capabilities.asStateFlow()

    private val _settings = MutableStateFlow(EqualizerSettings())
    val settings: StateFlow<EqualizerSettings> = _settings.asStateFlow()

    /** Vom Player-Service bei jedem `onAudioSessionIdChanged` aufgerufen. */
    fun attach(context: Context, sessionId: Int) {
        if (sessionId == 0 || sessionId == currentSessionId) return
        AppLog.d(TAG, "attach: audioSessionId=$sessionId (previous=$currentSessionId)")
        releaseEffects()
        currentSessionId = sessionId

        val eq = runCatching { Equalizer(0, sessionId) }.getOrNull() ?: run {
            AppLog.w(TAG, "Equalizer effect unavailable for session $sessionId")
            return
        }
        val bb = runCatching { BassBoost(0, sessionId) }.getOrNull()
        val vr = runCatching { Virtualizer(0, sessionId) }.getOrNull()
        equalizer = eq
        bassBoost = bb
        virtualizer = vr

        val bandCount = eq.numberOfBands.toInt()
        val range = eq.bandLevelRange
        _capabilities.value = EqualizerCapabilities(
            numberOfBands = bandCount,
            minLevelMillibel = range[0].toInt(),
            maxLevelMillibel = range[1].toInt(),
            bandCenterFreqHz = (0 until bandCount).map { eq.getCenterFreq(it.toShort()) / 1000 },
            presets = (0 until eq.numberOfPresets.toInt()).map { eq.getPresetName(it.toShort()) },
            bassBoostSupported = bb?.strengthSupported == true,
            virtualizerSupported = vr?.strengthSupported == true,
        )

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope?.cancel()
        scope = newScope
        newScope.launch {
            val repository = SettingsRepository(context.applicationContext)
            val stored = repository.equalizerSettings.first()
            val normalized = if (stored.bandLevels.size == bandCount) {
                stored
            } else {
                stored.copy(bandLevels = List(bandCount) { 0 }, presetIndex = -1)
            }
            _settings.value = normalized
            applyToEffects(normalized)
        }
    }

    /** Vom Player-Service bei `onDestroy` aufgerufen. */
    fun release() {
        releaseEffects()
        scope?.cancel()
        scope = null
        currentSessionId = 0
    }

    fun setEnabled(context: Context, enabled: Boolean) = update(context) { it.copy(enabled = enabled) }

    fun setPreset(context: Context, index: Int) {
        val eq = equalizer ?: return
        runCatching { eq.usePreset(index.toShort()) }
        val bandCount = _capabilities.value?.numberOfBands ?: 0
        val levels = (0 until bandCount).map { eq.getBandLevel(it.toShort()).toInt() }
        update(context) { it.copy(presetIndex = index, bandLevels = levels) }
    }

    fun setBandLevel(context: Context, band: Int, levelMillibel: Int) {
        update(context) { current ->
            val levels = current.bandLevels.toMutableList()
            if (band in levels.indices) levels[band] = levelMillibel
            current.copy(presetIndex = -1, bandLevels = levels)
        }
    }

    /**
     * Importierte Einstellungen (Config-Backup) auf die laufende Session anwenden.
     * Passt die Band-Levels an die Bandzahl des Geräts an (gleiche Normalisierung
     * wie in [attach]) — bei Mismatch werden die Levels auf 0 zurückgesetzt.
     */
    fun applyImported(context: Context, imported: EqualizerSettings) {
        val bandCount = _capabilities.value?.numberOfBands
        val normalized = if (bandCount == null || imported.bandLevels.size == bandCount) {
            imported
        } else {
            imported.copy(bandLevels = List(bandCount) { 0 }, presetIndex = -1)
        }
        update(context) { normalized }
    }

    fun setBassBoostStrength(context: Context, strength: Int) =
        update(context) { it.copy(bassBoostStrength = strength) }

    fun setVirtualizerStrength(context: Context, strength: Int) =
        update(context) { it.copy(virtualizerStrength = strength) }

    private fun update(context: Context, transform: (EqualizerSettings) -> EqualizerSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        applyToEffects(updated)
        val currentScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        currentScope.launch {
            runCatching { SettingsRepository(context.applicationContext).setEqualizerSettings(updated) }
        }
    }

    private fun applyToEffects(settings: EqualizerSettings) {
        runCatching { equalizer?.setEnabled(settings.enabled) }
        runCatching { bassBoost?.setEnabled(settings.enabled) }
        runCatching { virtualizer?.setEnabled(settings.enabled) }
        if (!settings.enabled) return
        settings.bandLevels.forEachIndexed { index, level ->
            runCatching { equalizer?.setBandLevel(index.toShort(), level.toShort()) }
        }
        runCatching { bassBoost?.setStrength(settings.bassBoostStrength.toShort()) }
        runCatching { virtualizer?.setStrength(settings.virtualizerStrength.toShort()) }
    }

    private fun releaseEffects() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
        _capabilities.value = null
    }

    private const val TAG = "EqualizerManager"
}
