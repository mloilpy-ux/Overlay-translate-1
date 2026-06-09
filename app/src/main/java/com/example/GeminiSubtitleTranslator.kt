package com.example

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object GeminiSubtitleTranslator {
    private const val TAG = "GeminiTranslator"
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    // Helper: translate subtitles in batches of 25 lines
    suspend fun translateSubtitles(
        subtitles: List<SubtitleLine>,
        apiKey: String,
        onProgress: (Int, Int) -> Unit
    ): List<SubtitleLine> = withContext(Dispatchers.IO) {
        Log.d(TAG, "[STEP] translateSubtitles initiated for ${subtitles.size} lines.")
        if (subtitles.isEmpty()) {
            Log.w(TAG, "[STEP] Subtitles list is empty. Returning immediately.")
            return@withContext emptyList()
        }
        val result = subtitles.map { it.copy() }
        val batchSize = 25
        val batches = result.chunked(batchSize)
        
        Log.d(TAG, "[STEP] Grouped into ${batches.size} translation batches of size up to $batchSize")

        for ((index, batch) in batches.withIndex()) {
            Log.d(TAG, "[ACTION] Processing translation batch ${index + 1}/${batches.size} (Batch size: ${batch.size})")
            onProgress(index * batchSize, result.size)
            var success = false
            var attempts = 0
            
            while (!success && attempts < 2) {
                try {
                    attempts++
                    Log.d(TAG, "[ACTION] Batch ${index + 1} translation attempt $attempts/2")
                    val translationMap = callGeminiTranslateBatch(batch, apiKey)
                    if (translationMap.isNotEmpty()) {
                        Log.d(TAG, "[ACTION] Successfully translated batch ${index + 1}. Map size: ${translationMap.size}")
                        for (line in batch) {
                            val translated = translationMap[line.id]
                            if (translated != null) {
                                line.translatedText = translated
                            } else {
                                Log.w(TAG, "[ACTION] Missing translation mapping for subtitle ID: ${line.id}. Falling back to default text.")
                                line.translatedText = line.text
                            }
                        }
                        success = true
                    } else {
                        Log.e(TAG, "[ACTION] Empty or invalid translation map returned for batch $index on attempt $attempts")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[ACTION] Error in translating batch $index (attempt $attempts)", e)
                    if (attempts < 2) {
                        Log.d(TAG, "[ACTION] Waiting 1000ms before retrying batch $index...")
                        delay(1000)
                    }
                }
            }

            if (!success) {
                Log.e(TAG, "[STEP] All attempts failed for translation batch $index. Injecting placeholder texts.")
                for (line in batch) {
                    if (line.translatedText == null) {
                        line.translatedText = "[Перевод недоступен] ${line.text}"
                    }
                }
            }
        }
        
        Log.d(TAG, "[STEP] Translation session completed. Dispatching 100% progress callback.")
        onProgress(result.size, result.size)
        result
    }

    // Call Gemini API to translate a single batch
    private fun callGeminiTranslateBatch(batch: List<SubtitleLine>, apiKey: String): Map<Int, String> {
        Log.d(TAG, "[ACTION] callGeminiTranslateBatch triggered. Checking API key validity.")
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "[ACTION] Gemini API key is missing or is placeholder! Cannot perform translate batch.")
            return emptyMap()
        }

        val inputList = JSONArray()
        for (line in batch) {
            val obj = JSONObject()
            obj.put("id", line.id)
            obj.put("text", line.text)
            inputList.put(obj)
        }

        Log.d(TAG, "[ACTION] Constructing translation prompt instructions.")
        val prompt = """
            You are an expert video translator. Translate this list of timed subtitles from their original language into natural, high-quality, conversational Russian.
            Make sure the translation flows naturally for video voiceover and maintains the exact mood of the speaker.
            
            OUTPUT FORMAT REQUIREMENTS:
            You MUST return a clean JSON array containing the EXACT same indices (id) and their translated Russian text.
            Do not include any intro, outro, explanations, markdown backticks (like ```json), or notes. Output MUST be an absolutely raw, parseable JSON array.
            
            Example Format:
            [
              {"id": 0, "text": "Транслированный текст"}
            ]
            
            Subtitles to translate:
            ${inputList.toString(2)}
        """.trimIndent()

        val requestJson = JSONObject()
        val contentsArray = JSONArray()
        val contentObj = JSONObject()
        val partsArray = JSONArray()
        val partObj = JSONObject()
        partObj.put("text", prompt)
        partsArray.put(partObj)
        contentObj.put("parts", partsArray)
        contentsArray.put(contentObj)
        requestJson.put("contents", contentsArray)

        // Configure system instruction to enforce expert translator behavior
        val systemInstruction = JSONObject()
        val systemParts = JSONArray()
        val systemPart = JSONObject()
        systemPart.put("text", "You are a professional video translator. You translate movie and vlog subtitles to natural Russian. You strictly output valid, un-wrapped JSON formats without code-fences.")
        systemParts.put(systemPart)
        systemInstruction.put("parts", systemParts)
        requestJson.put("systemInstruction", systemInstruction)

        // Set generationConfig for application/json output type
        val generationConfig = JSONObject()
        generationConfig.put("responseMimeType", "application/json")
        generationConfig.put("temperature", 0.3)
        requestJson.put("generationConfig", generationConfig)

        val requestBodyString = requestJson.toString()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

        Log.d(TAG, "[ACTION] Deserializing REST payload. Target Endpoint: $url")
        val request = Request.Builder()
            .url(url)
            .post(requestBodyString.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "[ACTION] Gemini translation response code: ${response.code}")
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    Log.e(TAG, "[ACTION] Gemini API error (Status ${response.code}): $errBody")
                    return emptyMap()
                }

                val body = response.body?.string() ?: return emptyMap()
                Log.d(TAG, "[ACTION] Payload size received: ${body.length} symbols. Initializing parser.")
                parseGeminiResponse(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[ACTION] Network error when executing batch request", e)
            emptyMap()
        }
    }

    private fun extractJsonArrayString(raw: String): String {
        val clean = raw.trim()
        val startIndex = clean.indexOf('[')
        val endIndex = clean.lastIndexOf(']')
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            val extracted = clean.substring(startIndex, endIndex + 1)
            Log.d(TAG, "[ACTION] Robustly extracted JSON array block from text spanning $startIndex to $endIndex.")
            return extracted
        }
        val stripped = clean.removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        Log.v(TAG, "[ACTION] extractJsonArrayString fallback: pattern brackets not found or invalid; returned cleaned string.")
        return stripped
    }

    // Parse the Gemini REST json payload
    private fun parseGeminiResponse(responseStr: String): Map<Int, String> {
        Log.d(TAG, "[ACTION] parseGeminiResponse: Parsing raw Gemini JSON response payload. Length: ${responseStr.length} symbols.")
        val result = mutableMapOf<Int, String>()
        try {
            val responseObj = JSONObject(responseStr)
            val candidates = responseObj.optJSONArray("candidates") ?: run {
                Log.e(TAG, "[ACTION] parseGeminiResponse failed: candidates array is missing in response JSON.")
                return emptyMap()
            }
            if (candidates.length() == 0) {
                Log.e(TAG, "[ACTION] parseGeminiResponse failed: candidates list is empty.")
                return emptyMap()
            }
            val candidate = candidates.getJSONObject(0)
            val content = candidate.optJSONObject("content") ?: run {
                Log.e(TAG, "[ACTION] parseGeminiResponse failed: candidate content object missing.")
                return emptyMap()
            }
            val parts = content.optJSONArray("parts") ?: run {
                Log.e(TAG, "[ACTION] parseGeminiResponse failed: content parts array missing.")
                return emptyMap()
            }
            if (parts.length() == 0) {
                Log.e(TAG, "[ACTION] parseGeminiResponse failed: parts list is empty.")
                return emptyMap()
            }
            
            val rawText = parts.getJSONObject(0).optString("text") ?: ""
            Log.d(TAG, "[ACTION] parseGeminiResponse raw generated text from model:\n$rawText")

            val cleanText = extractJsonArrayString(rawText)
            Log.d(TAG, "[ACTION] parseGeminiResponse extracted clean JSON array text:\n$cleanText")
            
            val jsonArray = JSONArray(cleanText)
            Log.d(TAG, "[ACTION] parseGeminiResponse successfully instantiated JSONArray. Parsing ${jsonArray.length()} array elements.")
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val id = item.getInt("id")
                // Robust check for translatedText or text keys
                val text = if (item.has("translatedText")) {
                    item.getString("translatedText")
                } else if (item.has("text")) {
                    item.getString("text")
                } else {
                    ""
                }
                result[id] = text
                Log.v(TAG, "[ACTION] parseGeminiResponse item index parsed -> ID: $id, text: '$text'")
            }
            Log.d(TAG, "[ACTION] parseGeminiResponse extraction completed. Total successfully mapped translated lines: ${result.size}")
        } catch (e: Exception) {
            Log.e(TAG, "[ACTION] parseGeminiResponse critical failure during JSON decoding. Payload trace:\n$responseStr", e)
        }
        return result
    }

    // Generates translated narrative synopsis for video when no captions exist or scraping fails
    suspend fun generateFallbackSubtitles(
        url: String,
        title: String,
        description: String,
        apiKey: String
    ): List<SubtitleLine> = withContext(Dispatchers.IO) {
        Log.d(TAG, "[STEP] generateFallbackSubtitles triggered. Input video parameters -> URL: $url, Title: $title, Description length: ${description.length} chars.")
        val defaultSubtitles = listOf(
            SubtitleLine(0, 1000, 4000, "Initializing", "[Оригинальный трек не найден. Gemini создает обзор видео...]"),
            SubtitleLine(1, 5000, 10000, "Summary", "[Перевод названия: '$title']")
        )
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "[STEP] generateFallbackSubtitles skipped: Gemini API key is missing or is developer placeholder. Returning default subtitles.")
            return@withContext defaultSubtitles
        }

        try {
            Log.d(TAG, "[STEP] Constructing specialized expert translation Prompt requesting ONLY narrative adaptation timeline JSON.")
            val prompt = """
                You are an expert video translator and narrative content adapter.
                The user wants a voiceover translation and adaptation of the following YouTube video link where original captions are missing:
                Link URL: $url
                Title: $title
                Description: $description
                
                Please generate a high-quality, continuous, natural Russian voiceover narrative script with timing (timestamps in milliseconds) matching the sequence of the video (from 0 to 60-90 seconds or more).
                The narrative must describe the content, translate key elements, and adapt them beautifully into Russian so that a text-to-speech engine can voice the entire timeline in sync.
                
                OUTPUT FORMAT REQUIREMENTS:
                You MUST return ONLY a clean valid JSON array of objects. Do not include markdown backticks (like ```json or ```), conversational text, explanations, or any extra commentary.
                The response must consist STRICTLY AND ONLY of the JSON array.
                
                Exact JSON Schema to return:
                [
                  {"id": 0, "startMs": 1000, "durationMs": 4000, "text": "Summary segment description", "translatedText": "Текст адаптированного перевода и озвучки на русском"}
                ]
                
                Provide at least 8-12 timed segments spanning up to 1-2 minutes or more so that the user gets a high-quality, continuous, synchronized voiceover narration in Russian!
            """.trimIndent()

            Log.d(TAG, "[STEP] Dispatching adaptation request payload to 'gemini-3.5-flash'.")
            val requestJson = JSONObject()
            val contentsArray = JSONArray()
            val contentObj = JSONObject()
            val partsArray = JSONArray()
            val partObj = JSONObject()
            partObj.put("text", prompt)
            partsArray.put(partObj)
            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
            requestJson.put("contents", contentsArray)

            val generationConfig = JSONObject()
            generationConfig.put("responseMimeType", "application/json")
            generationConfig.put("temperature", 0.4)
            requestJson.put("generationConfig", generationConfig)

            val apiEndpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(apiEndpoint)
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            Log.d(TAG, "[STEP] Sending request to REST API endpoint...")
            client.newCall(request).execute().use { response ->
                val code = response.code
                Log.d(TAG, "[STEP] Gemini HTTP Response code: $code")
                if (!response.isSuccessful) {
                    val errorPayload = response.body?.string() ?: ""
                    Log.e(TAG, "[STEP] Gemini fallback generation API failed with status code $code. Error payload: $errorPayload")
                    return@withContext defaultSubtitles
                }
                
                val body = response.body?.string() ?: run {
                    Log.e(TAG, "[STEP] Gemini response returned successful but with empty body payload.")
                    return@withContext defaultSubtitles
                }
                Log.d(TAG, "[STEP] Google Gemini API HTTP reply body payload successfully fetched. Length: ${body.length}")
                
                val responseObj = JSONObject(body)
                val candidates = responseObj.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    Log.e(TAG, "[STEP] candidates array not resolved in response dictionary.")
                    return@withContext defaultSubtitles
                }
                
                val rawText = candidates.getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                
                Log.d(TAG, "[STEP] Extracting generated content from parts list -> raw text:\n$rawText")

                val cleanText = extractJsonArrayString(rawText)
                Log.d(TAG, "[STEP] Extracted cleanest JSON form for timeline parsing -> clean text:\n$cleanText")

                val lineList = mutableListOf<SubtitleLine>()
                val array = JSONArray(cleanText)
                Log.d(TAG, "[STEP] Timeline JSON array parsed. Populating ${array.length()} narrative segments.")
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    // Robust check for translatedText or text keys in fallback adaptation JSON
                    val translation = if (obj.has("translatedText")) {
                        obj.getString("translatedText")
                    } else if (obj.has("text")) {
                        obj.getString("text")
                    } else {
                        "Видео фрагмент"
                    }
                    lineList.add(
                        SubtitleLine(
                            id = obj.getInt("id"),
                            startMs = obj.getLong("startMs"),
                            durationMs = obj.getLong("durationMs"),
                            text = obj.optString("text", "Video Segment"),
                            translatedText = translation
                        )
                    )
                    Log.d(TAG, "[STEP] Segment #$i parsed successfully: id=${obj.getInt("id")}, start=${obj.getLong("startMs")}ms, text='$translation'")
                }
                
                Log.d(TAG, "[STEP] Fallback tracks synchronization completed. Returning ${lineList.size} beautifully adapted voiceover lines.")
                return@withContext lineList
            }
        } catch (e: Exception) {
            Log.e(TAG, "[STEP] Critical exception caught during fallback subtitle generation flow", e)
            return@withContext defaultSubtitles
        }
    }

    // Google Translate Engine - FREE, FAST, NO API KEY NEEDED
    suspend fun translateSubtitlesGoogle(
        subtitles: List<SubtitleLine>,
        onProgress: (Int, Int) -> Unit
    ): List<SubtitleLine> = withContext(Dispatchers.IO) {
        Log.d(TAG, "[STEP] translateSubtitlesGoogle initiated for ${subtitles.size} lines.")
        if (subtitles.isEmpty()) {
            Log.w(TAG, "[STEP] Subtitles list is empty. Returning immediately.")
            return@withContext emptyList()
        }
        val result = subtitles.map { it.copy() }
        val chunkSize = 15 // Batch size for newline combining (stable for Google Translate)
        val chunks = result.chunked(chunkSize)

        Log.d(TAG, "[STEP] Starting Google Translation of ${result.size} lines in ${chunks.size} chunks")

        for ((index, chunk) in chunks.withIndex()) {
            Log.d(TAG, "[ACTION] Requesting Google Translate for chunk ${index + 1}/${chunks.size} (Chunk size: ${chunk.size})")
            onProgress(index * chunkSize, result.size)
            var success = false
            try {
                val joinedText = chunk.map { it.text }.joinToString("\n")
                val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ru&dt=t"
                val body = okhttp3.FormBody.Builder()
                    .add("q", joinedText)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "[ACTION] Google translate chunk response status: ${response.code}")
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        val parsedLines = parseGoogleTranslateResponse(bodyStr)
                        if (parsedLines.size == chunk.size) {
                            Log.d(TAG, "[ACTION] Perfect size fit for parsed lines on chunk $index.")
                            for (i in chunk.indices) {
                                chunk[i].translatedText = parsedLines[i]
                            }
                            success = true
                        } else {
                            Log.w(TAG, "[ACTION] Google Translate size mismatch (${parsedLines.size} vs ${chunk.size}) on chunk $index, processing individually")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[ACTION] Failed Google Translate Chunk $index due to exception", e)
            }

            if (!success) {
                Log.w(TAG, "[ACTION] Batch processing failed on chunk $index. Running individual fallback translate operations.")
                for (line in chunk) {
                    line.translatedText = translateSingleGoogle(line.text)
                }
            }
            Log.d(TAG, "[ACTION] Resting 150ms between translate requests...")
            delay(150)
        }

        Log.d(TAG, "[STEP] Google Translate translation completed successfully.")
        onProgress(result.size, result.size)
        result
    }

    private fun parseGoogleTranslateResponse(jsonStr: String): List<String> {
        Log.d(TAG, "[ACTION] Parsing Google Translate response json array.")
        val list = mutableListOf<String>()
        try {
            val root = JSONArray(jsonStr)
            val firstArray = root.optJSONArray(0) ?: return emptyList()
            for (i in 0 until firstArray.length()) {
                val item = firstArray.optJSONArray(i)
                if (item != null && item.length() > 0) {
                    val translated = item.optString(0) ?: ""
                    list.add(translated)
                }
            }
            Log.d(TAG, "[ACTION] Parsed lines counts: ${list.size}")
        } catch (e: Exception) {
            Log.e(TAG, "[ACTION] Failed to parse Google Translate response JSON string: $jsonStr", e)
        }
        return list
    }

    private fun translateSingleGoogle(text: String): String {
        Log.d(TAG, "[ACTION] translateSingleGoogle for word/sentence: '$text'")
        if (text.isBlank()) return ""
        try {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ru&dt=t&q=${java.net.URLEncoder.encode(text, "UTF-8")}"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "[ACTION] Single translate status: ${response.code}")
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val root = JSONArray(bodyStr)
                    val firstArray = root.optJSONArray(0)
                    if (firstArray != null && firstArray.length() > 0) {
                        val sb = StringBuilder()
                        for (i in 0 until firstArray.length()) {
                            val item = firstArray.optJSONArray(i)
                            if (item != null && item.length() > 0) {
                                sb.append(item.optString(0))
                            }
                        }
                        val finalTrans = sb.toString().trim()
                        Log.d(TAG, "[ACTION] Translated single sentence to: '$finalTrans'")
                        return finalTrans
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[ACTION] Single line Google Translation failed for '$text'", e)
        }
        return text // fallback to original text on failure
    }

    // Heuristically defines the original language of the video
    fun detectLanguageOfSubtitles(subtitles: List<SubtitleLine>): String {
        Log.d(TAG, "[STEP] Detecting original language for subtitles.")
        if (subtitles.isEmpty()) {
            Log.w(TAG, "[STEP] No subtitles to sample language from. Assuming default language: en")
            return "en"
        }

        val textSample = subtitles.take(20).joinToString(" ") { it.text }.lowercase()
        if (textSample.isBlank()) {
            Log.w(TAG, "[STEP] Sample string is empty. Assuming default language: en")
            return "en"
        }

        Log.d(TAG, "[ACTION] Scanning sub-samples: $textSample")

        // 1. Japanese check
        val jpCount = Regex("[\\u3040-\\u309F\\u30A0-\\u30FF]").findAll(textSample).count()
        if (jpCount > 4) {
            Log.d(TAG, "[STEP] Japanese characters found (count: $jpCount). Detected: ja")
            return "ja"
        }

        // 2. Korean check
        val koCount = Regex("[\\uAC00-\\uD7AF]").findAll(textSample).count()
        if (koCount > 4) {
            Log.d(TAG, "[STEP] Korean characters found (count: $koCount). Detected: ko")
            return "ko"
        }

        // 3. Chinese check
        val zhCount = Regex("[\\u4E00-\\u9FFF]").findAll(textSample).count()
        if (zhCount > 8) {
            Log.d(TAG, "[STEP] Chinese characters found (count: $zhCount). Detected: zh")
            return "zh"
        }

        // 4. Cyrillic check
        val cyrillicCount = Regex("[а-яё]").findAll(textSample).count()
        if (cyrillicCount > 15) {
            Log.d(TAG, "[STEP] Cyrillic/Russian characters found (count: $cyrillicCount). Detected: ru")
            return "ru"
        }

        // Dictionary stopword matchers for Latin-script languages
        val englishWords = listOf("the", "and", "you", "that", "this", "with", "have", "are", "for")
        val spanishWords = listOf("el", "la", "los", "en", "de", "con", "que", "por", "para")
        val frenchWords = listOf("le", "la", "les", "en", "de", "que", "pour", "dans", "avec")
        val germanWords = listOf("der", "die", "das", "und", "ist", "mit", "von", "für")
        val italianWords = listOf("il", "la", "i", "in", "di", "che", "per", "con")

        val words = textSample.split(Regex("\\s+")).map { it.replace(Regex("[^a-z]"), "") }.filter { it.isNotEmpty() }
        
        var en = 0
        var es = 0
        var fr = 0
        var de = 0
        var it = 0

        for (word in words) {
            if (englishWords.contains(word)) en++
            if (spanishWords.contains(word)) es++
            if (frenchWords.contains(word)) fr++
            if (germanWords.contains(word)) de++
            if (italianWords.contains(word)) it++
        }

        val max = listOf(en, es, fr, de, it).maxOrNull() ?: 0
        if (max > 0) {
            return when (max) {
                en -> { Log.d(TAG, "[STEP] English stopwords matched: $en. Detected: en"); "en" }
                es -> { Log.d(TAG, "[STEP] Spanish stopwords matched: $es. Detected: es"); "es" }
                fr -> { Log.d(TAG, "[STEP] French stopwords matched: $fr. Detected: fr"); "fr" }
                de -> { Log.d(TAG, "[STEP] German stopwords matched: $de. Detected: de"); "de" }
                it -> { Log.d(TAG, "[STEP] Italian stopwords matched: $it. Detected: it"); "it" }
                else -> "en"
            }
        }

        val latinCount = Regex("[a-z]").findAll(textSample).count()
        if (latinCount > 10) {
            Log.d(TAG, "[STEP] Latin character count is high ($latinCount) without strong dictionary hits. Detected: en")
            return "en"
        }

        Log.d(TAG, "[STEP] No matchers found. Defaulting language code to: en")
        return "en"
    }

    fun getLanguageDisplayName(code: String): String {
        return when (code.lowercase()) {
            "en" -> "Английский (English)"
            "es" -> "Испанский (Español)"
            "fr" -> "Французский (Français)"
            "de" -> "Немецкий (Deutsch)"
            "it" -> "Итальянский (Italiano)"
            "ru" -> "Русский (Русский)"
            "ja" -> "Японский (日本語)"
            "ko" -> "Корейский (한국어)"
            "zh" -> "Китайский (中文)"
            else -> "Невербальный / Другой"
        }
    }

    suspend fun generateVideoSummaryAndQA(
        subtitles: List<SubtitleLine>,
        promptText: String,
        apiKey: String
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "Ошибка: Пожалуйста, настройте GEMINI_API_KEY в панели Secrets во вкладке разработки AI Studio."
        }

        if (subtitles.isEmpty()) {
            return@withContext "Ошибка: Текст видео пуст или еще не загружен."
        }

        val textSample = subtitles.take(300).joinToString("\n") { "[${it.startMs/1000}с]: ${it.text} -> ${it.translatedText ?: ""}" }
        val prompt = """
            You are an expert AI Content Analyst.
            We are analyzing a video. Here is the timed text transcript of the subtitles:
            $textSample
            
            The user is asking you to do this:
            $promptText
            
            Please provide a comprehensive, clear, and beautiful response in strict Russian language.
            Use elegant formatting with bold highlights and bullet points. Never display any HTML code or markdown code fences. Respond directly.
        """.trimIndent()

        val requestJson = JSONObject()
        val contentsArray = JSONArray()
        val contentObj = JSONObject()
        val partsArray = JSONArray()
        val partObj = JSONObject()
        partObj.put("text", prompt)
        partsArray.put(partObj)
        contentObj.put("parts", partsArray)
        contentsArray.put(contentObj)
        requestJson.put("contents", contentsArray)

        val apiEndpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(apiEndpoint)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: ""
                    return@withContext "Ошибка от API (код ${response.code}): $err"
                }
                val body = response.body?.string() ?: return@withContext "Пустой ответ от сервера."
                val responseObj = JSONObject(body)
                val candidates = responseObj.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val rawResponse = candidates.getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                    return@withContext rawResponse.trim()
                }
                "Не удалось разобрать ответ от ИИ."
            }
        } catch (e: Exception) {
            "Ошибка сети или подключения: ${e.localizedMessage}"
        }
    }
}
