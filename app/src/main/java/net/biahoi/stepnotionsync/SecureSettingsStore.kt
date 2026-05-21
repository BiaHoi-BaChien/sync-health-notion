package net.biahoi.stepnotionsync

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEY_ALIAS = "health_notion_sync_settings"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val TOKEN_CIPHERTEXT_KEY = "tokenCiphertext"
private const val TOKEN_IV_KEY = "tokenIv"
private const val LEGACY_TOKEN_KEY = "token"

internal object SecureSettingsStore {
    fun loadToken(prefs: SharedPreferences): String {
        val ciphertext = prefs.getString(TOKEN_CIPHERTEXT_KEY, null)
        val iv = prefs.getString(TOKEN_IV_KEY, null)
        if (!ciphertext.isNullOrBlank() && !iv.isNullOrBlank()) {
            return runCatching { decrypt(ciphertext, iv) }
                .getOrElse {
                    prefs.edit()
                        .remove(TOKEN_CIPHERTEXT_KEY)
                        .remove(TOKEN_IV_KEY)
                        .remove(LEGACY_TOKEN_KEY)
                        .apply()
                    ""
                }
        }

        val legacyToken = prefs.getString(LEGACY_TOKEN_KEY, "") ?: ""
        if (legacyToken.isNotBlank()) {
            saveToken(prefs, legacyToken)
        }
        return legacyToken
    }

    fun saveToken(prefs: SharedPreferences, token: String) {
        if (token.isBlank()) {
            prefs.edit()
                .remove(TOKEN_CIPHERTEXT_KEY)
                .remove(TOKEN_IV_KEY)
                .remove(LEGACY_TOKEN_KEY)
                .apply()
            return
        }

        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(TOKEN_CIPHERTEXT_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(TOKEN_IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .remove(LEGACY_TOKEN_KEY)
            .apply()
    }

    private fun decrypt(ciphertext: String, iv: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP))
        )
        val plaintext = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        return plaintext.toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }
}
