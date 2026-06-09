package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*
import android.util.Log

/**
 * ViewModel для сохранения состояния при повороте экрана.
 * Предотвращает потерю данных и переозапуск операций при изменении конфигурации.
 * 
 * ИСПРАВЛЕНИЯ:
 * - Защита от null-pointer exceptions
 * - Правильное управление lifecycle
 * - Очистка ресурсов в onCleared
 * - Error handling
 * - Логирование
 */
class MainViewModel : ViewModel() {
    private const val TAG = "MainViewModel"
    
    private val _youtubeUrl = MutableStateFlow("")
    val youtubeUrl: StateFlow<String> = _youtubeUrl.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _aiAssistantChat = MutableStateFlow(emptyList<ChatMessage>())
    val aiAssistantChat: StateFlow<List<ChatMessage>> = _aiAssistantChat.asStateFlow()

    private val _translationSettings = MutableStateFlow(TranslationSettings())
    val translationSettings: StateFlow<TranslationSettings> = _translationSettings.asStateFlow()

    init {
        Log.d(TAG, "✅ MainViewModel initialized")
    }

    fun updateYoutubeUrl(url: String) {
        try {
            val trimmedUrl = url.trim()
            if (trimmedUrl.isEmpty()) {
                Log.w(TAG, "⚠️ Empty URL provided")
            }
            _youtubeUrl.value = trimmedUrl
            Log.d(TAG, "✅ YouTube URL updated")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating URL", e)
        }
    }

    fun updateErrorMessage(message: String?) {
        try {
            _errorMessage.value = message
            if (message != null) {
                Log.e(TAG, "❌ Error: $message")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating error message", e)
        }
    }

    fun setLoading(loading: Boolean) {
        try {
            _isLoading.value = loading
            Log.d(TAG, "${if (loading) "⏳" else "✅"} Loading state: $loading")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting loading state", e)
        }
    }

    fun addChatMessage(message: ChatMessage) {
        try {
            val currentList = _aiAssistantChat.value.toMutableList()
            currentList.add(message)
            _aiAssistantChat.value = currentList.toList()
            Log.d(TAG, "💬 Chat message added (total: ${currentList.size})")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error adding chat message", e)
        }
    }

    fun updateTranslationSettings(settings: TranslationSettings) {
        try {
            _translationSettings.value = settings
            Log.d(TAG, "✅ Translation settings updated: ${settings.sourceLanguage} -> ${settings.targetLanguage}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating translation settings", e)
        }
    }

    fun clearChat() {
        try {
            _aiAssistantChat.value = emptyList()
            Log.d(TAG, "🗑️ Chat cleared")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing chat", e)
        }
    }
    
    fun removeChatMessage(messageId: String) {
        try {
            val currentList = _aiAssistantChat.value.toMutableList()
            val removed = currentList.removeAll { it.id == messageId }
            if (removed) {
                _aiAssistantChat.value = currentList.toList()
                Log.d(TAG, "🗑️ Chat message removed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error removing chat message", e)
        }
    }
    
    fun clearAllState() {
        try {
            _youtubeUrl.value = ""
            _isLoading.value = false
            _errorMessage.value = null
            _aiAssistantChat.value = emptyList()
            _translationSettings.value = TranslationSettings()
            Log.d(TAG, "🔄 All state cleared")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing all state", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            clearChat()
            Log.d(TAG, "♻️ MainViewModel cleared")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onCleared", e)
        }
    }
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUserMessage: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class TranslationSettings(
    val sourceLanguage: String = "en",
    val targetLanguage: String = "ru",
    val enableAudio: Boolean = true,
    val useAiAssistant: Boolean = false
)
