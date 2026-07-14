package de.schliemannosaurusrex.kaniamp.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.schliemannosaurusrex.kaniamp.sync.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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

data class KaniSettings(
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
)

class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore
    private val secretStore = SecretStore()

    val settings: Flow<KaniSettings> = dataStore.data.map { prefs ->
        KaniSettings(
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

    private companion object {
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
    }
}
