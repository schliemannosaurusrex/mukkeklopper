package de.schliemannosaurusrex.mukkeklopper.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.schliemannosaurusrex.mukkeklopper.player.EqualizerSettings
import de.schliemannosaurusrex.mukkeklopper.player.PlaybackState
import de.schliemannosaurusrex.mukkeklopper.sync.SecretStore
import de.schliemannosaurusrex.mukkeklopper.sync.SyncFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class AuthMethod(val storageValue: String) {
    PASSWORD("password"),
    PUBLIC_KEY("publickey");

    companion object {
        fun fromStorage(value: String?): AuthMethod =
            entries.firstOrNull { it.storageValue == value } ?: PASSWORD
    }
}

enum class LibraryViewMode(val storageValue: String, val label: String) {
    FOLDERS("folders", "Folders"),
    ALBUMS("albums", "Albums"),
    ARTISTS("artists", "Artists"),
    TRACKS("tracks", "All tracks");

    companion object {
        fun fromStorage(value: String?): LibraryViewMode =
            entries.firstOrNull { it.storageValue == value } ?: FOLDERS
    }
}

data class MukkeSettings(
    val serverHost: String = "",
    val serverPort: Int = 22,
    val pinnedHostKey: String = "",
    val requireVpnOutsideHome: Boolean = true,
    val syncModeBEnabled: Boolean = false,
    val homeSsids: Set<String> = emptySet(),
    val serverUser: String = "",
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val hasPassword: Boolean = false,
    val hasSshKey: Boolean = false,
    val remoteBasePath: String = "",
    val autoSyncEnabled: Boolean = false,
    /** Startverzeichnis der Library (MediaStore-RELATIVE_PATH); leer = Wurzel. */
    val libraryRoot: String = "",
    val libraryViewMode: LibraryViewMode = LibraryViewMode.FOLDERS,
    /** Zuschaltbares Debug-Log (Settings > Debug log) — siehe [de.schliemannosaurusrex.mukkeklopper.debug.AppLog]. */
    val debugLogEnabled: Boolean = false,
)

class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore
    private val secretStore = SecretStore()

    val settings: Flow<MukkeSettings> = dataStore.data.map { prefs ->
        MukkeSettings(
            serverHost = prefs[SERVER_HOST] ?: "",
            serverPort = prefs[SERVER_PORT] ?: 22,
            pinnedHostKey = prefs[PINNED_HOSTKEY] ?: "",
            requireVpnOutsideHome = prefs[REQUIRE_VPN_OUTSIDE_HOME] ?: true,
            syncModeBEnabled = prefs[SYNC_MODE_B_ENABLED] ?: false,
            homeSsids = prefs[HOME_SSIDS] ?: emptySet(),
            serverUser = prefs[SERVER_USER] ?: "",
            authMethod = AuthMethod.fromStorage(prefs[AUTH_METHOD]),
            hasPassword = !prefs[AUTH_SECRET].isNullOrEmpty(),
            hasSshKey = !prefs[SSH_KEY_SEED].isNullOrEmpty(),
            remoteBasePath = prefs[REMOTE_BASE_PATH] ?: "",
            autoSyncEnabled = prefs[AUTO_SYNC_ENABLED] ?: false,
            libraryRoot = prefs[LIBRARY_ROOT] ?: "",
            libraryViewMode = LibraryViewMode.fromStorage(prefs[LIBRARY_VIEW_MODE]),
            debugLogEnabled = prefs[DEBUG_LOG_ENABLED] ?: false,
        )
    }

    suspend fun setLibraryRoot(path: String) {
        dataStore.edit { it[LIBRARY_ROOT] = path }
    }

    suspend fun clearLibraryRoot() {
        dataStore.edit { it.remove(LIBRARY_ROOT) }
    }

    suspend fun setLibraryViewMode(mode: LibraryViewMode) {
        dataStore.edit { it[LIBRARY_VIEW_MODE] = mode.storageValue }
    }

    suspend fun setServerHost(host: String) {
        dataStore.edit { it[SERVER_HOST] = host.trim() }
    }

    suspend fun setServerPort(port: Int) {
        require(port in 1..65535) { "Port out of range: $port" }
        dataStore.edit { it[SERVER_PORT] = port }
    }

    suspend fun setPinnedHostKey(key: String) {
        dataStore.edit { it[PINNED_HOSTKEY] = key }
    }

    suspend fun clearPinnedHostKey() {
        dataStore.edit { it.remove(PINNED_HOSTKEY) }
    }

    suspend fun setRequireVpnOutsideHome(enabled: Boolean) {
        dataStore.edit { it[REQUIRE_VPN_OUTSIDE_HOME] = enabled }
    }

    suspend fun setSyncModeBEnabled(enabled: Boolean) {
        dataStore.edit { it[SYNC_MODE_B_ENABLED] = enabled }
    }

    suspend fun addHomeSsid(ssid: String) {
        val trimmed = ssid.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { it[HOME_SSIDS] = (it[HOME_SSIDS] ?: emptySet()) + trimmed }
    }

    suspend fun removeHomeSsid(ssid: String) {
        dataStore.edit { it[HOME_SSIDS] = (it[HOME_SSIDS] ?: emptySet()) - ssid }
    }

    /** Ersetzt die komplette SSID-Liste (Config-Import). */
    suspend fun setHomeSsids(ssids: Set<String>) {
        dataStore.edit { it[HOME_SSIDS] = ssids }
    }

    suspend fun setServerUser(user: String) {
        dataStore.edit { it[SERVER_USER] = user.trim() }
    }

    suspend fun setAuthMethod(method: AuthMethod) {
        dataStore.edit { it[AUTH_METHOD] = method.storageValue }
    }

    suspend fun setRemoteBasePath(path: String) {
        dataStore.edit { it[REMOTE_BASE_PATH] = path.trim() }
    }

    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        dataStore.edit { it[AUTO_SYNC_ENABLED] = enabled }
    }

    suspend fun setPassword(plain: String) {
        dataStore.edit { it[AUTH_SECRET] = secretStore.encrypt(plain.toByteArray(Charsets.UTF_8)) }
    }

    suspend fun clearPassword() {
        dataStore.edit { it.remove(AUTH_SECRET) }
    }

    suspend fun getPassword(): String? {
        val encrypted = dataStore.data.first()[AUTH_SECRET] ?: return null
        if (encrypted.isEmpty()) return null
        return runCatching { String(secretStore.decrypt(encrypted), Charsets.UTF_8) }.getOrNull()
    }

    suspend fun setSshSeed(seed: ByteArray) {
        dataStore.edit { it[SSH_KEY_SEED] = secretStore.encrypt(seed) }
    }

    suspend fun getSshSeed(): ByteArray? {
        val encrypted = dataStore.data.first()[SSH_KEY_SEED] ?: return null
        if (encrypted.isEmpty()) return null
        return runCatching { secretStore.decrypt(encrypted) }.getOrNull()
    }

    /** Fehler des letzten Sync-Laufs (nur der jeweils letzte, kein Verlauf). */
    val lastSyncReport: Flow<List<SyncFailure>> = dataStore.data.map { prefs ->
        prefs[LAST_SYNC_REPORT]?.let { json ->
            runCatching { Json.decodeFromString<List<SyncFailure>>(json) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun setLastSyncReport(failures: List<SyncFailure>) {
        dataStore.edit { it[LAST_SYNC_REPORT] = Json.encodeToString(failures) }
    }

    suspend fun clearLastSyncReport() {
        dataStore.edit { it.remove(LAST_SYNC_REPORT) }
    }

    /** Equalizer-Einstellungen (PLAN.md Phase 9) — Bandzahl ist geräteabhängig. */
    val equalizerSettings: Flow<EqualizerSettings> = dataStore.data.map { prefs ->
        prefs[EQUALIZER_SETTINGS]?.let { json ->
            runCatching { Json.decodeFromString<EqualizerSettings>(json) }.getOrNull()
        } ?: EqualizerSettings()
    }

    suspend fun setEqualizerSettings(settings: EqualizerSettings) {
        dataStore.edit { it[EQUALIZER_SETTINGS] = Json.encodeToString(settings) }
    }

    suspend fun setDebugLogEnabled(enabled: Boolean) {
        dataStore.edit { it[DEBUG_LOG_ENABLED] = enabled }
    }

    /** Zuletzt gespielte Queue + Position (Laufzeit-State, nicht im Config-Export). */
    val playbackState: Flow<PlaybackState?> = dataStore.data.map { prefs ->
        prefs[PLAYBACK_STATE]?.let { json ->
            runCatching { Json.decodeFromString<PlaybackState>(json) }.getOrNull()
        }
    }

    suspend fun setPlaybackState(state: PlaybackState) {
        dataStore.edit { it[PLAYBACK_STATE] = Json.encodeToString(state) }
    }

    suspend fun clearPlaybackState() {
        dataStore.edit { it.remove(PLAYBACK_STATE) }
    }

    /**
     * Einmalige Migration für Geräte, auf denen `library_root` noch auf den alten
     * `Music/KaniAmp/…`-Pfad zeigt (vor der Umbenennung gepinnt). Die physischen Dateien
     * bleiben an ihrem alten Ort liegen — nur der App-interne Startordner wird auf den
     * neuen Präfix `Music/MukkeKlopper/` umgeschrieben, damit die Library nicht dauerhaft
     * auf einem inzwischen von neuen Syncs ungenutzten Pfad "hängen bleibt".
     */
    suspend fun migrateLegacyLibraryRoot() {
        dataStore.edit { prefs ->
            val current = prefs[LIBRARY_ROOT] ?: return@edit
            if (current.startsWith(LEGACY_LIBRARY_ROOT_PREFIX)) {
                prefs[LIBRARY_ROOT] = NEW_LIBRARY_ROOT_PREFIX + current.removePrefix(LEGACY_LIBRARY_ROOT_PREFIX)
            }
        }
    }

    private companion object {
        const val LEGACY_LIBRARY_ROOT_PREFIX = "Music/KaniAmp/"
        const val NEW_LIBRARY_ROOT_PREFIX = "Music/MukkeKlopper/"
        val SERVER_HOST = stringPreferencesKey("server_host")
        val SERVER_PORT = intPreferencesKey("server_port")
        val PINNED_HOSTKEY = stringPreferencesKey("pinned_hostkey")
        val REQUIRE_VPN_OUTSIDE_HOME = booleanPreferencesKey("require_vpn_outside_home")
        val SYNC_MODE_B_ENABLED = booleanPreferencesKey("sync_mode_b_enabled")
        val HOME_SSIDS = stringSetPreferencesKey("home_ssids")
        val SERVER_USER = stringPreferencesKey("server_user")
        val AUTH_METHOD = stringPreferencesKey("auth_method")
        val AUTH_SECRET = stringPreferencesKey("auth_secret")
        val SSH_KEY_SEED = stringPreferencesKey("ssh_key_seed")
        val REMOTE_BASE_PATH = stringPreferencesKey("remote_base_path")
        val AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")
        val LIBRARY_ROOT = stringPreferencesKey("library_root")
        val LIBRARY_VIEW_MODE = stringPreferencesKey("library_view_mode")
        val LAST_SYNC_REPORT = stringPreferencesKey("last_sync_report")
        val EQUALIZER_SETTINGS = stringPreferencesKey("equalizer_settings")
        val DEBUG_LOG_ENABLED = booleanPreferencesKey("debug_log_enabled")
        val PLAYBACK_STATE = stringPreferencesKey("playback_state")
    }
}
