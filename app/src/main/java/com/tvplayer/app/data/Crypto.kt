package com.tvplayer.app.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts saved data (server passwords, and lists whose links contain credentials)
 * with an AES-256 key kept in the Android Keystore, so it never leaves the device
 * and can't be read from a copied settings file. Android 5 (API 21-22) has no
 * Keystore AES, so data stays as-is there.
 */
object Crypto {
    private const val ALIAS = "tvplayer_data_key"
    private const val PREFIX = "enc1:"
    @Volatile private var cachedKey: SecretKey? = null

    fun encrypt(plain: String): String {
        if (plain.isEmpty() || Build.VERSION.SDK_INT < 23) return plain
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: Exception) {
            plain
        }
    }

    fun decrypt(value: String): String {
        if (!value.startsWith(PREFIX) || Build.VERSION.SDK_INT < 23) return value
        return try {
            val all = Base64.decode(value.substring(PREFIX.length), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    @RequiresApi(23)
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(ALIAS, null) as? SecretKey
        val k = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
        cachedKey = k
        return k
    }

    // ---------- PIN hashing ----------

    fun newSalt(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    /** Salted, iterated SHA-256 so the PIN isn't stored in a readable form. */
    fun hashPin(pin: String, salt: String): String {
        var d = (salt + pin).toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        repeat(20_000) { d = md.digest(d) }
        return Base64.encodeToString(d, Base64.NO_WRAP)
    }
}
