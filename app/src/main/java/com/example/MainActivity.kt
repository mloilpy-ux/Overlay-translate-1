package com.example

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var subtitleService by mutableStateOf<SubtitleService?>(null)
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SubtitleService.LocalBinder
            subtitleService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            subtitleService = null
            isBound = false
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkOverlayAndNotify()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { accepted ->
        if (accepted) {
            Toast.makeText(this, "Уведомления разрешены!", Toast.LENGTH_SHORT).show()
        }
    }

    private var hasOverlayPermission by mutableStateOf(false)
    private val isWindowFocusedStateByApp = mutableStateOf(false)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        isWindowFocusedStateByApp.value = hasFocus
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkOverlayAndNotify()
        requestNotificationPermission()

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    MainScreenContent(
                        paddingValues = innerPadding,
                        subtitleService = subtitleService,
                        hasOverlayPermission = hasOverlayPermission,
                        isWindowFocused = isWindowFocusedStateByApp.value,
                        onRequestOverlayPermission = { requestOverlayPermission() },
                        onStartTranslation = { url -> startSubtitleService(url) },
                        onStopTranslation = { stopSubtitleService() }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, SubtitleService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onResume() {
        super.onResume()
        checkOverlayAndNotify()
    }

    private fun checkOverlayAndNotify() {
        hasOverlayPermission = PermissionUtils.canDrawOverlays(this)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestOverlayPermission() {
        if (!PermissionUtils.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun startSubtitleService(url: String) {
        if (!PermissionUtils.canDrawOverlays(this)) {
            Toast.makeText(this, "Пожалуйста, разрешите показ поверх других окон!", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }

        val intent = Intent(this, SubtitleService::class.java).apply {
            putExtra("VIDEO_URL", url)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Сервис озвучки запущен!", Toast.LENGTH_SHORT).show()
    }

    private fun stopSubtitleService() {
        val intent = Intent(this, SubtitleService::class.java)
        stopService(intent)
        Toast.makeText(this, "Сервис озвучки остановлен", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreenContent(
    paddingValues: PaddingValues,
    subtitleService: SubtitleService?,
    hasOverlayPermission: Boolean,
    isWindowFocused: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onStartTranslation: (String) -> Unit,
    onStopTranslation: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    var inputUrl by remember { mutableStateOf("") }
    var lastInterpretedLink by remember { mutableStateOf("") }

    var aiResultText by remember { mutableStateOf("") }
    var isAiLoading by remember { mutableStateOf(false) }
    var customQuestionText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val textColor = if (isDark) Color.White else Color(0xFF0F172A)
    val subTextColor = if (isDark) Color.LightGray.copy(alpha = 0.7f) else Color(0xFF64748B)
    val cardContainerColor = MaterialTheme.colorScheme.surface
    val cardBorderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color(0xFFE2E8F0)

    // Read clipboard for YouTube URL when window gains focus
    LaunchedEffect(isWindowFocused) {
        if (isWindowFocused) {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard != null && clipboard.hasPrimaryClip()) {
                    val clip = clipboard.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0)?.text?.toString() ?: ""
                        if (rawLinkIsValid(text)) {
                            inputUrl = text
                            lastInterpretedLink = text
                        }
                    }
                }
            } catch (e: Exception) {
                // Safe ignore
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(modifier = Modifier.height(20.dp))
            
            // Brand Headline Banner
            BrandHeaderWidget()
            
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Overlay Permission Status Card
        item {
            PermissionCheckCard(
                hasPermission = hasOverlayPermission,
                onRequest = onRequestOverlayPermission
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Link Input Box
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        cardBorderColor,
                        RoundedCornerShape(24.dp)
                    ),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = cardContainerColor)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text(
                        text = "Ссылка на видео или Shorts",
                        color = textColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Скопируйте ссылку из YouTube и нажмите 'Вставить'",
                        color = subTextColor,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        placeholder = { Text("https://www.youtube.com/watch?v=...", color = subTextColor.copy(alpha = 0.6f), fontSize = 13.sp) },
                        trailingIcon = {
                            if (inputUrl.isNotEmpty()) {
                                IconButton(onClick = { inputUrl = "" }) {
                                    Icon(Icons.Default.Clear, "Clear text", tint = subTextColor)
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = textColor,
                            unfocusedTextColor = textColor,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = cardBorderColor,
                            focusedContainerColor = if (isDark) Color(0xFF090E17) else Color(0xFFF8FAFC),
                            unfocusedContainerColor = if (isDark) Color(0xFF090E17) else Color(0xFFF8FAFC)
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = clipboard?.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val text = clip.getItemAt(0)?.text?.toString() ?: ""
                                    if (rawLinkIsValid(text)) {
                                        inputUrl = text
                                        Toast.makeText(context, "Ссылка вставлена!", Toast.LENGTH_SHORT).show()
                                    } else if (text.isNotEmpty()) {
                                        inputUrl = text
                                        Toast.makeText(context, "Вставлен буфер обмена", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Буфер обмена пуст!", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "Буфер обмена не обнаружен", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color(0xFFE2E8F0),
                                contentColor = textColor
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Вставить", fontSize = 13.sp)
                        }

                        Button(
                            onClick = {
                                if (inputUrl.trim().isEmpty()) {
                                    Toast.makeText(context, "Введите ссылку на YouTube сначала!", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                onStartTranslation(inputUrl.trim())
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1.2f)
                        ) {
                            Icon(Icons.Default.Subtitles, contentDescription = "Translate")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Озвучить", fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Action Status / Controller Panel
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        cardBorderColor,
                        RoundedCornerShape(24.dp)
                    ),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = cardContainerColor)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text(
                        text = "Управление оверлеем",
                        color = textColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Включите фоновый оверлей, чтобы переводить YouTube прямо во время воспроизведения",
                        color = subTextColor,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onStopTranslation() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Остановить", fontSize = 13.sp)
                        }

                        Button(
                            onClick = {
                                // Just start service with empty or current URL to draw floating button only
                                onStartTranslation("")
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDark) Color(0xFF1E293B) else Color(0xFF141414),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = "Draw button")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Оверлей", fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 5. Settings, API Picker, and Audio Mixer Panel
        item {
            val service = subtitleService
            if (service != null) {
                val apiSelection by service.selectedTranslationApi.collectAsState()
                val originalVol by service.originalVideoVolume.collectAsState()
                val transMasterVol by service.translationMasterVolume.collectAsState()
                val voiceVol by service.voiceVolume.collectAsState()
                val detectedLangCode by service.detectedLanguage.collectAsState()
                val availableVoicesList by service.availableVoices.collectAsState()
                val currentVoiceName by service.selectedVoice.collectAsState()
                val speedRate by service.rate.collectAsState()
                val autoSync by service.autoSyncEnabled.collectAsState()

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, cardBorderColor, RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cardContainerColor)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Text(
                            text = "Настройки перевода и звука",
                            color = textColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Трансляция и микширование звука в реальном времени поверх YouTube",
                            color = subTextColor,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        // Selector
                        Text(
                            text = "Предпочитаемый API для перевода:",
                            color = textColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("gemini" to "Gemini AI API", "google" to "Google Translate").forEach { (key, label) ->
                                val isSelected = apiSelection == key
                                Button(
                                    onClick = { service.setTranslationApi(key) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary else (if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0)),
                                        contentColor = if (isSelected) Color.White else textColor
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Selector for Synchronization Mode
                        Text(
                            text = "Режим синхронизации воспроизведения:",
                            color = textColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(true to "Авто-синхро (видео)", false to "Ручной плеер").forEach { (key, label) ->
                                val isSelected = autoSync == key
                                Button(
                                    onClick = { service.setAutoSyncEnabled(key) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary else (if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0)),
                                        contentColor = if (isSelected) Color.White else textColor
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Detected Language Area
                        Text(
                            text = "Автоматическое определение языка оригинала:",
                            color = textColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isDark) Color(0xFF0F172A) else Color(0xFFF1F5F9), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = "Language detected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Оригинал: $detectedLangCode",
                                color = textColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Vol Slider 1: Original YouTube Video Volume
                        Text(
                            text = "Регулировка звука (Микшер):",
                            color = textColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VolumeUp, "Video sound", tint = subTextColor, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("YouTube Громкость:", color = textColor, fontSize = 12.sp, modifier = Modifier.width(130.dp))
                            Slider(
                                value = originalVol,
                                onValueChange = { service.adjustOriginalVideoVolume(it) },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("${(originalVol * 100).toInt()}%", color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        // Vol Slider 2: Translation voiceover/TTS volume
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Hearing, "TTS Volume", tint = subTextColor, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Громкость Озвучки:", color = textColor, fontSize = 12.sp, modifier = Modifier.width(130.dp))
                            Slider(
                                value = voiceVol,
                                onValueChange = { service.adjustTTSVolume(it) },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("${(voiceVol * 100).toInt()}%", color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        // Vol Slider 3: Translation master / gain slider
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.GraphicEq, "Master Translation Volume", tint = subTextColor, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Громкость Перевода:", color = textColor, fontSize = 12.sp, modifier = Modifier.width(130.dp))
                            Slider(
                                value = transMasterVol,
                                onValueChange = { service.adjustTranslationMasterVolume(it) },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("${(transMasterVol * 100).toInt()}%", color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        // Speed rate slider
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Speed, "TTS Speed", tint = subTextColor, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Скорость Перевода:", color = textColor, fontSize = 12.sp, modifier = Modifier.width(130.dp))
                            Slider(
                                value = speedRate,
                                onValueChange = { service.adjustRate(it) },
                                valueRange = 0.5f..2.0f,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(String.format(Locale.US, "%.1fx", speedRate), color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        if (availableVoicesList.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Доступные голоса озвучки в системе:",
                                color = textColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            // Wrap-around row of standard voices
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                availableVoicesList.take(6).forEach { voiceName ->
                                    val isSelected = currentVoiceName == voiceName
                                    androidx.compose.material3.InputChip(
                                        selected = isSelected,
                                        onClick = { service.setVoice(voiceName) },
                                        label = {
                                            Text(
                                                text = voiceName.substringAfterLast("_").replace("ru-ru-", "").replace("ru-", ""),
                                                fontSize = 11.sp
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // If service is not bound/running, provide fallback information
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, cardBorderColor, RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cardContainerColor)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Text(
                            text = "Настройки и Микшер звука",
                            color = textColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Запустите фоновый оверлей, чтобы активировать микшер звука, автоопределение языка оригинала и выбор между лучшими API (Gemini, Google).",
                            color = subTextColor,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 6. Gemini AI Interactive Assistant Card
        item {
            val service = subtitleService
            if (service != null) {
                val videoInfoState by service.videoInfo.collectAsState()
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            BorderStroke(1.5.dp, if (videoInfoState != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else cardBorderColor),
                            RoundedCornerShape(24.dp)
                        ),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cardContainerColor)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "AI Assistant",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = "ИИ видео-ассистент (Gemini 3.5)",
                                color = textColor,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (videoInfoState == null) {
                            Text(
                                text = "Интеллектуальный анализ доступен после загрузки видео. ИИ-помощник сможет составить конспект или ответить на любые вопросы по содержанию видео!",
                                color = subTextColor,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        } else {
                            val lines = videoInfoState?.subtitles ?: emptyList()
                            
                            Text(
                                text = "Текст видео успешно загружен (${lines.size} строк). Выберите готовый сценарий анализа или спросите ИИ напрямую!",
                                color = subTextColor,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            // AI Action Buttons Configuration
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isAiLoading = true
                                            aiResultText = "Генерирую подробный конспект..."
                                            aiResultText = GeminiSubtitleTranslator.generateVideoSummaryAndQA(
                                                subtitles = lines,
                                                promptText = "Создай краткое, структурированное текстовое содержание (конспект) этого видео с ключевыми пунктами и выводами на русском.",
                                                apiKey = BuildConfig.GEMINI_API_KEY
                                            )
                                            isAiLoading = false
                                        }
                                    },
                                    enabled = !isAiLoading,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        contentColor = MaterialTheme.colorScheme.primary
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Summarize, contentDescription = "Summary", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("📝 Краткий конспект", fontSize = 12.sp)
                                }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            isAiLoading = true
                                            aiResultText = "Ищу практические советы..."
                                            aiResultText = GeminiSubtitleTranslator.generateVideoSummaryAndQA(
                                                subtitles = lines,
                                                promptText = "Извлеки все практические советы, уроки, инструкции или рекомендации для зрителя из этого видео.",
                                                apiKey = BuildConfig.GEMINI_API_KEY
                                            )
                                            isAiLoading = false
                                        }
                                    },
                                    enabled = !isAiLoading,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                                        contentColor = MaterialTheme.colorScheme.secondary
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("💡 Практические советы", fontSize = 12.sp)
                                }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            isAiLoading = true
                                            aiResultText = "Формулирую главный вывод..."
                                            aiResultText = GeminiSubtitleTranslator.generateVideoSummaryAndQA(
                                                subtitles = lines,
                                                promptText = "Опиши главную суть, посыл, и целевую аудиторию этого видео в 2-3 предложениях.",
                                                apiKey = BuildConfig.GEMINI_API_KEY
                                            )
                                            isAiLoading = false
                                        }
                                    },
                                    enabled = !isAiLoading,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                                        contentColor = MaterialTheme.colorScheme.tertiary
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("🎯 Главный вывод", fontSize = 12.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Custom Q&A TextInput Row
                            OutlinedTextField(
                                value = customQuestionText,
                                onValueChange = { customQuestionText = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Задайте любой вопрос по содержанию...", color = subTextColor.copy(alpha = 0.6f), fontSize = 12.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                trailingIcon = {
                                    if (customQuestionText.isNotEmpty() && !isAiLoading) {
                                        IconButton(
                                            onClick = {
                                                val q = customQuestionText
                                                customQuestionText = ""
                                                scope.launch {
                                                    isAiLoading = true
                                                    aiResultText = "Ищу ответ на ваш вопрос: '$q'..."
                                                    aiResultText = GeminiSubtitleTranslator.generateVideoSummaryAndQA(
                                                        subtitles = lines,
                                                        promptText = "Ответь на следующий вопрос пользователя касательно содержания этого видео: '$q'. Твой ответ должен быть точным, полезным и только на русском языке.",
                                                        apiKey = BuildConfig.GEMINI_API_KEY
                                                    )
                                                    isAiLoading = false
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = textColor,
                                    unfocusedTextColor = textColor,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = cardBorderColor,
                                    focusedContainerColor = if (isDark) Color(0xFF090E17) else Color(0xFFF8FAFC),
                                    unfocusedContainerColor = if (isDark) Color(0xFF090E17) else Color(0xFFF8FAFC)
                                )
                            )

                            // AI Answer Display Canvas
                            if (aiResultText.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(14.dp))
                                
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC), RoundedCornerShape(16.dp))
                                        .border(0.5.dp, cardBorderColor, RoundedCornerShape(16.dp))
                                        .padding(14.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "ОТВЕТ ИИ-ПОМОЩНИКА:",
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.2.sp
                                        )
                                        
                                        if (isAiLoading) {
                                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                        } else {
                                            IconButton(
                                                onClick = {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                    clipboard?.setPrimaryClip(ClipData.newPlainText("AI Answer", aiResultText))
                                                    Toast.makeText(context, "Ответ скопирован!", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.size(16.dp)
                                            ) {
                                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy text", tint = subTextColor, modifier = Modifier.size(12.dp))
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))
                                    
                                    SelectionContainer {
                                        Text(
                                            text = aiResultText,
                                            color = textColor,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Quick User Guide / Instructions Card
        item {
            InstructionCard()
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun BrandHeaderWidget() {
    val isDark = isSystemInDarkTheme()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
    ) {
        Text(
            text = "SERVICE ACTIVE",
            color = Color(0xFF4F6BED),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.8.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Text(
            text = "LinguaCast",
            color = if (isDark) Color.White else Color(0xFF0F172A),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp
        )

        Text(
            text = "YT Subtitles & Sound Sync Overlay",
            color = if (isDark) Color.LightGray.copy(alpha = 0.8f) else Color(0xFF64748B),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp)
        )
    }
}

@Composable
fun PermissionCheckCard(
    hasPermission: Boolean,
    onRequest: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val containerCol = if (hasPermission) {
        if (isDark) Color(0xFF0D2D23) else Color(0xFFE8F5E9)
    } else {
        if (isDark) Color(0xFF330E14) else Color(0xFFFFEBEE)
    }
    val borderCol = if (hasPermission) {
        if (isDark) Color(0xFF1E824C) else Color(0xFF81C784)
    } else {
        if (isDark) Color(0xFF962D3E) else Color(0xFFE57373)
    }
    val iconBgCol = if (hasPermission) {
        if (isDark) Color(0xFF114232) else Color(0xFFC8E6C9)
    } else {
        if (isDark) Color(0xFF4C111A) else Color(0xFFFFCDD2)
    }
    val iconCol = if (hasPermission) {
        if (isDark) Color(0xFF2ECD71) else Color(0xFF2E7D32)
    } else {
        if (isDark) Color(0xFFE74C3C) else Color(0xFFC62828)
    }
    val titleCol = if (isDark) Color.White else Color(0xFF0F172A)
    val bodyCol = if (isDark) Color.LightGray else Color(0xFF64748B)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerCol),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(borderCol)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(iconBgCol),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (hasPermission) Icons.Default.Check else Icons.Default.Dangerous,
                    contentDescription = "Permission Status",
                    tint = iconCol,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (hasPermission) "Разрешение получено" else "Требуется разрешение",
                    color = titleCol,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (hasPermission) "Оверлей может запускаться поверх YouTube." else "Необходимо разрешить показ поверх других приложений.",
                    color = bodyCol,
                    fontSize = 10.sp
                )
            }

            if (!hasPermission) {
                Button(
                    onClick = onRequest,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE74C3C)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Выдать", fontSize = 11.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun InstructionCard() {
    val isDark = isSystemInDarkTheme()
    val cardContainerColor = MaterialTheme.colorScheme.surface
    val cardBorderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color(0xFFE2E8F0)
    val textColor = if (isDark) Color.White else Color(0xFF0F172A)
    val subTextColor = if (isDark) Color.LightGray.copy(alpha = 0.85f) else Color(0xFF475569)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                cardBorderColor,
                RoundedCornerShape(24.dp)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cardContainerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = "Help icon",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Как этим пользоваться?",
                    color = textColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            val items = listOf(
                "Скопируйте ссылку на обычное видео или Shorts в приложении YouTube",
                "Вставьте ссылку на главном экране этого приложения и нажмите 'Озвучить'",
                "Появится плавающее окно (оверлей). Вы можете свернуть это приложение и открыть YouTube",
                "Нажмите 'Play' на панели оверлея одновременно с запуском видео на YouTube",
                "Используйте кнопки коррекции ±0.5с / ±2с для идеальной синхронизации озвучки с видео!"
            )

            items.forEachIndexed { index, text ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "${index + 1}. ",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = text,
                        color = subTextColor,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

private fun rawLinkIsValid(url: String): Boolean {
    return url.contains("youtube.com") || url.contains("youtu.be")
}
