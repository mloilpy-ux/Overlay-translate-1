package com.example

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object YouTubeCaptionsScraper {
    private const val TAG = "YTCaptionsScraper"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun extractVideoId(url: String): String? {
        Log.d(TAG, "[STEP] Extracting video ID from URL: $url")
        val pattern = "(?:https?:\\/\\/)?(?:www\\.)?(?:youtube\\.com\\/(?:[^\\/\\n\\s]+\\/\\S+\\/|(?:v|e(?:mbed)?)\\/|\\S*?[?&]v=|shorts\\/)|youtu\\.be\\/)([a-zA-Z0-9_-]{11})"
        val regex = Regex(pattern)
        val videoId = regex.find(url)?.groupValues?.get(1)
        Log.d(TAG, "[STEP] Extracted Video ID: ${videoId ?: "none (failed extraction)"}")
        return videoId
    }

    suspend fun fetchVideoInfo(url: String): VideoInfo? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(url)
        if (videoId == null) {
            Log.e(TAG, "[STEP] Could not parse video ID from URL. Aborting scrape step.")
            return@withContext null
        }
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        Log.d(TAG, "[STEP] Performing watch URL GET request to $watchUrl")
        
        val request = Request.Builder()
            .url(watchUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
            
        return@withContext try {
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "[STEP] Watch page response code: ${response.code}")
                if (!response.isSuccessful) {
                    Log.e(TAG, "[STEP] Watch Page request failed with HTTP code ${response.code}")
                    return@withContext null
                }
                val html = response.body?.string() ?: return@withContext null
                Log.d(TAG, "[STEP] Received HTML content size: ${html.length} characters")
                
                val title = extractTitle(html) ?: "YouTube Video"
                val description = extractDescription(html) ?: ""
                Log.d(TAG, "[STEP] Scraped Title: $title")
                Log.d(TAG, "[STEP] Scraped Description character count: ${description.length}")
                
                val captionsUrl = extractCaptionsUrl(html)
                val subtitles = if (captionsUrl != null) {
                    Log.d(TAG, "[STEP] Found captions base URL: $captionsUrl. Triggering parser.")
                    fetchAndParseCaptions(captionsUrl)
                } else {
                    Log.w(TAG, "[STEP] No native caption renderer/tracks found in YouTube HTML.")
                    emptyList()
                }
                
                Log.d(TAG, "[STEP] Scraper successfully complete. Title: $title, Captions found: ${subtitles.isNotEmpty()} (${subtitles.size} lines)")
                VideoInfo(
                    id = videoId,
                    title = title,
                    description = description,
                    durationString = "00:00",
                    subtitles = subtitles
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "[STEP] Exception occurred during YouTube screen scraping for $videoId", e)
            null
        }
    }

    private fun extractTitle(html: String): String? {
        Log.d(TAG, "[ACTION] Extracting title tag from raw HTML")
        val startToken = "<title>"
        val endToken = "</title>"
        val start = html.indexOf(startToken)
        if (start == -1) return null
        val end = html.indexOf(endToken, start + startToken.length)
        if (end == -1) return null
        return decodeHtmlEntities(html.substring(start + startToken.length, end).removeSuffix(" - YouTube"))
    }

    private fun extractDescription(html: String): String? {
        Log.d(TAG, "[ACTION] Extracting description meta tag from raw HTML")
        val startToken = "<meta name=\"description\" content=\""
        val start = html.indexOf(startToken)
        if (start == -1) return null
        val end = html.indexOf("\"", start + startToken.length)
        if (end == -1) return null
        return decodeHtmlEntities(html.substring(start + startToken.length, end))
    }

    private fun extractCaptionsUrl(html: String): String? {
        Log.d(TAG, "[ACTION] Searching for player captions tracks inside JSON blocks")
        val rendererToken = "\"playerCaptionsTracklistRenderer\""
        var idx = html.indexOf(rendererToken)
        if (idx == -1) {
            val fallbackIdx = html.indexOf("\"captionTracks\"")
            if (fallbackIdx == -1) {
                Log.d(TAG, "[ACTION] Caption tokens not found in this response.")
                return null
            }
            idx = fallbackIdx
        }

        val baseUrlIdx = html.indexOf("\"baseUrl\"", idx)
        if (baseUrlIdx == -1) return null
        val startQuote = html.indexOf("\"", baseUrlIdx + 9 + 1)
        if (startQuote == -1) return null
        val endQuote = html.indexOf("\"", startQuote + 1)
        if (endQuote == -1) return null
        
        val url = html.substring(startQuote + 1, endQuote)
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            
        Log.d(TAG, "[ACTION] URL extracted and unescaped successfully: $url")
        return url
    }

    private fun fetchAndParseCaptions(baseUrl: String): List<SubtitleLine> {
        Log.d(TAG, "[STEP] Fetching/parsing XML from captions base URL: $baseUrl")
        val request = Request.Builder()
            .url(baseUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()
            
        try {
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "[STEP] Caption file HTTP status: ${response.code}")
                if (!response.isSuccessful) return emptyList()
                val xml = response.body?.string() ?: return emptyList()
                Log.d(TAG, "[STEP] Caption file payload length: ${xml.length} characters")
                
                val textTagRegex = Regex("<text\\s+start=\"([0-9.]+)\"\\s+dur=\"([0-9.]+)\"[^>]*>([^<]*)</text>")
                val matches = textTagRegex.findAll(xml)
                
                var lineId = 0
                val parsedList = matches.map { match ->
                    val startSecFraction = match.groupValues[1].toDoubleOrNull() ?: 0.0
                    val durationSecFraction = match.groupValues[2].toDoubleOrNull() ?: 0.0
                    val rawText = match.groupValues[3]
                    
                    SubtitleLine(
                        id = lineId++,
                        startMs = (startSecFraction * 1000).toLong(),
                        durationMs = (durationSecFraction * 1000).toLong(),
                        text = decodeHtmlEntities(rawText).trim()
                    )
                }.toList()
                
                Log.d(TAG, "[STEP] Parsed completed. Total parsed text lines: ${parsedList.size}")
                return parsedList
            }
        } catch (e: Exception) {
            Log.e(TAG, "[STEP] Error fetching captions from XML", e)
            return emptyList()
        }
    }

    private fun decodeHtmlEntities(str: String): String {
        return str.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#92;", "\\")
    }
}
