package com.example.utils

import android.util.Log

/**
 * Защита от Prompt Injection атак.
 * Экранирует опасные символы перед передачей в LLM.
 * 
 * ИСПРАВЛЕНИЯ:
 * - Улучшенное экранирование специальных символов
 * - Обработка пустых строк
 * - Логирование потенциальных атак
 * - Control character detection
 * - Validation методы
 */
object PromptSanitizer {
    private const val TAG = "PromptSanitizer"
    private const val MAX_LENGTH = 1000
    
    /**
     * Экранирует пользовательский контент для безопасной передачи в LLM.
     * Предотвращает prompt injection атаки путём экранирования специальных директив.
     */
    fun sanitize(text: String): String {
        if (text.isEmpty()) return ""
        
        var sanitized = text
            // Экранировать кавычки
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace("`", "\\`")
            // Экранировать символы новой строки
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            // Удалить опасные директивы для LLM
            .replace(Regex("""\{[{%#].*?[}%#]\}"""), "")
            // Удалить управляющие последовательности
            .replace(Regex("""\\x[0-9a-fA-F]{2}"""), "")
            // Ограничить длину
            .take(MAX_LENGTH)
            .trim()
        
        // Логирование если обнаружены потенциальные атаки
        if (sanitized != text) {
            Log.w(TAG, "⚠️ Potential injection attempt detected and sanitized")
        }
        
        return sanitized
    }

    /**
     * Экранирует JSON-строку для безопасного встраивания в JSON.
     */
    fun sanitizeJson(text: String): String {
        return sanitize(text)
            .replace("\\", "\\\\")
            .replace("/", "\\/")
    }

    /**
     * Валидирует URL перед использованием.
     * Проверяет на опасные символы и валидный формат.
     */
    fun isValidYoutubeUrl(url: String): Boolean {
        if (url.isEmpty() || url.length > 2000) {
            Log.w(TAG, "⚠️ Invalid YouTube URL - incorrect length")
            return false
        }
        
        val isValid = (url.contains("youtube.com/watch") || url.contains("youtu.be/")) &&
                url.startsWith("http") &&
                !url.contains("<") &&
                !url.contains(">") &&
                !url.contains("\"") &&
                !url.contains("'") &&
                !url.contains("`")
        
        if (!isValid) {
            Log.w(TAG, "⚠️ Invalid YouTube URL detected")
        }
        
        return isValid
    }
    
    /**
     * Валидирует текст перед использованием в системе.
     * Проверяет на управляющие символы и некорректное содержимое.
     */
    fun isValidText(text: String): Boolean {
        if (text.isEmpty() || text.length > 10000) {
            Log.w(TAG, "⚠️ Text validation failed - incorrect length")
            return false
        }
        
        // Проверить на управляющие символы
        val hasControlChars = text.any { it.code < 32 && it !in listOf('\n', '\r', '\t') }
        
        if (hasControlChars) {
            Log.w(TAG, "⚠️ Text with control characters detected")
            return false
        }
        
        return true
    }
}
