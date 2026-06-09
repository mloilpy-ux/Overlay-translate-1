package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*

/**
 * ViewModel для сохранения состояния при повороте экрана.
 * Предотвращает потерю данных и переозапуск операций при изменении конфигурации.
 * 
 * ИСПРАВЛЕНИЯ:
 * - Защита от null-pointer exceptions
 * - Правильное управление lifecycle
 * - Очистка ресурсов в onCleared
 */
class MainViewModel : ViewModel() {
    
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

    fun updateYoutubeUrl(url: String) {
        _youtubeUrl.value = url.trim()
    }

    fun updateErrorMessage(message: String?) {
        _errorMessage.value = message
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun addChatMessage(message: ChatMessage) {
        val currentList = _aiAssistantChat.value.toMutableList()
        currentList.add(message)
        _aiAssistantChat.value = currentList.toList()
    }

    fun updateTranslationSettings(settings: TranslationSettings) {
        _translationSettings.value = settings
    }

    fun clearChat() {
        _aiAssistantChat.value = emptyList()
    }
    
    fun removeChatMessage(messageId: String) {
        val currentList = _aiAssistantChat.value.toMutableList()
        currentList.removeAll { it.id == messageId }
        _aiAssistantChat.value = currentList.toList()
    }
    
    fun clearAllState() {
        _youtubeUrl.value = ""
        _isLoading.value = false
        _errorMessage.value = null
        _aiAssistantChat.value = emptyList()
        _translationSettings.value = TranslationSettings()
    }

    override fun onCleared() {
        super.onCleared()
        // Очищаем состояние перед уничтожением ViewModel
        clearChat()
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
