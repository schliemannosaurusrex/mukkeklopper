package de.schliemannosaurusrex.kaniamp.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import de.schliemannosaurusrex.kaniamp.settings.KaniSettings
import de.schliemannosaurusrex.kaniamp.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.net.InetSocketAddress
import java.net.Socket
import java.security.PublicKey

sealed interface SyncDecision {
    data object Allowed : SyncDecision
    data object NotConfigured : SyncDecision
    data object Blocked : SyncDecision
    data class MitmWarning(val pinned: String, val presented: String) : SyncDecision
    data class Unreachable(val reason: String) : SyncDecision
}

/**
 * Gate vor jedem Sync-Start (PLAN.md Phase 3):
 * [1] Heimnetz-Check (Modus A: TCP-Probe, Modus B: SSID),
 * [2] VPN-Check außerhalb des Heimnetzes,
 * [3] SSH-Handshake mit Host-Key-Pinning (TOFU, kein Auto-Retry bei Mismatch).
 */
class NetworkGatekeeper(
    context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val appContext = context.applicationContext

    suspend fun canSync(): SyncDecision = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()
        if (settings.serverHost.isBlank()) return@withContext SyncDecision.NotConfigured

        if (!isHomeNetwork(settings) && settings.requireVpnOutsideHome && !isVpnActive()) {
            return@withContext SyncDecision.Blocked
        }
        verifyHostKey(settings)
    }

    private fun isHomeNetwork(settings: KaniSettings): Boolean =
        if (settings.syncModeBEnabled) {
            // SSID null (Permission entzogen, kein WLAN, …) → Fallback auf Modus A,
            // damit fehlende Berechtigungen den Sync nicht komplett blockieren
            when (val ssid = currentSsid()) {
                null -> isServerReachable(settings.serverHost, settings.serverPort)
                else -> ssid in settings.homeSsids
            }
        } else {
            isServerReachable(settings.serverHost, settings.serverPort)
        }

    private fun isServerReachable(host: String, port: Int): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), TCP_PROBE_TIMEOUT_MS) }
            true
        }.getOrDefault(false)

    private fun isVpnActive(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    // WifiInfo über den NetworkCallback-Weg braucht API 31; connectionInfo liefert die SSID
    // auf minSdk 30 mit ACCESS_FINE_LOCATION (Modus B fordert diese Permission an).
    @Suppress("DEPRECATION")
    private fun currentSsid(): String? {
        val wm = appContext.getSystemService(WifiManager::class.java) ?: return null
        // SecurityException, falls ACCESS_WIFI_STATE/ACCESS_FINE_LOCATION zur Laufzeit fehlen
        val ssid = runCatching { wm.connectionInfo?.ssid }.getOrNull()?.trim('"') ?: return null
        return ssid.takeUnless { it.isEmpty() || it == WifiManager.UNKNOWN_SSID }
    }

    private suspend fun verifyHostKey(settings: KaniSettings): SyncDecision {
        var presentedKey: String? = null
        val client = SSHClient()
        try {
            client.connectTimeout = SSH_TIMEOUT_MS
            client.timeout = SSH_TIMEOUT_MS
            client.addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                    presentedKey = SecurityUtils.getFingerprint(key)
                    // Handshake immer abschließen; die Pinning-Entscheidung fällt unten,
                    // damit ein Mismatch als MITM_WARNING statt als Transportfehler endet.
                    return true
                }

                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
                    emptyList()
            })
            client.connect(settings.serverHost, settings.serverPort)
        } catch (e: Exception) {
            return SyncDecision.Unreachable(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { client.disconnect() }
        }

        val presented = presentedKey
            ?: return SyncDecision.Unreachable("Server presented no host key")
        val pinned = settings.pinnedHostKey
        return when {
            pinned.isEmpty() -> {
                settingsRepository.setPinnedHostKey(presented)
                SyncDecision.Allowed
            }
            pinned == presented -> SyncDecision.Allowed
            else -> SyncDecision.MitmWarning(pinned = pinned, presented = presented)
        }
    }

    private companion object {
        const val TCP_PROBE_TIMEOUT_MS = 3_000
        const val SSH_TIMEOUT_MS = 5_000
    }
}
