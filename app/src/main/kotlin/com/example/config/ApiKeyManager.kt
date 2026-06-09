package com.example.config

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Безопасное управление API-ключами.
 * Использует зашифрованное хранилище вместо BuildConfig.
 */
object ApiKeyManager {
    private const val PREFS_NAME = "api_keys_prefs"
    private const val GEMINI_KEY = "gemini_api_key"

    private lateinit var prefs: EncryptedSharedPreferences

    fun init(context: Context) {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }

    fun getGeminiApiKey(): String? = prefs.getString(GEMINI_KEY, null)

    fun setGeminiApiKey(key: String) {
        prefs.edit().putString(GEMINI_KEY, key).apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(GEMINI_KEY).apply()
    }

    fun hasApiKey(): Boolean = getGeminiApiKey() != null
}
