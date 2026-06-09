package com.example

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

class TextToSpeechHelper(context: Context, private val onInitSuccess: () -> Unit) {
    private var tts: TextToSpeech? = null
    var isInitialized = false
        private set

    init {
        Log.d("TTSHelper", "[STEP] Initializing TextToSpeech system instance.")
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                Log.d("TTSHelper", "[STEP] TextToSpeech successfully initialized. Setting language to Russian.")
                var result = tts?.setLanguage(Locale("ru", "RU"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w("TTSHelper", "[ACTION] Russian language pack with country tag not supported. Testing general Russian locale fallback.")
                    result = tts?.setLanguage(Locale("ru"))
                }
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w("TTSHelper", "[ACTION] Russian language is completely unsupported. Testing system default locale fallback.")
                    result = tts?.setLanguage(Locale.getDefault())
                }
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w("TTSHelper", "[ACTION] Default locale failed. Setting English US fallback.")
                    tts?.setLanguage(Locale.US)
                }
                isInitialized = true
                Log.d("TTSHelper", "[STEP] TTS Engine is ready for requests. Final language set code: $result")
                onInitSuccess()
            } else {
                Log.e("TTSHelper", "[STEP] TextToSpeech initialization failed with internal status code: $status")
            }
        }
    }

    fun isSpeaking(): Boolean {
        if (!isInitialized) return false
        return try {
            val speaking = tts?.isSpeaking == true
            if (speaking) {
                Log.v("TTSHelper", "[ACTION] Check isSpeaking: TTS is currently vocalizing audio.")
            }
            speaking
        } catch (e: Exception) {
            Log.e("TTSHelper", "Error checking if TTS is speaking", e)
            false
        }
    }

    fun getAvailableVoices(): List<String> {
        Log.d("TTSHelper", "[ACTION] Fetching available localized voices from TTS Engine.")
        if (!isInitialized) {
            Log.w("TTSHelper", "[ACTION] Cannot fetch voices: TTS not yet initialized.")
            return emptyList()
        }
        return try {
            val systemVoices = tts?.voices ?: emptySet()
            val filtered = systemVoices
                .filter { it.locale.language.equals("ru", ignoreCase = true) }
                .map { it.name }
                .sorted()
            Log.d("TTSHelper", "[ACTION] Found ${filtered.size} compatible Russian voices: $filtered")
            filtered
        } catch (e: Exception) {
            Log.e("TTSHelper", "[ACTION] Error getting system voices", e)
            emptyList()
        }
    }

    fun setVoice(name: String) {
        Log.d("TTSHelper", "[STEP] Requesting voice profile update to: '$name'")
        if (!isInitialized) {
            Log.w("TTSHelper", "[STEP] Cannot set voice: TTS not initialized.")
            return
        }
        try {
            val systemVoices = tts?.voices ?: emptySet()
            val matchedInstance = systemVoices.find { it.name == name }
            if (matchedInstance != null) {
                tts?.voice = matchedInstance
                Log.d("TTSHelper", "[STEP] Voice profile successfully updated to: '$name'.")
            } else {
                Log.w("TTSHelper", "[STEP] Requested voice profile '$name' was not found in system voices list.")
            }
        } catch (e: Exception) {
            Log.e("TTSHelper", "Error while switching voice profile", e)
        }
    }

    fun speak(text: String, volume: Float = 1.0f, pitch: Float = 1.0f, rate: Float = 1.0f) {
        Log.d("TTSHelper", "[STEP] TTS speak initiated. Text: '$text', Volume: $volume, Pitch: $pitch, Speed rate: $rate")
        if (!isInitialized) {
            Log.e("TTSHelper", "[STEP]speak aborted: TTS Engine is not initialized yet.")
            return
        }
        try {
            tts?.setPitch(pitch)
            tts?.setSpeechRate(rate)

            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
            }
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "YT_SUB_SPEECH")
            Log.d("TTSHelper", "[ACTION] tts?.speak call dispatched with return code: $result")
        } catch (e: Exception) {
            Log.e("TTSHelper", "[STEP] Exception during TTS audio vocalization", e)
        }
    }

    fun stop() {
        Log.d("TTSHelper", "[STEP] Hard stop vocalization requested.")
        if (isInitialized) {
            try {
                tts?.stop()
                Log.d("TTSHelper", "[ACTION] TTS active audio playback terminated.")
            } catch (e: Exception) {
                Log.e("TTSHelper", "Exception stopping speech", e)
            }
        }
    }

    fun shutdown() {
        Log.d("TTSHelper", "[STEP] shutdown triggered. Disposing TTS engine.")
        if (tts != null) {
            try {
                tts?.shutdown()
                Log.d("TTSHelper", "[STEP] TTS Engine destroyed.")
            } catch (e: Exception) {
                Log.e("TTSHelper", "Exception shutting down TTS service", e)
            }
            tts = null
            isInitialized = false
        }
    }
}
