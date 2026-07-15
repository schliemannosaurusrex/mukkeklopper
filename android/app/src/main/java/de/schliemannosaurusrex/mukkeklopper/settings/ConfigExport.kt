package de.schliemannosaurusrex.mukkeklopper.settings

import kotlinx.serialization.Serializable

/**
 * Export-/Import-Format für die Einstellungen (PLAN.md Phase 4b). Secrets sind
 * standardmäßig nicht enthalten; nur mit explizitem Opt-in als [EncryptedSecrets].
 */
@Serializable
data class ConfigExport(
    val version: Int = CURRENT_VERSION,
    val server: ServerConfig,
    val network: NetworkConfig,
    val library: LibraryConfig,
    val sync: SyncConfig,
    val secrets: EncryptedSecrets? = null,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class ServerConfig(
    val host: String,
    val port: Int,
    val user: String,
    val remoteBasePath: String,
    val pinnedHostKey: String,
)

@Serializable
data class NetworkConfig(
    val requireVpnOutsideHome: Boolean,
    val syncModeBEnabled: Boolean,
    val homeSsids: Set<String>,
)

@Serializable
data class LibraryConfig(
    val libraryRoot: String,
    val viewMode: String,
)

@Serializable
data class SyncConfig(
    val autoSyncEnabled: Boolean,
)

/** Passphrase-verschlüsselter Umschlag um [SecretsPayload] (PBKDF2 → AES-256-GCM). */
@Serializable
data class EncryptedSecrets(
    val salt: String,
    val iv: String,
    val ciphertext: String,
    val iterations: Int,
)

@Serializable
data class SecretsPayload(
    val authMethod: String,
    val password: String? = null,
    val sshKeySeedBase64: String? = null,
)
