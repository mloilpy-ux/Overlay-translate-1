package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.ui.theme.MyApplicationTheme
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.roundToInt

class SubtitleService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val CHANNEL_ID = "YT_SUB_NOTIF_CHANNEL"
    private val NOTIFICATION_ID = 2652

    private val binder = LocalBinder()
    
    // Lifecycle setup for embedding ComposeView inside WindowManager
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val job = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + job)

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // Players / Translators / TTS
    private var ttsHelper: TextToSpeechHelper? = null

    // Flows for App & Overlay UI State Synchronicity
    private val _serviceState = MutableStateFlow(ServiceState.IDLE)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _videoInfo = MutableStateFlow<VideoInfo?>(null)
    val videoInfo: StateFlow<VideoInfo?> = _videoInfo.asStateFlow()

    private val _currentSubtitleText = MutableStateFlow("")
    val currentSubtitleText: StateFlow<String> = _currentSubtitleText.asStateFlow()

    private val _currentProgressMs = MutableStateFlow(0L)
    val currentProgressMs: StateFlow<Long> = _currentProgressMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _voiceVolume = MutableStateFlow(0.8f)
    val voiceVolume: StateFlow<Float> = _voiceVolume.asStateFlow()

    private val _selectedVoice = MutableStateFlow("")
    val selectedVoice: StateFlow<String> = _selectedVoice.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<String>>(emptyList())
    val availableVoices: StateFlow<List<String>> = _availableVoices.asStateFlow()

    private val _isMinimized = MutableStateFlow(true)
    val isMinimized: StateFlow<Boolean> = _isMinimized.asStateFlow()

    private val _pitch = MutableStateFlow(1.0f)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    private val _rate = MutableStateFlow(1.0f)
    val rate: StateFlow<Float> = _rate.asStateFlow()

    private val _translationProgress = MutableStateFlow(0)
    val translationProgress: StateFlow<Int> = _translationProgress.asStateFlow()

    private val _errorMessage = MutableStateFlow("")
    val errorMessage: StateFlow<String> = _errorMessage.asStateFlow()

    // NEW state flows for Multiple Translation APIs, Language Auto-Detection, and Mixing Sliders
    private val _selectedTranslationApi = MutableStateFlow("gemini")
    val selectedTranslationApi: StateFlow<String> = _selectedTranslationApi.asStateFlow()

    private val _detectedLanguage = MutableStateFlow("Узнается...")
    val detectedLanguage: StateFlow<String> = _detectedLanguage.asStateFlow()

    private val _originalVideoVolume = MutableStateFlow(0.7f)
    val originalVideoVolume: StateFlow<Float> = _originalVideoVolume.asStateFlow()

    private val _translationMasterVolume = MutableStateFlow(0.8f)
    val translationMasterVolume: StateFlow<Float> = _translationMasterVolume.asStateFlow()

    private val _autoSyncEnabled = MutableStateFlow(false)
    val autoSyncEnabled: StateFlow<Boolean> = _autoSyncEnabled.asStateFlow()

    private var tickerJob: Job? = null
    private var lastVoicedLineId = -1
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    private lateinit var prefs: SharedPreferences

    inner class LocalBinder : Binder() {
        fun getService(): SubtitleService = this@SubtitleService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        savedStateRegistryController.performRestore(null)
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        Log.d("SubtitleService", "[STEP] onCreate service started. Initializing preferences and configuration.")

        prefs = getSharedPreferences("YTSettings", Context.MODE_PRIVATE)
        _voiceVolume.value = prefs.getFloat("voiceVolume", 0.8f)
        _pitch.value = prefs.getFloat("pitch", 1.0f)
        _rate.value = prefs.getFloat("rate", 1.0f)
        _selectedVoice.value = prefs.getString("selectedVoice", "") ?: ""

        // Restore new variables
        _selectedTranslationApi.value = prefs.getString("translationApi", "gemini") ?: "gemini"
        _originalVideoVolume.value = prefs.getFloat("originalVideoVolume", getSystemMediaVolume())
        _translationMasterVolume.value = prefs.getFloat("translationMasterVolume", 0.8f)
        _autoSyncEnabled.value = prefs.getBoolean("autoSyncEnabled", false)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        Log.d("SubtitleService", "[ACTION] Initializing TextToSpeech (TTS) Engine Helper for Russian localized voiceover.")
        ttsHelper = TextToSpeechHelper(this) {
            _availableVoices.value = ttsHelper?.getAvailableVoices() ?: emptyList()
            Log.d("SubtitleService", "[ACTION] TTS Engine localized init callback complete. Available Russian voices found: ${_availableVoices.value.size}")
            if (_selectedVoice.value.isNotEmpty()) {
                ttsHelper?.setVoice(_selectedVoice.value)
            } else if (_availableVoices.value.isNotEmpty()) {
                _selectedVoice.value = _availableVoices.value.first()
                ttsHelper?.setVoice(_selectedVoice.value)
            }
        }

        createNotificationChannel()

        // We do not call startForeground or setupOverlayWindow() in onCreate during binding.
        // Doing so would start a foreground service session from background before user interaction,
        // which is restricted in newer Android versions.

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerAudioPlaybackListener()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val url = intent?.getStringExtra("VIDEO_URL")
        Log.d("SubtitleService", "[STEP] onStartCommand triggered with action: '$action', URL parameter: '${url ?: "empty"}'")
        
        if (action == "STOP_SERVICE") {
            Log.d("SubtitleService", "[STEP] STOP_SERVICE command received. Shutting down foreground service.")
            stopSelf()
            return START_NOT_STICKY
        }

        // Trigger startForeground with appropriate foreground service type for media playback on API 29+
        Log.d("SubtitleService", "[ACTION] Triggering startForeground with MEDIA_PLAYBACK type in onStartCommand.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                createNotification("Инициализация оверлея..."), 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(
                NOTIFICATION_ID, 
                createNotification("Инициализация оверлея...")
            )
        }

        // Try to initialize overlay window in case permission was granted after binding
        setupOverlayWindow()

        if (!url.isNullOrEmpty()) {
            loadVideoAndTranslate(url)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d("SubtitleService", "[STEP] onDestroy triggered. Cleaning up all listeners, tickers, overlay windows, and TTS instances.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            unregisterAudioPlaybackListener()
        }
        stopTicker()
        ttsHelper?.shutdown()
        removeOverlayWindow()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        job.cancel()
        super.onDestroy()
    }

    // Load video transcription scraping and translation
    fun loadVideoAndTranslate(url: String) {
        Log.d("SubtitleService", "[STEP] loadVideoAndTranslate triggered for URL: $url")
        _serviceState.value = ServiceState.LOADING_VIDEO
        _errorMessage.value = ""
        _currentSubtitleText.value = "Загрузка информации о видео..."
        _translationProgress.value = 0
        lastVoicedLineId = -1
        _currentProgressMs.value = 0L

        serviceScope.launch {
            try {
                Log.d("SubtitleService", "[STEP] Analyzing video link scraping HTML watch page...")
                updateNotification("Анализ видео ссылки...")
                val rawVideoInfo = YouTubeCaptionsScraper.fetchVideoInfo(url)
                
                // If scraping failed completely or blocked, build custom fallback model referencing the URL!
                val resolvedVideoInfo = if (rawVideoInfo == null) {
                    Log.w("SubtitleService", "[STEP] YouTube Scraper returned null (blocked or network failure). Crafting placeholder metadata for URL: $url")
                    val videoId = YouTubeCaptionsScraper.extractVideoId(url) ?: "unknown_id"
                    VideoInfo(
                        id = videoId,
                        title = "YouTube Video ($videoId)",
                        description = "Фоновое описание недоступно",
                        durationString = "00:00",
                        subtitles = emptyList()
                    )
                } else {
                    Log.d("SubtitleService", "[STEP] YouTube Scraper returned valid info for '${rawVideoInfo.title}' with ${rawVideoInfo.subtitles.size} original caption lines.")
                    rawVideoInfo
                }

                _videoInfo.value = resolvedVideoInfo
                val apiKey = BuildConfig.GEMINI_API_KEY
                val isGoogle = _selectedTranslationApi.value == "google"

                Log.d("SubtitleService", "[STEP] Verifying credentials. Has API Key: ${apiKey.isNotEmpty() && apiKey != "MY_GEMINI_API_KEY"}, Current Translation Engine: ${if (isGoogle) "Google" else "Gemini"}")
                if ((resolvedVideoInfo.subtitles.isEmpty() || !isGoogle) && (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY")) {
                    Log.e("SubtitleService", "[STEP] Access key missing for Gemini. Aborting operations.")
                    _serviceState.value = ServiceState.ERROR
                    _errorMessage.value = "Пропущен GEMINI_API_KEY! Пожалуйста, добавьте ваш API-ключ в левую панель 'Secrets' в AI Studio."
                    _currentSubtitleText.value = "Ошибка: Добавьте GEMINI_API_KEY в панели Secrets в AI Studio."
                    return@launch
                }

                // AUTO-DETECT Language & Update Status
                val detectedCode = GeminiSubtitleTranslator.detectLanguageOfSubtitles(resolvedVideoInfo.subtitles)
                _detectedLanguage.value = GeminiSubtitleTranslator.getLanguageDisplayName(detectedCode)
                Log.d("SubtitleService", "[STEP] Original video language auto-detected: $detectedCode (${_detectedLanguage.value})")

                if (resolvedVideoInfo.subtitles.isEmpty()) {
                    // No subtitles available, let Gemini generate fallback overview subtitles
                    Log.d("SubtitleService", "[STEP] Captions list is empty. Triggering Gemini to generate a synced Russian narrative timeline for: $url")
                    _serviceState.value = ServiceState.TRANSLATING
                    _currentSubtitleText.value = "Оригинальные субтитры не найдены. Gemini генерирует адаптацию..."
                    updateNotification("Синтез описания видео через Gemini AI...")

                    val fallbackLines = GeminiSubtitleTranslator.generateFallbackSubtitles(
                        url = url,
                        title = resolvedVideoInfo.title,
                        description = resolvedVideoInfo.description,
                        apiKey = apiKey
                    )

                    Log.d("SubtitleService", "[STEP] Gemini generated fallback timelines successfully. Count: ${fallbackLines.size} entries.")
                    _videoInfo.value = resolvedVideoInfo.copy(subtitles = fallbackLines)
                    _serviceState.value = ServiceState.PAUSED
                    _durationMs.value = fallbackLines.lastOrNull()?.let { it.startMs + it.durationMs } ?: 60000L
                    _currentSubtitleText.value = "Адаптация готова. Нажмите Play для озвучки!"
                    updateNotification("Озвучка готова к воспроизведению: ${resolvedVideoInfo.title}")
                } else {
                    _serviceState.value = ServiceState.TRANSLATING
                    Log.d("SubtitleService", "[STEP] Native captions found. Translating ${resolvedVideoInfo.subtitles.size} lines using engine: ${if (isGoogle) "Google Translate" else "Gemini AI"}")
                    
                    if (isGoogle) {
                        _currentSubtitleText.value = "Перевод при помощи Google Translate (0%)..."
                        updateNotification("Перевод при помощи Google Translate...")

                        val translatedLines = GeminiSubtitleTranslator.translateSubtitlesGoogle(
                            subtitles = resolvedVideoInfo.subtitles
                        ) { progress, total ->
                            val pct = if (total > 0) (progress * 100) / total else 0
                            _translationProgress.value = pct
                            _currentSubtitleText.value = "Перевод при помощи Google Translate ($pct%)..."
                        }

                        _videoInfo.value = resolvedVideoInfo.copy(subtitles = translatedLines)
                    } else {
                        _currentSubtitleText.value = "Перевод субтитров при помощи Gemini AI (0%)..."
                        updateNotification("Перевод субтитров при помощи Gemini AI...")

                        val translatedLines = GeminiSubtitleTranslator.translateSubtitles(
                            subtitles = resolvedVideoInfo.subtitles,
                            apiKey = apiKey
                        ) { progress, total ->
                            val pct = if (total > 0) (progress * 100) / total else 0
                            _translationProgress.value = pct
                            _currentSubtitleText.value = "Перевод при помощи Gemini AI ($pct%)..."
                        }

                        _videoInfo.value = resolvedVideoInfo.copy(subtitles = translatedLines)
                    }

                    val finalLines = _videoInfo.value?.subtitles ?: emptyList()
                    Log.d("SubtitleService", "[STEP] Subtitles translation complete. Total tracks: ${finalLines.size}")
                    _serviceState.value = ServiceState.PAUSED
                    _durationMs.value = finalLines.lastOrNull()?.let { it.startMs + it.durationMs } ?: 0L
                    _currentSubtitleText.value = "Перевод завершен! Нажмите Play для синхронизации."
                    updateNotification("Перевод завершен! Готово к воспроизведению")
                }
            } catch (e: Exception) {
                Log.e("SubtitleService", "[STEP] Extreme failure loading or translating video url content", e)
                _serviceState.value = ServiceState.ERROR
                _errorMessage.value = "Ошибка: ${e.localizedMessage}"
                _currentSubtitleText.value = "Ошибка при загрузке. Проверьте API ключ."
            }
        }
    }

    fun startPlaying() {
        Log.d("SubtitleService", "[STEP] startPlaying triggered. Transitioning translation to active playback state.")
        if (_serviceState.value == ServiceState.PLAYING || _serviceState.value == ServiceState.PAUSED) {
            _serviceState.value = ServiceState.PLAYING
            startTicker()
        }
    }

    fun pausePlaying() {
        Log.d("SubtitleService", "[STEP] pausePlaying triggered. Halting tts voice playback and canceling ticker.")
        if (_serviceState.value == ServiceState.PLAYING) {
            _serviceState.value = ServiceState.PAUSED
            ttsHelper?.stop()
        }
    }

    fun seekTo(progressMs: Long) {
        val bounded = progressMs.coerceIn(0, _durationMs.value)
        Log.d("SubtitleService", "[STEP] seekTo triggered. Seeking playback to: ${bounded}ms. Invalidating last voiced line id.")
        _currentProgressMs.value = bounded
        lastVoicedLineId = -1
        updateSubtitleForTime(bounded)
    }

    fun adjustTTSVolume(volume: Float) {
        val vol = volume.coerceIn(0f, 1f)
        Log.d("SubtitleService", "[ACTION] adjustTTSVolume updated to: ${(vol * 100).toInt()}%")
        _voiceVolume.value = vol
        prefs.edit().putFloat("voiceVolume", vol).apply()
    }

    fun adjustPitch(pitch: Float) {
        val p = pitch.coerceIn(0.5f, 2.0f)
        Log.d("SubtitleService", "[ACTION] adjustPitch updated to: ${p}x")
        _pitch.value = p
        prefs.edit().putFloat("pitch", p).apply()
    }

    fun adjustRate(rate: Float) {
        val r = rate.coerceIn(0.5f, 2.0f)
        Log.d("SubtitleService", "[ACTION] adjustRate speed parameter updated to: ${r}x")
        _rate.value = r
        prefs.edit().putFloat("rate", r).apply()
    }

    fun setVoice(name: String) {
        Log.d("SubtitleService", "[STEP] setVoice selected. Target voice name: '$name'")
        _selectedVoice.value = name
        ttsHelper?.setVoice(name)
        prefs.edit().putString("selectedVoice", name).apply()
    }

    // New Helpers for Translation API selection and Multi-Slider Volume Mixing
    fun getSystemMediaVolume(): Float {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        if (audioManager != null) {
            val current = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            if (max > 0) return current.toFloat() / max.toFloat()
        }
        return 0.7f
    }

    fun adjustOriginalVideoVolume(volume: Float) {
        val vol = volume.coerceIn(0f, 1f)
        Log.d("SubtitleService", "[ACTION] adjustOriginalVideoVolume (YouTube Stream mixer level) updated to: ${(vol * 100).toInt()}%")
        _originalVideoVolume.value = vol
        prefs.edit().putFloat("originalVideoVolume", vol).apply()
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        if (audioManager != null) {
            val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (vol * max).toInt(), 0)
        }
    }

    fun adjustTranslationMasterVolume(volume: Float) {
        val vol = volume.coerceIn(0f, 1f)
        Log.d("SubtitleService", "[ACTION] adjustTranslationMasterVolume (Voiceover Master gain) updated to: ${(vol * 100).toInt()}%")
        _translationMasterVolume.value = vol
        prefs.edit().putFloat("translationMasterVolume", vol).apply()
        // Synchronously adjust the TTS playback volume parameter as well to match overall master gain!
        adjustTTSVolume(vol)
    }

    fun setTranslationApi(api: String) {
        Log.d("SubtitleService", "[STEP] setTranslationApi engine selection updated to: '$api'")
        _selectedTranslationApi.value = api
        prefs.edit().putString("translationApi", api).apply()
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        Log.d("SubtitleService", "[STEP] setAutoSyncEnabled updated to: $enabled")
        _autoSyncEnabled.value = enabled
        prefs.edit().putBoolean("autoSyncEnabled", enabled).apply()
    }

    fun getClipboardUrl(): String? {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.primaryClip
            Log.d("SubtitleService", "[ACTION] Reading clipboard for YouTube link...")
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0)?.text?.toString() ?: ""
                if (text.contains("youtube.com") || text.contains("youtu.be")) {
                    Log.d("SubtitleService", "[ACTION] Clipboard contains valid YouTube link: $text")
                    return text.trim()
                }
            }
        } catch (e: Exception) {
            Log.e("SubtitleService", "Error reading clipboard in service", e)
        }
        Log.d("SubtitleService", "[ACTION] Clipboard did not contain any valid YouTube URL.")
        return null
    }

    private fun startTicker() {
        stopTicker()
        tickerJob = serviceScope.launch {
            while (isActive) {
                if (_serviceState.value == ServiceState.PLAYING) {
                    val current = _currentProgressMs.value
                    if (current < _durationMs.value) {
                        _currentProgressMs.value = current + 100
                        updateSubtitleForTime(_currentProgressMs.value)
                    } else {
                        _serviceState.value = ServiceState.PLAYING // finished status
                    }
                }
                delay(100)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun updateSubtitleForTime(timeMs: Long) {
        val list = _videoInfo.value?.subtitles ?: return
        // Find if there is a subtitle matching this timestamp
        val match = list.find { timeMs >= it.startMs && timeMs <= (it.startMs + it.durationMs) }
        
        if (match != null) {
            val text = match.translatedText ?: match.text
            if (_currentSubtitleText.value != text) {
                Log.d("SubtitleService", "[ACTION] Active subtitle text updated at ${timeMs}ms: '$text'")
                _currentSubtitleText.value = text
            }
            if (match.id != lastVoicedLineId && _serviceState.value == ServiceState.PLAYING) {
                Log.d("SubtitleService", "[STEP] Handing text to TTS for voice rendering. ID: ${match.id}, text: '$text'")
                lastVoicedLineId = match.id
                ttsHelper?.speak(text, _voiceVolume.value, _pitch.value, _rate.value)
            }
        } else {
            // Check if we are between subtitles
            if (_currentSubtitleText.value.isNotEmpty()) {
                Log.d("SubtitleService", "[ACTION] Active subtitle cleared at ${timeMs}ms (no matching track for this timestamp).")
                _currentSubtitleText.value = ""
            }
        }
    }

    // Set up Draggable Window Overlay view with ComposeView
    private fun setupOverlayWindow() {
        if (composeView != null) {
            Log.d("SubtitleService", "setupOverlayWindow: Overlay window is already set up.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !PermissionUtils.canDrawOverlays(this)) {
            Log.w("SubtitleService", "setupOverlayWindow: SYSTEM_ALERT_WINDOW permission is not granted. Delaying overlay creation.")
            return
        }

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@SubtitleService)
            setViewTreeSavedStateRegistryOwner(this@SubtitleService)
            setViewTreeViewModelStoreOwner(this@SubtitleService)
            
            setContent {
                MyApplicationTheme {
                    OverlayViewContent()
                }
            }
        }

        try {
            windowManager.addView(composeView, layoutParams)
        } catch (e: Exception) {
            Log.e("SubtitleService", "Failed to add overlay window to WindowManager", e)
            composeView = null
        }
    }

    private fun removeOverlayWindow() {
        composeView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("SubtitleService", "Error removing overlay view", e)
            }
        }
        composeView = null
    }

    // Overlay Composable containing minimization/drag and beautiful display controls
    @Composable
    private fun OverlayViewContent() {
        val isMin by _isMinimized.collectAsState()
        val text by _currentSubtitleText.collectAsState()
        val state by _serviceState.collectAsState()
        val video by _videoInfo.collectAsState()
        val progress by _currentProgressMs.collectAsState()
        val duration by _durationMs.collectAsState()
        val volume by _voiceVolume.collectAsState()
        val pitchVal by _pitch.collectAsState()
        val rateVal by _rate.collectAsState()
        val transProgress by _translationProgress.collectAsState()
        val voices by _availableVoices.collectAsState()
        val selectedV by _selectedVoice.collectAsState()

        // Collected state flows for Multiple APIs, Language Detection, and Mixing Sliders
        val translationApi by _selectedTranslationApi.collectAsState()
        val detectedLang by _detectedLanguage.collectAsState()
        val origVol by _originalVideoVolume.collectAsState()
        val masterVol by _translationMasterVolume.collectAsState()
        val autoSync by _autoSyncEnabled.collectAsState()
        
        var showSettingsOver by remember { mutableStateOf(false) }

        var offsetX by remember { mutableStateOf(layoutParams?.x?.toFloat() ?: 100f) }
        var offsetY by remember { mutableStateOf(layoutParams?.y?.toFloat() ?: 200f) }

        LaunchedEffect(offsetX, offsetY, isMin, showSettingsOver) {
            layoutParams?.let { lp ->
                lp.x = offsetX.roundToInt()
                lp.y = offsetY.roundToInt()
                try {
                    windowManager.updateViewLayout(composeView, lp)
                    Log.d("SubtitleService", "[WINDOW] WindowManager bounds refreshed: x=${lp.x}, y=${lp.y}, isMin=$isMin, settings=$showSettingsOver")
                } catch (e: Exception) {
                    // Fail-safe
                }
            }
        }

        Box(
            modifier = Modifier.wrapContentSize()
        ) {
            if (isMin) {
                // Minimized bubble mode with custom gesture handler to resolve tap/drag conflicts
                var dragDistance = 0f
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { dragDistance = 0f },
                                onDragEnd = {
                                    if (dragDistance < 15f) {
                                        _isMinimized.value = false
                                    }
                                },
                                onDragCancel = {
                                    if (dragDistance < 15f) {
                                        _isMinimized.value = false
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragDistance += Math.abs(dragAmount.x) + Math.abs(dragAmount.y)
                                    offsetX += dragAmount.x
                                    offsetY += dragAmount.y
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Subtitles,
                        contentDescription = "Show subtitles board",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                    if (state == ServiceState.LOADING_VIDEO || state == ServiceState.TRANSLATING) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.inversePrimary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                // Expanded Subtitle Board
                Card(
                    modifier = Modifier
                        .widthIn(max = 380.dp)
                        .wrapContentHeight()
                        .padding(8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xEC1A2235) // Deep Slate aero glow
                    ),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        // Header row with drag handle and close/min buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Sub-row representing the title bar drag handle specifically (leaving control buttons fully clickable)
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            offsetX += dragAmount.x
                                            offsetY += dragAmount.y
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DragIndicator,
                                    contentDescription = "Drag overlay handle",
                                    tint = Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = video?.title ?: "YT Subtitles",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 160.dp)
                                )
                            }
                            Row {
                                IconButton(
                                    onClick = { showSettingsOver = !showSettingsOver },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Subtitle Settings",
                                        tint = if (showSettingsOver) MaterialTheme.colorScheme.primary else Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { _isMinimized.value = true },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Minimize,
                                        contentDescription = "Minimize overlay to bubble",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { stopSelf() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close overlay service",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        if (showSettingsOver) {
                            Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 4.dp))
                            
                            // Load other video button
                            Button(
                                onClick = {
                                    _videoInfo.value = null
                                    _serviceState.value = ServiceState.IDLE
                                    _currentSubtitleText.value = ""
                                    _translationProgress.value = 0
                                    lastVoicedLineId = -1
                                    stopTicker()
                                    showSettingsOver = false
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White.copy(alpha = 0.15f)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.fillMaxWidth().height(26.dp).padding(bottom = 6.dp)
                            ) {
                                Icon(Icons.Default.Refresh, "Reset video", tint = Color.White, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Сбросить / Другое видео", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }

                            Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 4.dp))

                            // Multi-API Selector
                            Text("Выберите API для перевода субтитров:", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("gemini" to "Gemini AI", "google" to "Google Web").forEach { (key, label) ->
                                    val isSel = translationApi == key
                                    Button(
                                        onClick = { setTranslationApi(key) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSel) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(24.dp).weight(1f)
                                    ) {
                                        Text(label, fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            
                            // Detected Language Banner
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Оригинальный язык видео:", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Normal)
                            Text(
                                text = detectedLang,
                                color = Color(0xFF00D1FF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 4.dp))
                            Text("Регулировка громкости и микширования:", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            
                            // Slider 1: Громкость видео (Original system music stream volume)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.VolumeUp, "YouTube Vol", tint = Color.White, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Видео:", color = Color.White, fontSize = 9.sp, modifier = Modifier.width(36.dp))
                                Slider(
                                    value = origVol,
                                    onValueChange = { adjustOriginalVideoVolume(it) },
                                    modifier = Modifier.weight(1f).height(24.dp)
                                )
                                Text("${(origVol * 100).toInt()}%", color = Color.White, fontSize = 8.sp)
                            }

                            // Slider 2: Громкость озвучки (TTS voice speech parameter)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Hearing, "TTS Volume", tint = Color.White, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Голос:", color = Color.White, fontSize = 9.sp, modifier = Modifier.width(36.dp))
                                Slider(
                                    value = volume,
                                    onValueChange = { adjustTTSVolume(it) },
                                    modifier = Modifier.weight(1f).height(24.dp)
                                )
                                Text("${(volume * 100).toInt()}%", color = Color.White, fontSize = 8.sp)
                            }

                            // Slider 3: Громкость перевода (Master translator overlay level)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.GraphicEq, "Translation Volume", tint = Color.White, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Перевод:", color = Color.White, fontSize = 9.sp, modifier = Modifier.width(36.dp))
                                Slider(
                                    value = masterVol,
                                    onValueChange = { adjustTranslationMasterVolume(it) },
                                    modifier = Modifier.weight(1f).height(24.dp)
                                )
                                Text("${(masterVol * 100).toInt()}%", color = Color.White, fontSize = 8.sp)
                            }
                            
                            // Speed Rate Slider
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Speed, "TTS Rate", tint = Color.White, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Скорость:", color = Color.White, fontSize = 9.sp, modifier = Modifier.width(36.dp))
                                Slider(
                                    value = rateVal,
                                    onValueChange = { adjustRate(it) },
                                    valueRange = 0.5f..2.0f,
                                    modifier = Modifier.weight(1f).height(24.dp)
                                )
                                Text(String.format(Locale.US, "%.1fx", rateVal), color = Color.White, fontSize = 8.sp)
                            }

                            // Auto Sync mode selector
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Режим синхронизации:", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Button(
                                    onClick = { setAutoSyncEnabled(true) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (autoSync) Color(0xFF00D1FF) else Color.White.copy(alpha = 0.08f)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                    modifier = Modifier.height(24.dp).weight(1f)
                                ) {
                                    Icon(Icons.Default.Sync, "Sync on", tint = Color.White, modifier = Modifier.size(10.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("Авто-синхро (видео)", fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = { setAutoSyncEnabled(false) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (!autoSync) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                    modifier = Modifier.height(24.dp).weight(1f)
                                ) {
                                    Icon(Icons.Default.PlayArrow, "Manual mode", tint = Color.White, modifier = Modifier.size(10.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("Ручной плеер", fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }

                            // Voice Selection
                            if (voices.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Голос озвучки (${voices.size}):", color = Color.White, fontSize = 9.sp)
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    voices.take(3).forEach { voiceName ->
                                        val isSel = selectedV == voiceName
                                        Button(
                                            onClick = { setVoice(voiceName) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isSel) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f)
                                            ),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                            modifier = Modifier.height(24.dp)
                                        ) {
                                            Text(
                                                text = voiceName.substringAfterLast("_").replace("ru-ru-", "").replace("ru-", "").take(7),
                                                fontSize = 8.sp,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 6.dp))

                        // Subtitle Display Window
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 60.dp, max = 100.dp)
                                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (state == ServiceState.LOADING_VIDEO) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Загрузка видео и субтитров...", color = Color.White, fontSize = 11.sp)
                                }
                            } else if (state == ServiceState.TRANSLATING) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        progress = transProgress / 100f,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val apiLabel = if (translationApi == "google") "Google Translate" else "Gemini AI"
                                    Text("$apiLabel переводит: $transProgress%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            } else if (state == ServiceState.IDLE) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth().padding(4.dp)
                                ) {
                                    Text(
                                        text = "Скопируйте ссылку на видео в YouTube",
                                        color = Color.White.copy(alpha = 0.8f),
                                        fontSize = 11.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Button(
                                        onClick = {
                                            val url = getClipboardUrl()
                                            if (url != null) {
                                                loadVideoAndTranslate(url)
                                            } else {
                                                android.widget.Toast.makeText(
                                                    this@SubtitleService,
                                                    "Открываем главное приложение для вставки ссылки...",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                                try {
                                                    val launchIntent = Intent(this@SubtitleService, MainActivity::class.java).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                                    }
                                                    this@SubtitleService.startActivity(launchIntent)
                                                } catch (e: Exception) {
                                                    Log.e("SubtitleService", "Failed to start MainActivity", e)
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = Color.White, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Вставить и Озвучить", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            } else {
                                if (text.isEmpty()) {
                                    Text(
                                        text = "Нажмите Play на видео и на оверлее для озвучки",
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(4.dp)
                                    )
                                } else {
                                    Text(
                                        text = text,
                                        color = Color(0xFFFFEB3B), // Classic high-visibility cinematic yellow subtitles
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Media Playback Sync Slider & Buttons
                        if (state == ServiceState.PLAYING || state == ServiceState.PAUSED) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        if (state == ServiceState.PLAYING) pausePlaying() else startPlaying()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (state == ServiceState.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play translation sync",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Text(
                                    text = formatTime(progress),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )

                                Slider(
                                    value = progress.toFloat(),
                                    onValueChange = { seekTo(it.toLong()) },
                                    valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                                    modifier = Modifier.weight(1f).height(24.dp)
                                )

                                Text(
                                    text = formatTime(duration),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }

                            // Sync offset calibration buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { seekTo(progress - 2000) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text("-2с", fontSize = 9.sp, color = Color.White)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { seekTo(progress - 500) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text("-0.5с", fontSize = 9.sp, color = Color.White)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Icon(Icons.Default.Timer, "Sync calibration icon", tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Button(
                                    onClick = { seekTo(progress + 500) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text("+0.5с", fontSize = 9.sp, color = Color.White)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { seekTo(progress + 2000) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text("+2с", fontSize = 9.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun formatTime(millis: Long): String {
        val totalSecs = millis / 1000
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "YouTube Subtitles Translation Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val stopIntent = Intent(this, SubtitleService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2452, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Озвучка & Перевод YT")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(mainPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Остановить сервис",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun registerAudioPlaybackListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        playbackCallback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                super.onPlaybackConfigChanged(configs)

                // If auto-sync is disabled, do absolutely nothing. Manual controls have exclusive authority.
                if (!_autoSyncEnabled.value) {
                    return
                }

                // If our own TTS is speaking, we do not want to trigger or change play/pause state!
                if (ttsHelper?.isSpeaking() == true) {
                    return
                }

                var isOtherAppPlaying = false
                for (config in configs) {
                    val usage = config.audioAttributes?.usage ?: 0
                    if (usage == AudioAttributes.USAGE_MEDIA) {
                        isOtherAppPlaying = true
                        break
                    }
                }

                val currentState = _serviceState.value
                if (currentState == ServiceState.PLAYING || currentState == ServiceState.PAUSED) {
                    if (isOtherAppPlaying) {
                        if (currentState == ServiceState.PAUSED) {
                            Log.d("SubtitleService", "Auto-resuming translation playback because other app started audio/video.")
                            startPlaying()
                        }
                    } else {
                        if (currentState == ServiceState.PLAYING) {
                            Log.d("SubtitleService", "Auto-pausing translation playback because other app stopped audio/video.")
                            pausePlaying()
                        }
                    }
                }
            }
        }

        try {
            audioManager.registerAudioPlaybackCallback(playbackCallback!!, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.e("SubtitleService", "Failed to register audio playback callback", e)
        }
    }

    private fun unregisterAudioPlaybackListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        playbackCallback?.let {
            try {
                audioManager.unregisterAudioPlaybackCallback(it)
            } catch (e: Exception) {
                Log.e("SubtitleService", "Failed to unregister audio playback callback", e)
            }
        }
        playbackCallback = null
    }
}
