package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.player.PlayerDiagLevel
import com.example.player.VideoPlayerDiagnosticLogger

/**
 * Detailed diagnostic card inspecting Media3 ExoPlayer hardware decoders,
 * MediaCodecVideoRenderer initialization success, surface binding, and decoder errors on the car head unit.
 */
@Composable
fun PlayerDiagnosticsCard() {
    val TAG = "PlayerDiagnosticsCard"
    val context = LocalContext.current
    val playerDiagState by VideoPlayerDiagnosticLogger.state.collectAsStateWithLifecycle()

    val hasDecoderError = playerDiagState.entries.any {
        it.level == PlayerDiagLevel.ERROR && (it.source.contains("MediaCodec", true) || it.message.contains("Decoder", true))
    }
    val lastDecoder = playerDiagState.lastDecoderName
    val isHardwareDecoder = !lastDecoder.lowercase().contains("sw") && lastDecoder != "None"

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Media3 MediaCodecVideoRenderer Diagnostics",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }

            DiagnosticStatusRow("Active Hardware Decoder", lastDecoder)
            DiagnosticStatusRow("Decoder Acceleration", if (isHardwareDecoder) "Hardware Accelerated (Zero-Copy)" else "Software / None")
            DiagnosticStatusRow("Video Resolution", playerDiagState.lastVideoSize)
            DiagnosticStatusRow("Total Dropped Frames", playerDiagState.totalDroppedFrames.toString())

            // Status Banner
            val bannerColor = if (!hasDecoderError && isHardwareDecoder) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            }
            val bannerTextColor = if (!hasDecoderError && isHardwareDecoder) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(bannerColor)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = if (!hasDecoderError && isHardwareDecoder) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (!hasDecoderError && isHardwareDecoder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (!hasDecoderError && isHardwareDecoder) {
                        "MediaCodecVideoRenderer successfully initialized hardware decoding on head unit surface."
                    } else {
                        playerDiagState.lastError ?: "Checking MediaCodecVideoRenderer decoder initialization on car display..."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = bannerTextColor
                )
            }

            Text(
                text = "MediaCodec & ExoPlayer Session Logs (${playerDiagState.entries.size})",
                style = MaterialTheme.typography.labelLarge
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 200.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (playerDiagState.entries.isEmpty()) {
                    Text(
                        text = "No player decoder events recorded yet. Play a video to inspect MediaCodecVideoRenderer startup.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    playerDiagState.entries.asReversed().forEach { entry ->
                        val entryColor = when (entry.level) {
                            PlayerDiagLevel.ERROR -> MaterialTheme.colorScheme.error
                            PlayerDiagLevel.WARN -> MaterialTheme.colorScheme.tertiary
                            PlayerDiagLevel.EVENT -> MaterialTheme.colorScheme.primary
                            PlayerDiagLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = "${entry.timeLabel} [${entry.source}] ${entry.message}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = entryColor
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val report = VideoPlayerDiagnosticLogger.exportFormattedReport()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("DriveCast Player Diagnostics", report))
                        Toast.makeText(context, "Player diagnostic report copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("copy_player_logs_button")
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Logs")
                }

                OutlinedButton(
                    onClick = { VideoPlayerDiagnosticLogger.clear() },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("clear_player_logs_button")
                ) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Clear")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticStatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
