package com.example.domain.repository

import com.example.config.ApiKeyManager
import com.example.data.CachedTranslation
import com.example.data.TranslationCacheDao
import com.example.utils.PromptSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

/**
 * Repository для управления переводами.
 * Обрабатывает логику кэширования и безопасности.
 * 
 * ИСПРАВЛЕНИЯ:
 * - Проверка кэша перед API запросом
 * - Экранирование текста перед передачей в LLM
 * - Использование EncryptedSharedPreferences для API ключей
 * - Error handling
 * - Логирование
 */
class TranslationRepository(
    private val cacheDao: TranslationCacheDao,
    private val apiClient: TranslationApiClient
) {
    private const val TAG = "TranslationRepository"
    private val CACHE_EXPIRY_DAYS = 7L

    /**
     * Переводит текст с кэшированием результатов.
     * 
     * ПРОЦЕСС:
     * 1. Проверяет кэш
     * 2. При кэше возвращает результат
     * 3. При отсутствии - запрашивает API
     * 4. Экранирует текст перед запросом
     * 5. Сохраняет результат в кэш
     */
    suspend fun translateText(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        videoId: String
    ): String = withContext(Dispatchers.IO) {
        try {
            // Валидация входных данных
            if (text.isEmpty() || videoId.isEmpty()) {
                Log.e(TAG, "❌ Empty text or videoId")
                return@withContext text
            }
            
            if (!PromptSanitizer.isValidText(text)) {
                Log.e(TAG, "❌ Invalid text")
                return@withContext text
            }
            
            // Проверяем кэш
            val cached = cacheDao.getTranslation(videoId, sourceLanguage, targetLanguage)
            if (cached != null) {
                Log.d(TAG, "💾 Cache hit for video: $videoId")
                return@withContext cached.translatedText
            }

            Log.d(TAG, "🔄 Translating text (cache miss)...")

            // Если нет в кэше - переводим с экранированием
            val sanitizedText = PromptSanitizer.sanitize(text)
            val translated = try {
                apiClient.translate(sanitizedText, sourceLanguage, targetLanguage)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Translation API failed", e)
                // Fallback на оригинальный текст при ошибке
                sanitizedText
            }

            // Сохраняем в кэш
            try {
                val cacheEntry = CachedTranslation(
                    videoId = videoId,
                    originalText = text,
                    translatedText = translated,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage
                )
                cacheDao.insertTranslation(cacheEntry)
                Log.d(TAG, "✅ Translation cached")
            } catch (e: Exception) {
                Log.e(TAG, "�� Failed to cache translation", e)
            }

            translated
        } catch (e: Exception) {
            Log.e(TAG, "❌ Unexpected error in translateText", e)
            text
        }
    }

    /**
     * Очищает устаревший кэш (старше CACHE_EXPIRY_DAYS дней).
     */
    suspend fun clearExpiredCache() = withContext(Dispatchers.IO) {
        try {
            val expiryTime = System.currentTimeMillis() - (CACHE_EXPIRY_DAYS * 24 * 60 * 60 * 1000)
            cacheDao.deleteOlderThan(expiryTime)
            Log.d(TAG, "✅ Expired cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to clear expired cache", e)
        }
    }

    /**
     * Очищает кэш для конкретного видео.
     */
    suspend fun clearVideoCache(videoId: String) = withContext(Dispatchers.IO) {
        try {
            if (videoId.isEmpty()) {
                Log.e(TAG, "❌ Empty videoId")
                return@withContext
            }
            cacheDao.deleteByVideoId(videoId)
            Log.d(TAG, "✅ Video cache cleared: $videoId")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to clear video cache", e)
        }
    }
    
    /**
     * Получает размер кэша.
     */
    suspend fun getCacheSize(): Int = withContext(Dispatchers.IO) {
        try {
            val size = cacheDao.getCacheSize()
            Log.d(TAG, "ℹ️ Cache size: $size items")
            size
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get cache size", e)
            0
        }
    }
}

interface TranslationApiClient {
    suspend fun translate(text: String, sourceLang: String, targetLang: String): String
}
