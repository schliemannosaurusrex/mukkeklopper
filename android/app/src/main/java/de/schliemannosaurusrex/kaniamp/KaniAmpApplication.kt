package de.schliemannosaurusrex.kaniamp

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class KaniAmpApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Androids eingebauter "BC"-Provider ist beschnitten (u. a. kein X25519).
        // sshj fordert Algorithmen explizit beim Provider "BC" an — deshalb das
        // System-BC durch das volle BouncyCastle aus den Dependencies ersetzen,
        // sonst scheitert der SSH-Key-Exchange (curve25519-sha256).
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
    }
}
