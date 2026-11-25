package com.example.player

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PlayerDiagLevel(val tag: String) {
    INFO("INFO"),
    EVENT("EVENT"),
    WARN("WARN"),
    ERROR("ERROR")
}

data class PlayerDiagnosticLogEntry(
    val timestampMs: Long,
    val timeLabel: String,
    val level: PlayerDiagLevel,
    val source: String,
    val message: String
)

data class PlayerDiagnosticState(
    val entries: List<PlayerDiagnosticLogEntry> = emptyList(),
    val lastDecoderName: String = "None",
    val lastVideoSize: String = "0x0",
    val lastError: String? = null,
    val totalDroppedFrames: Int = 0
)

/**
 * Ring-buffer diagnostic logger tracking Media3 ExoPlayer playback states,
 * hardware decoder initialization, codec formats, dropped frames, and playback errors.
 */
object VideoPlayerDiagnosticLogger {
    private const val TAG = "DriveCastPlayerDiag"
    private const val LOG_FILE_NAME = "player_diagnostics.log"
    private const val MAX_ENTRIES = 200

    private val lock = Any()
    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null
    private var initialized = false

    private val _state = MutableStateFlow(PlayerDiagnosticState())
    val state: StateFlow<PlayerDiagnosticState> = _state.asStateFlow()

    fun init(context: Context?) {
        val appCtx = context?.applicationContext ?: return
        synchronized(lock) {
            if (initialized) return
            initialized = true
            val file = File(appCtx.filesDir, LOG_FILE_NAME)
            logFile = file
            if (file.exists()) {
                val restored = runCatching {
                    file.readLines()
                        .takeLast(MAX_ENTRIES)
                        .mapNotNull(::parseLine)
                }.getOrDefault(emptyList())
                _state.update { it.copy(entries = restored) }
            }
        }
    }

    fun log(
        source: String,
        message: String,
        level: PlayerDiagLevel = PlayerDiagLevel.INFO,
        context: Context? = null
    ) {
        if (context != null && !initialized) {
            init(context)
        }
        val now = System.currentTimeMillis()
        val timeLabel = synchronized(lock) { timeFormatter.format(Date(now)) }
        val entry = PlayerDiagnosticLogEntry(
            timestampMs = now,
            timeLabel = timeLabel,
            level = level,
            source = source,
            message = message
        )

        runCatching {
            when (level) {
                PlayerDiagLevel.ERROR -> Log.e(TAG, "[$source] $message")
                PlayerDiagLevel.WARN -> Log.w(TAG, "[$source] $message")
                else -> Log.i(TAG, "[$source] $message")
            }
        }

        synchronized(lock) {
            _state.update { current ->
                val updated = (current.entries + entry).takeLast(MAX_ENTRIES)
                val err = if (level == PlayerDiagLevel.ERROR) message else current.lastError
                current.copy(entries = updated, lastError = err)
            }
            appendToFileLocked(entry)
        }
    }

    fun updateDecoder(decoderName: String) {
        _state.update { it.copy(lastDecoderName = decoderName) }
        log("Decoder", "Initialized hardware decoder: $decoderName", PlayerDiagLevel.EVENT)
    }

    fun updateVideoSize(width: Int, height: Int) {
        val sizeLabel = "${width}x${height}"
        _state.update { it.copy(lastVideoSize = sizeLabel) }
        log("VideoSize", "Resolution changed to $sizeLabel", PlayerDiagLevel.INFO)
    }

    fun addDroppedFrames(count: Int) {
        if (count <= 0) return
        _state.update { it.copy(totalDroppedFrames = it.totalDroppedFrames + count) }
        if (count > 5) {
            log("Perf", "Dropped $count frames (total: ${_state.value.totalDroppedFrames})", PlayerDiagLevel.WARN)
        }
    }

    fun clear() {
        synchronized(lock) {
            _state.update {
                PlayerDiagnosticState()
            }
            runCatching { logFile?.writeText("") }
        }
    }

    fun exportFormattedReport(): String {
        val current = _state.value
        return buildString {
            appendLine("=== DriveCast Video Player Diagnostic Report ===")
            appendLine("Last Decoder: ${current.lastDecoderName}")
            appendLine("Video Resolution: ${current.lastVideoSize}")
            appendLine("Total Dropped Frames: ${current.totalDroppedFrames}")
            appendLine("Last Error: ${current.lastError ?: "None"}")
            appendLine()
            appendLine("--- Player Events (${current.entries.size}) ---")
            current.entries.forEach { entry ->
                appendLine("${entry.timeLabel} [${entry.level.tag}] ${entry.source}: ${entry.message}")
            }
        }
    }

    private fun appendToFileLocked(entry: PlayerDiagnosticLogEntry) {
        val file = logFile ?: return
        runCatching {
            val line = "${entry.timestampMs}|${entry.timeLabel}|${entry.level.name}|${entry.source}|${entry.message.replace('\n', ' ')}\n"
            file.appendText(line)
            if (file.length() > 64 * 1024) {
                val trimmed = file.readLines().takeLast(MAX_ENTRIES)
                file.writeText(trimmed.joinToString("\n", postfix = "\n"))
            }
        }
    }

    private fun parseLine(line: String): PlayerDiagnosticLogEntry? {
        val parts = line.split('|', limit = 5)
        if (parts.size < 5) return null
        val ts = parts[0].toLongOrNull() ?: return null
        val level = runCatching { PlayerDiagLevel.valueOf(parts[2]) }.getOrDefault(PlayerDiagLevel.INFO)
        return PlayerDiagnosticLogEntry(
            timestampMs = ts,
            timeLabel = parts[1],
            level = level,
            source = parts[3],
            message = parts[4]
        )
    }
}
