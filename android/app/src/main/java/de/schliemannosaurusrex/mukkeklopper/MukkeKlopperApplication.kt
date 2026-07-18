package de.schliemannosaurusrex.mukkeklopper

import android.app.Application
import de.schliemannosaurusrex.mukkeklopper.debug.AppLog
import de.schliemannosaurusrex.mukkeklopper.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class MukkeKlopperApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Muss vor jedem anderen AppLog-Aufruf laufen, damit auch der allererste
        // Eintrag dieses Prozesslaufs in die persistente Datei geschrieben wird
        // (siehe AppLog — reines In-Memory hätte Prozess-Neustarts nicht überstanden).
        AppLog.init(this)
        // Androids eingebauter "BC"-Provider ist beschnitten (u. a. kein X25519).
        // sshj fordert Algorithmen explizit beim Provider "BC" an — deshalb das
        // System-BC durch das volle BouncyCastle aus den Dependencies ersetzen,
        // sonst scheitert der SSH-Key-Exchange (curve25519-sha256).
        val removed = Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
        AppLog.i(TAG, "BouncyCastle provider replaced (previous removed=$removed)")

        // Debug-Log-Toggle aus den Settings laden und live beobachten (Settings > Debug log).
        val repository = SettingsRepository(this)
        appScope.launch {
            repository.settings
                .map { it.debugLogEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    AppLog.setDebugEnabled(enabled)
                    AppLog.i(TAG, "Debug logging ${if (enabled) "enabled" else "disabled"}")
                }
        }

        // Einmalige Migration für Geräte, auf denen "library_root" noch von vor der
        // KaniAmp→MukkeKlopper-Umbenennung stammt (siehe SettingsRepository.migrateLegacyLibraryRoot).
        appScope.launch {
            repository.migrateLegacyLibraryRoot()
            AppLog.i(TAG, "Legacy library root migration checked")
        }
    }

    private companion object {
        const val TAG = "MukkeApp"
    }
}
