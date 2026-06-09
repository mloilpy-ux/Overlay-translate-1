package com.example.utils

/**
 * Защита от Prompt Injection атак.
 * Экранирует опасные символы перед передачей в LLM.
 */
object PromptSanitizer {
    
    /**
     * Экранирует пользовательский контент для безопасной передачи в LLM.
     * Предотвращает prompt injection атаки путём экранирования специальных директив.
     */
    fun sanitize(text: String): String {
        return text
            // Экранировать кавычки
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            // Экранировать символы новой строки
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            // Удалить опасные директивы
            .replace(Regex("""\{[{%#].*?[}%#]\}"""), "")
            // Ограничить длину
            .take(1000)
            .trim()
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
     */
    fun isValidYoutubeUrl(url: String): Boolean {
        return (url.contains("youtube.com/watch") || url.contains("youtu.be/")) &&
                url.startsWith("http") &&
                !url.contains("<") &&
                !url.contains(">") &&
                !url.contains("\"")
    }
}
