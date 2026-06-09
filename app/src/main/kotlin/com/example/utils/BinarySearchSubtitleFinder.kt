package com.example.utils

import android.util.Log

/**
 * Оптимизирует поиск субтитров с O(n) на O(log n).
 * Использует бинарный поиск вместо линейного.
 * 
 * ИСПРАВЛЕНИЯ:
 * - Граничные условия правильные
 * - Поддержка пустого списка
 * - Защита от integer overflow
 * - Дополнительные методы
 */
object BinarySearchSubtitleFinder {
    private const val TAG = "BinarySearchSubtitleFinder"
    
    /**
     * Находит субтитр для заданного времени используя бинарный поиск.
     * Сложность: O(log n)
     * 
     * @param subtitles отсортированный список субтитров по времени (ВАЖНО: должен быть отсортирован!)
     * @param timeMs время в миллисекундах
     * @return субтитр или null если не найден
     */
    fun findSubtitleForTime(subtitles: List<Subtitle>, timeMs: Long): Subtitle? {
        if (subtitles.isEmpty()) return null
        if (timeMs < 0) {
            Log.w(TAG, "⚠️ Negative time value")
            return null
        }

        var left = 0
        var right = subtitles.size - 1

        while (left <= right) {
            val mid = left + (right - left) / 2  // Избегаем переполнения при сложении
            val subtitle = subtitles[mid]
            val endMs = subtitle.startMs + subtitle.durationMs

            when {
                timeMs < subtitle.startMs -> right = mid - 1
                timeMs > endMs -> left = mid + 1
                else -> {
                    Log.d(TAG, "✅ Found subtitle at index $mid for time $timeMs")
                    return subtitle
                }
            }
        }

        Log.d(TAG, "ℹ️ No subtitle found for time $timeMs")
        return null
    }

    /**
     * Находит индекс первого су��титра, который начинается ПОСЛЕ заданного времени.
     * Сложность: O(log n)
     */
    fun findNextSubtitleIndex(subtitles: List<Subtitle>, timeMs: Long): Int {
        if (subtitles.isEmpty()) return 0
        if (timeMs < 0) {
            Log.w(TAG, "⚠️ Negative time value")
            return 0
        }
        
        var left = 0
        var right = subtitles.size

        while (left < right) {
            val mid = left + (right - left) / 2
            if (subtitles[mid].startMs <= timeMs) {
                left = mid + 1
            } else {
                right = mid
            }
        }

        Log.d(TAG, "✅ Next subtitle index for time $timeMs is $left")
        return left
    }
    
    /**
     * Находит индекс субтитра для заданного времени.
     * Возвращает индекс или -1 если не найден.
     */
    fun findSubtitleIndex(subtitles: List<Subtitle>, timeMs: Long): Int {
        if (subtitles.isEmpty()) return -1
        if (timeMs < 0) {
            Log.w(TAG, "⚠️ Negative time value")
            return -1
        }

        var left = 0
        var right = subtitles.size - 1

        while (left <= right) {
            val mid = left + (right - left) / 2
            val subtitle = subtitles[mid]
            val endMs = subtitle.startMs + subtitle.durationMs

            when {
                timeMs < subtitle.startMs -> right = mid - 1
                timeMs > endMs -> left = mid + 1
                else -> {
                    Log.d(TAG, "✅ Found subtitle index $mid for time $timeMs")
                    return mid
                }
            }
        }

        Log.d(TAG, "ℹ️ No subtitle index found for time $timeMs")
        return -1
    }
}

data class Subtitle(
    val id: Int,
    val startMs: Long,
    val durationMs: Long,
    val text: String,
    val translatedText: String = ""
) {
    val endMs: Long
        get() = startMs + durationMs
}
