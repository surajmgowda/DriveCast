package com.example.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedReader
import java.io.InputStreamReader

data class ExternalSubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object ExternalSubtitleParser {

    fun loadFromUri(context: Context, uri: Uri): Pair<String, List<ExternalSubtitleCue>> {
        val displayName = resolveDisplayName(context, uri)
        val rawText = context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
        } ?: throw IllegalStateException("Unable to read subtitle file")
        val cues = parse(rawText)
        return displayName to cues
    }

    fun parse(rawContent: String): List<ExternalSubtitleCue> {
        val normalized = rawContent
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val lines = normalized.lines()
        val cues = mutableListOf<ExternalSubtitleCue>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.startsWith("WEBVTT") || line.startsWith("NOTE")) {
                i++
                continue
            }
            if (line.contains("-->")) {
                val parsedRange = parseTimeRange(line)
                i++
                val textLines = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank()) {
                    textLines += stripTags(lines[i])
                    i++
                }
                if (parsedRange != null && textLines.isNotEmpty()) {
                    val joined = textLines.joinToString("\n").trim()
                    if (joined.isNotEmpty()) {
                        cues += ExternalSubtitleCue(
                            startMs = parsedRange.first,
                            endMs = parsedRange.second,
                            text = joined
                        )
                    }
                }
            } else {
                i++
            }
        }
        return cues.sortedBy { it.startMs }
    }

    fun findActiveCueTexts(
        cues: List<ExternalSubtitleCue>,
        playbackPositionMs: Long,
        subtitleOffsetMs: Int
    ): List<String> {
        if (cues.isEmpty()) return emptyList()
        // Positive offset delays subtitle appearance (looks up earlier cue timestamps)
        val targetMs = (playbackPositionMs - subtitleOffsetMs).coerceAtLeast(0L)
        return cues.filter { targetMs in it.startMs..it.endMs }.map { it.text }
    }

    private fun parseTimeRange(line: String): Pair<Long, Long>? {
        val arrowIdx = line.indexOf("-->")
        if (arrowIdx <= 0) return null
        val startPart = line.substring(0, arrowIdx).trim()
        val endRaw = line.substring(arrowIdx + 3).trim()
        val endPart = endRaw.split(Regex("\\s+")).firstOrNull()?.trim() ?: return null
        val startMs = parseTimestampMs(startPart) ?: return null
        val endMs = parseTimestampMs(endPart) ?: return null
        return if (endMs >= startMs) startMs to endMs else null
    }

    private fun parseTimestampMs(raw: String): Long? {
        val clean = raw.trim().replace(',', '.')
        val parts = clean.split(':')
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val secParts = parts[2].split('.')
                    val s = secParts[0].toLong()
                    val ms = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                    ((h * 3600L) + (m * 60L) + s) * 1000L + ms
                }
                2 -> {
                    val m = parts[0].toLong()
                    val secParts = parts[1].split('.')
                    val s = secParts[0].toLong()
                    val ms = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                    ((m * 60L) + s) * 1000L + ms
                }
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun stripTags(line: String): String {
        return line.replace(Regex("<[^>]+>"), "").trim()
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "External Subtitles"
    }
}
