package de.schliemannosaurusrex.mukkeklopper.settings

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-basierte Verschlüsselung für den optionalen "Include credentials"-Export
 * (PLAN.md Phase 4b). Der Android-Keystore-Schlüssel selbst ist nicht exportierbar,
 * deshalb dieser zweite, vom Nutzer beim Export/Import eingegebene Umschlag.
 */
object ConfigCrypto {
    private const val ITERATIONS = 210_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_SIZE = 16
    private const val TAG_BITS = 128

    fun encrypt(plaintext: ByteArray, passphrase: String): EncryptedSecrets {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, ITERATIONS))
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedSecrets(
            salt = Base64.encodeToString(salt, Base64.NO_WRAP),
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            iterations = ITERATIONS,
        )
    }

    fun decrypt(encrypted: EncryptedSecrets, passphrase: String): ByteArray {
        val salt = Base64.decode(encrypted.salt, Base64.NO_WRAP)
        val iv = Base64.decode(encrypted.iv, Base64.NO_WRAP)
        val ciphertext = Base64.decode(encrypted.ciphertext, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(passphrase, salt, encrypted.iterations),
            GCMParameterSpec(TAG_BITS, iv),
        )
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
}
