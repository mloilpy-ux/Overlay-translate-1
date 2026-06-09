package com.example.service.overlay

import android.content.Context
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import android.util.Log

/**
 * Управляет оверлей окном для вывода субтитров.
 * Отделяет логику управления оверлеем от основного сервиса.
 * 
 * ИСПРАВЛЕНИЯ:
 * - Обновление layout params происходит ВНЕ LaunchedEffect
 * - Error handling
 * - Логирование оп��раций
 * - Граничные проверки
 */
class OverlayManager(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private const val TAG = "OverlayManager"
    
    private var overlayView: FrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    
    var offsetX = mutableFloatStateOf(0f)
        private set
    var offsetY = mutableFloatStateOf(0f)
        private set
    
    var isMinimized = mutableStateOf(false)
        private set
    
    private var lastX = 0f
    private var lastY = 0f

    /**
     * Инициализирует оверлей с заданными параметрами.
     */
    fun initializeOverlay(view: FrameLayout, params: WindowManager.LayoutParams) {
        try {
            this.overlayView = view
            this.layoutParams = params.apply {
                x = offsetX.floatValue.toInt()
                y = offsetY.floatValue.toInt()
            }
            Log.d(TAG, "✅ Overlay initialized")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize overlay", e)
        }
    }

    /**
     * Обрабатывает перетаскивание оверлея.
     * ВАЖНО: обновление layout params происходит здесь, а не в LaunchedEffect!
     * Это предотвращает перезагрузку всей композиции при каждом движении.
     */
    fun handleDrag(event: MotionEvent) {
        val params = layoutParams ?: return
        val view = overlayView ?: return

        try {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    Log.d(TAG, "📍 Drag started at ($lastX, $lastY)")
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - lastX).toInt()
                    val deltaY = (event.rawY - lastY).toInt()

                    params.x += deltaX
                    params.y += deltaY

                    offsetX.floatValue = params.x.toFloat()
                    offsetY.floatValue = params.y.toFloat()

                    // Обновляем layout ВНЕ композиции для избежания утечек
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to update layout", e)
                    }

                    lastX = event.rawX
                    lastY = event.rawY
                }
                MotionEvent.ACTION_UP -> {
                    Log.d(TAG, "✋ Drag ended at (${params.x}, ${params.y})")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling drag", e)
        }
    }

    /**
     * Переводит оверлей в минимизированное состояние.
     */
    fun toggleMinimize() {
        isMinimized.value = !isMinimized.value
        Log.d(TAG, "${if (isMinimized.value) "📦" else "📖"} Overlay minimized: ${isMinimized.value}")
    }

    /**
     * Удаляет оверлей из WindowManager.
     */
    fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
                Log.d(TAG, "✅ Overlay removed")
            } catch (e: Exception) {
                Log.e(TAG, "ℹ️ View already removed", e)
            }
        }
        overlayView = null
        layoutParams = null
    }

    /**
     * Сохраняет позицию оверлея.
     */
    fun savePosition(preferences: android.content.SharedPreferences) {
        try {
            preferences.edit().apply {
                putInt("overlay_x", offsetX.floatValue.toInt())
                putInt("overlay_y", offsetY.floatValue.toInt())
                putBoolean("overlay_minimized", isMinimized.value)
                apply()
            }
            Log.d(TAG, "✅ Position saved")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save position", e)
        }
    }

    /**
     * Восстанавливает сохранённую позицию оверлея.
     */
    fun restorePosition(preferences: android.content.SharedPreferences) {
        try {
            offsetX.floatValue = preferences.getInt("overlay_x", 0).toFloat()
            offsetY.floatValue = preferences.getInt("overlay_y", 0).toFloat()
            isMinimized.value = preferences.getBoolean("overlay_minimized", false)
            Log.d(TAG, "✅ Position restored")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to restore position", e)
        }
    }
}
