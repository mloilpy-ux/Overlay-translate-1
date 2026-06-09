package com.example

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SubtitleLine(
    val id: Int,
    val startMs: Long,
    val durationMs: Long,
    val text: String,
    var translatedText: String? = null
)

@JsonClass(generateAdapter = true)
data class TranslatedLine(
    val id: Int,
    val text: String
)

data class VideoInfo(
    val id: String,
    val title: String,
    val description: String,
    val durationString: String,
    val subtitles: List<SubtitleLine>
)

enum class ServiceState {
    IDLE,
    LOADING_VIDEO,
    TRANSLATING,
    PLAYING,
    PAUSED,
    ERROR
}
