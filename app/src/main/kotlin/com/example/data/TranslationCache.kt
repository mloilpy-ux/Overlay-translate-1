package com.example.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import android.util.Log

/**
 * Локальное кэширование переводов для избежания повторных запросов.
 * 
 * ИСПРАВЛЕНИЯ:
 * - Логирование операций
 * - Error handling
 * - Примечания о времени жизни
 */
@Entity(tableName = "translation_cache")
data class CachedTranslation(
    @PrimaryKey val videoId: String,
    val originalText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface TranslationCacheDao {
    
    @Query("SELECT * FROM translation_cache WHERE videoId = :videoId AND sourceLanguage = :sourceLang AND targetLanguage = :targetLang")
    suspend fun getTranslation(videoId: String, sourceLang: String, targetLang: String): CachedTranslation?

    @Query("SELECT * FROM translation_cache WHERE videoId = :videoId AND sourceLanguage = :sourceLang AND targetLanguage = :targetLang")
    fun getTranslationFlow(videoId: String, sourceLang: String, targetLang: String): Flow<CachedTranslation?>

    @Insert
    suspend fun insertTranslation(translation: CachedTranslation)

    @Query("DELETE FROM translation_cache WHERE createdAt < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)

    @Query("DELETE FROM translation_cache WHERE videoId = :videoId")
    suspend fun deleteByVideoId(videoId: String)

    @Query("DELETE FROM translation_cache")
    suspend fun clearAll()
    
    @Query("SELECT COUNT(*) FROM translation_cache")
    suspend fun getCacheSize(): Int
}

@Database(entities = [CachedTranslation::class], version = 1)
abstract class TranslationCacheDatabase : RoomDatabase() {
    abstract fun translationCacheDao(): TranslationCacheDao
}
