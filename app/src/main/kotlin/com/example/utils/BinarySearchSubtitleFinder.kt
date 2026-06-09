package com.example.utils

/**
 * Оптимизирует поиск субтитров с O(n) на O(log n).
 * Использует бинарный поиск вместо линейного.
 */
object BinarySearchSubtitleFinder {
    
    /**
     * Находит субтитр для заданного времени используя бинарный поиск.
     * 
     * @param subtitles отсортированный список субтитров по времени
     * @param timeMs время в миллисекундах
     * @return субтитр или null если не найден
     */
    fun findSubtitleForTime(subtitles: List<Subtitle>, timeMs: Long): Subtitle? {
        if (subtitles.isEmpty()) return null

        var left = 0
        var right = subtitles.size - 1

        while (left <= right) {
            val mid = (left + right) / 2
            val subtitle = subtitles[mid]
            val endMs = subtitle.startMs + subtitle.durationMs

            when {
                timeMs < subtitle.startMs -> right = mid - 1
                timeMs > endMs -> left = mid + 1
                else -> return subtitle
            }
        }

        return null
    }

    /**
     * Находит индекс первого субтитра, который начинается после заданного времени.
     */
    fun findNextSubtitleIndex(subtitles: List<Subtitle>, timeMs: Long): Int {
        var left = 0
        var right = subtitles.size

        while (left < right) {
            val mid = (left + right) / 2
            if (subtitles[mid].startMs <= timeMs) {
                left = mid + 1
            } else {
                right = mid
            }
        }

        return left
    }
}

data class Subtitle(
    val id: Int,
    val startMs: Long,
    val durationMs: Long,
    val text: String,
    val translatedText: String = ""
)
