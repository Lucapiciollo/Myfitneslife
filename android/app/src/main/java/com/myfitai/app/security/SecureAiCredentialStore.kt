package com.myfitai.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class AiCredentialProvider(val storageKey: String) {
    OPENAI("openai"),
    GEMINI("gemini"),
}

/** Stores only AES-GCM ciphertext and IV; plaintext keys exist only during a request. */
class SecureAiCredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDebuggable(): Boolean = (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun save(provider: AiCredentialProvider, apiKey: String) {
        val normalized = apiKey.trim()
        require(normalized.isNotEmpty()) { "API key must not be blank" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(normalized.toByteArray(StandardCharsets.UTF_8))
        prefs.edit()
            .putString(ciphertextKey(provider), Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(ivKey(provider), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun read(provider: AiCredentialProvider): String? {
        val ciphertext = prefs.getString(ciphertextKey(provider), null) ?: return null
        val iv = prefs.getString(ivKey(provider), null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    fun exists(provider: AiCredentialProvider): Boolean =
        !prefs.getString(ciphertextKey(provider), null).isNullOrBlank() &&
            !prefs.getString(ivKey(provider), null).isNullOrBlank()

    fun delete(provider: AiCredentialProvider) {
        prefs.edit().remove(ciphertextKey(provider)).remove(ivKey(provider)).apply()
    }

    private fun ciphertextKey(provider: AiCredentialProvider) = "${provider.storageKey}_key_ciphertext"
    private fun ivKey(provider: AiCredentialProvider) = "${provider.storageKey}_key_iv"

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return keyGenerator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        // Keep the shipped OpenAI alias so existing encrypted OpenAI credentials remain readable.
        const val KEY_ALIAS = "myfitai_openai_api_key_aes_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val PREFS_NAME = "secure_ai_credentials"
    }
}
