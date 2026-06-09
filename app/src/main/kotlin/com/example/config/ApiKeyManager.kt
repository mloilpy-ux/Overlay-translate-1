package com.example.config

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.util.Log

/**
 * Безопасное управление API-ключами.
 * Использует зашифрованное хранилище вместо BuildConfig.
 * 
 * ИСПРАВЛЕНИЯ:
 * - Проверка инициализации перед использованием
 * - Error handling с try-catch
 * - Логирование для отладки
 * - Null safety
 */
object ApiKeyManager {
    private const val TAG = "ApiKeyManager"
    private const val PREFS_NAME = "api_keys_prefs"
    private const val GEMINI_KEY = "gemini_api_key"

    private var prefs: EncryptedSharedPreferences? = null
    private var initialized = false

    fun init(context: Context) {
        try {
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
            
            initialized = true
            Log.d(TAG, "✅ ApiKeyManager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize ApiKeyManager", e)
            initialized = false
        }
    }

    fun getGeminiApiKey(): String? {
        if (!initialized) {
            Log.w(TAG, "⚠️ ApiKeyManager not initialized. Call init(context) first!")
            return null
        }
        
        return try {
            prefs?.getString(GEMINI_KEY, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to retrieve API key", e)
            null
        }
    }

    fun setGeminiApiKey(key: String) {
        if (!initialized) {
            Log.w(TAG, "⚠️ ApiKeyManager not initialized. Call init(context) first!")
            return
        }
        
        if (key.isEmpty()) {
            Log.e(TAG, "❌ API key is empty")
            return
        }
        
        try {
            prefs?.edit()?.putString(GEMINI_KEY, key)?.apply()
            Log.d(TAG, "✅ API key set successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to set API key", e)
        }
    }

    fun clearApiKey() {
        if (!initialized) {
            Log.w(TAG, "⚠️ ApiKeyManager not initialized")
            return
        }
        
        try {
            prefs?.edit()?.remove(GEMINI_KEY)?.apply()
            Log.d(TAG, "✅ API key cleared successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to clear API key", e)
        }
    }

    fun hasApiKey(): Boolean {
        return getGeminiApiKey() != null
    }
    
    fun isInitialized(): Boolean = initialized
}
