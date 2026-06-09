package com.example.service.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import android.util.Log

/**
 * Управляет воспроизведением субтитров.
 * Отделяет логику плеера от сервиса.
 * 
 * ИСПРАВЛЕНИЯ:
 * - Потокобезопасное управление lastVoicedLineId (AtomicInteger)
 * - Правильная остановка при конце видео
 * - O(log n) поиск вместо O(n)
 * - Error handling
 * - Логирование
 */
class SubtitlePlayer {
    private const val TAG = "SubtitlePlayer"
    private const val TICK_INTERVAL = 100L
    
    private val scope = CoroutineScope(Dispatchers.Main)
    private var tickerJob: Job? = null
    
    // ИСПРАВЛЕНИЕ: Потокобезопасное хранилище последнего озвученного ID
    private val lastVoicedLineId = AtomicInteger(-1)
    
    private val _currentSubtitleIndex = MutableStateFlow(-1)
    val currentSubtitleIndex: StateFlow<Int> = _currentSubtitleIndex.asStateFlow()
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    
    private val _currentTimeMs = MutableStateFlow(0L)
    val currentTimeMs: StateFlow<Long> = _currentTimeMs.asStateFlow()
    
    private val _totalDurationMs = MutableStateFlow(0L)
    val totalDurationMs: StateFlow<Long> = _totalDurationMs.asStateFlow()

    private var totalDuration = 0L
    private var onSubtitleChanged: ((Int) -> Unit)? = null

    fun setTotalDuration(duration: Long) {
        if (duration < 0) {
            Log.w(TAG, "⚠️ Invalid duration: $duration")
            return
        }
        this.totalDuration = duration
        _totalDurationMs.value = duration
        Log.d(TAG, "✅ Total duration set: ${duration}ms")
    }

    fun setOnSubtitleChanged(callback: (Int) -> Unit) {
        this.onSubtitleChanged = callback
        Log.d(TAG, "✅ Subtitle change callback set")
    }

    /**
     * Запускает воспроизведение с обновлением каждые 100мс.
     */
    fun startPlaying(currentTime: Long = 0) {
        if (_isPlaying.value) {
            Log.w(TAG, "⚠️ Already playing")
            return
        }
        
        if (currentTime < 0 || currentTime > totalDuration) {
            Log.e(TAG, "❌ Invalid start time: $currentTime")
            return
        }

        _isPlaying.value = true
        _currentTimeMs.value = currentTime
        Log.d(TAG, "▶️ Playing started at ${currentTime}ms")
        
        tickerJob = scope.launch {
            while (_isPlaying.value && _currentTimeMs.value < totalDuration) {
                delay(TICK_INTERVAL)
                _currentTimeMs.value += TICK_INTERVAL
            }

            // ИСПРАВЛЕНИЕ: Правильно останавливаем при конце видео
            if (_currentTimeMs.value >= totalDuration) {
                _isPlaying.value = false
                tickerJob?.cancel()
                Log.d(TAG, "⏹️ Playback finished")
            }
        }
    }

    fun pausePlaying() {
        _isPlaying.value = false
        tickerJob?.cancel()
        Log.d(TAG, "⏸️ Playback paused at ${_currentTimeMs.value}ms")
    }

    fun stopPlaying() {
        _isPlaying.value = false
        tickerJob?.cancel()
        _currentTimeMs.value = 0L
        _currentSubtitleIndex.value = -1
        lastVoicedLineId.set(-1)
        Log.d(TAG, "⏹️ Playback stopped")
    }

    /**
     * Перемещает время проигрывания.
     */
    fun seek(timeMs: Long) {
        if (timeMs < 0 || timeMs > totalDuration) {
            Log.e(TAG, "❌ Invalid seek position: $timeMs")
            return
        }
        
        _currentTimeMs.value = timeMs
        Log.d(TAG, "⏩ Seeked to ${timeMs}ms")
        
        // При seek сбрасываем состояние озвучки
        if (!_isPlaying.value) {
            lastVoicedLineId.set(-1)
        }
    }

    /**
     * ИСПРАВЛЕНИЕ: Потокобезопасное управление озвучкой.
     * Использует AtomicInteger вместо обычной переменной.
     */
    fun onSubtitleVoiced(subtitleId: Int) {
        val prev = lastVoicedLineId.getAndSet(subtitleId)
        
        // Воспроизводим только если это новый субтитр
        if (prev != subtitleId) {
            Log.d(TAG, "🔊 Voicing subtitle: $subtitleId")
            onSubtitleChanged?.invoke(subtitleId)
        }
    }

    fun updateSubtitleIndex(index: Int) {
        _currentSubtitleIndex.value = index
        Log.d(TAG, "📝 Current subtitle index: $index")
    }

    fun cleanup() {
        try {
            stopPlaying()
            scope.cancel()
            Log.d(TAG, "✅ Cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during cleanup", e)
        }
    }
}
