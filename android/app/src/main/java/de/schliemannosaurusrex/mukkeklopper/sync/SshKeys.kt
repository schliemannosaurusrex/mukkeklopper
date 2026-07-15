package de.schliemannosaurusrex.mukkeklopper.sync

import android.util.Base64
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom

/**
 * In-App generiertes Ed25519-Schlüsselpaar für die Publickey-Auth (PLAN.md Phase 4).
 * Persistiert wird nur der 32-Byte-Seed (verschlüsselt via [SecretStore]); Private
 * und Public Key werden daraus deterministisch abgeleitet. Die net.i2p-eddsa-Typen
 * sind genau die, die sshj für ssh-ed25519 erwartet.
 */
object SshKeys {

    private val curve = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

    fun generateSeed(): ByteArray = ByteArray(SEED_SIZE).also { SecureRandom().nextBytes(it) }

    fun keyProviderFromSeed(seed: ByteArray): KeyProvider {
        require(seed.size == SEED_SIZE) { "Ed25519 seed must be $SEED_SIZE bytes" }
        val privateSpec = EdDSAPrivateKeySpec(seed, curve)
        val privateKey = EdDSAPrivateKey(privateSpec)
        val publicKey = EdDSAPublicKey(EdDSAPublicKeySpec(privateSpec.a, curve))
        return object : KeyProvider {
            override fun getPrivate(): PrivateKey = privateKey
            override fun getPublic(): PublicKey = publicKey
            override fun getType(): KeyType = KeyType.ED25519
        }
    }

    /** OpenSSH-Zeile für authorized_keys: `ssh-ed25519 <base64> mukkeklopper@android`. */
    fun openSshPublicKey(seed: ByteArray): String {
        val publicKey = keyProviderFromSeed(seed).public
        val blob = Buffer.PlainBuffer().putPublicKey(publicKey).compactData
        return "ssh-ed25519 ${Base64.encodeToString(blob, Base64.NO_WRAP)} mukkeklopper@android"
    }

    private const val SEED_SIZE = 32
}
