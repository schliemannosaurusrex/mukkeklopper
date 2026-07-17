package de.schliemannosaurusrex.mukkeklopper.settings

import android.util.Base64
import de.schliemannosaurusrex.mukkeklopper.debug.AppLog
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Baut/parst das JSON-Backup-Format und wendet es auf die [SettingsRepository] an. */
object ConfigExporter {

    private const val TAG = "ConfigExporter"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    suspend fun export(
        repository: SettingsRepository,
        includeCredentials: Boolean,
        passphrase: String?,
    ): String {
        val settings = repository.settings.first()

        val secrets = if (includeCredentials && !passphrase.isNullOrEmpty()) {
            val payload = SecretsPayload(
                authMethod = settings.authMethod.storageValue,
                password = if (settings.authMethod == AuthMethod.PASSWORD) repository.getPassword() else null,
                sshKeySeedBase64 = if (settings.authMethod == AuthMethod.PUBLIC_KEY) {
                    repository.getSshSeed()?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                } else null,
            )
            val plaintext = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
            ConfigCrypto.encrypt(plaintext, passphrase)
        } else null

        val export = ConfigExport(
            server = ServerConfig(
                host = settings.serverHost,
                port = settings.serverPort,
                user = settings.serverUser,
                remoteBasePath = settings.remoteBasePath,
                pinnedHostKey = settings.pinnedHostKey,
            ),
            network = NetworkConfig(
                requireVpnOutsideHome = settings.requireVpnOutsideHome,
                syncModeBEnabled = settings.syncModeBEnabled,
                homeSsids = settings.homeSsids,
            ),
            library = LibraryConfig(
                libraryRoot = settings.libraryRoot,
                viewMode = settings.libraryViewMode.storageValue,
            ),
            sync = SyncConfig(autoSyncEnabled = settings.autoSyncEnabled),
            player = PlayerConfig(equalizer = repository.equalizerSettings.first()),
            debug = DebugConfig(debugLogEnabled = settings.debugLogEnabled),
            secrets = secrets,
        )
        AppLog.i(TAG, "exported config (includeCredentials=$includeCredentials)")
        return json.encodeToString(export)
    }

    /** @throws SerializationException bei ungültigem JSON oder fehlenden Pflichtfeldern. */
    fun parse(text: String): ConfigExport {
        val config = json.decodeFromString<ConfigExport>(text)
        // v1-Dateien bleiben importierbar: die seit v2 ergänzten Felder sind nullable
        // mit Default und fehlen dort einfach.
        if (config.version !in ConfigExport.MIN_SUPPORTED_VERSION..ConfigExport.CURRENT_VERSION) {
            throw SerializationException("Unsupported config version: ${config.version}")
        }
        return config
    }

    /** @throws Exception falls die Passphrase falsch ist (AEADBadTagException) oder Secrets fehlen. */
    suspend fun apply(repository: SettingsRepository, config: ConfigExport, passphrase: String?) {
        AppLog.i(TAG, "importing config: host=${config.server.host} hasSecrets=${config.secrets != null}")
        repository.setServerHost(config.server.host)
        repository.setServerPort(config.server.port.coerceIn(1, 65535))
        repository.setPinnedHostKey(config.server.pinnedHostKey)
        repository.setServerUser(config.server.user)
        repository.setRemoteBasePath(config.server.remoteBasePath)

        repository.setRequireVpnOutsideHome(config.network.requireVpnOutsideHome)
        repository.setSyncModeBEnabled(config.network.syncModeBEnabled)
        repository.setHomeSsids(config.network.homeSsids)

        repository.setLibraryViewMode(LibraryViewMode.fromStorage(config.library.viewMode))
        if (config.library.libraryRoot.isNotEmpty()) {
            repository.setLibraryRoot(config.library.libraryRoot)
        } else {
            repository.clearLibraryRoot()
        }

        repository.setAutoSyncEnabled(config.sync.autoSyncEnabled)

        // Seit v2 vorhanden; bei v1-Dateien bleiben die Ziel-Werte unverändert.
        config.player?.let { repository.setEqualizerSettings(it.equalizer) }
        config.debug?.let { repository.setDebugLogEnabled(it.debugLogEnabled) }

        val secrets = config.secrets
        if (secrets != null && !passphrase.isNullOrEmpty()) {
            val decrypted = ConfigCrypto.decrypt(secrets, passphrase)
            val payload = json.decodeFromString<SecretsPayload>(String(decrypted, Charsets.UTF_8))
            repository.setAuthMethod(AuthMethod.fromStorage(payload.authMethod))
            payload.password?.let { repository.setPassword(it) }
            payload.sshKeySeedBase64?.let { repository.setSshSeed(Base64.decode(it, Base64.NO_WRAP)) }
        }
    }
}
