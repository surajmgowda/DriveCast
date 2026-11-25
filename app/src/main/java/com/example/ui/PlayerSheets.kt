package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.car.CabinUtilityState
import com.example.data.AspectMode
import com.example.data.OrientationLockMode
import com.example.data.SubtitleColorPreset
import com.example.data.SubtitleStyleConfig
import com.example.data.SyncedBookmark
import com.example.player.PlayerController
import com.example.player.PlayerUiState
import kotlin.math.roundToInt

@Composable
fun TrackSelectionAndSubtitleDialog(
    playerState: PlayerUiState,
    subtitleStyle: SubtitleStyleConfig,
    onUpdateSubtitleStyle: (SubtitleStyleConfig) -> Unit,
    onPickExternalSubtitle: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio, Subtitles & Sync") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Audio Tracks", style = MaterialTheme.typography.titleMedium)
                if (playerState.audioTracks.isEmpty()) {
                    Text("Default hardware audio stream", style = MaterialTheme.typography.bodyMedium)
                } else {
                    playerState.audioTracks.forEach { track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (track.selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { PlayerController.selectAudioTrack(track) }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(track.label, style = MaterialTheme.typography.bodyMedium)
                            Text(track.codec, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text("Subtitles", style = MaterialTheme.typography.titleMedium)

                OutlinedButton(
                    onClick = onPickExternalSubtitle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("load_external_subtitle_button")
                ) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Load External .SRT / .VTT File")
                }

                if (playerState.externalSubtitleName != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playerState.externalSubtitleName,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${playerState.externalSubtitleCount} external cues active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            IconButton(onClick = { PlayerController.clearExternalSubtitle() }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear external subtitles")
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            PlayerController.clearExternalSubtitle()
                            PlayerController.selectSubtitleTrack(null)
                        }
                        .padding(12.dp)
                ) {
                    Text("Off", style = MaterialTheme.typography.bodyMedium)
                }
                playerState.subtitleTracks.forEach { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (track.selected && playerState.externalSubtitleName == null) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                            .clickable { PlayerController.selectSubtitleTrack(track) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(track.label, style = MaterialTheme.typography.bodyMedium)
                        Text(track.codec, style = MaterialTheme.typography.labelSmall)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "External Subtitle Timing Offset: ${"%+.2f".format(playerState.subtitleOffsetMs / 1000f)}s",
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(-500 to "-0.5s", -250 to "-0.25s", 0 to "0s", 250 to "+0.25s", 500 to "+0.5s").forEach { (delta, label) ->
                        FilterChip(
                            selected = delta == 0 && playerState.subtitleOffsetMs == 0,
                            onClick = {
                                val next = if (delta == 0) 0 else playerState.subtitleOffsetMs + delta
                                PlayerController.setSubtitleOffsetMs(next)
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Slider(
                    value = playerState.subtitleOffsetMs.toFloat(),
                    onValueChange = { PlayerController.setSubtitleOffsetMs(it.roundToInt()) },
                    valueRange = -5000f..5000f,
                    modifier = Modifier.testTag("subtitle_sync_slider")
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Cabin Subtitle Styling", style = MaterialTheme.typography.titleMedium)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Sample Cabin Subtitle",
                        color = Color(subtitleStyle.colorPreset.argbColor),
                        fontSize = (14f * subtitleStyle.fontScale).sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = subtitleStyle.bgOpacity))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Text(
                    text = "Text Size: ${(subtitleStyle.fontScale * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = subtitleStyle.fontScale,
                    onValueChange = {
                        onUpdateSubtitleStyle(subtitleStyle.copy(fontScale = it))
                    },
                    valueRange = 0.8f..1.65f,
                    modifier = Modifier.testTag("subtitle_size_slider")
                )

                Text(
                    text = "Backdrop Contrast: ${(subtitleStyle.bgOpacity * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = subtitleStyle.bgOpacity,
                    onValueChange = {
                        onUpdateSubtitleStyle(subtitleStyle.copy(bgOpacity = it))
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.testTag("subtitle_opacity_slider")
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SubtitleColorPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = subtitleStyle.colorPreset == preset,
                            onClick = {
                                onUpdateSubtitleStyle(subtitleStyle.copy(colorPreset = preset))
                            },
                            label = { Text(preset.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksAndDisplaySheet(
    playerState: PlayerUiState,
    orientationLock: OrientationLockMode,
    bookmarks: List<SyncedBookmark>,
    onAddBookmark: (String, Long) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onSetOrientationLock: (OrientationLockMode) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var bookmarkLabel by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.AspectRatio, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Screen Orientation & Ultrawide Zoom", style = MaterialTheme.typography.titleMedium)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AspectMode.entries.forEach { mode ->
                    FilterChip(
                        selected = playerState.aspectMode == mode,
                        onClick = { PlayerController.setAspectMode(mode) },
                        label = { Text(mode.label) }
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.ScreenRotation, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Text("Orientation Lock", style = MaterialTheme.typography.labelLarge)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OrientationLockMode.entries.forEach { mode ->
                    FilterChip(
                        selected = orientationLock == mode,
                        onClick = { onSetOrientationLock(mode) },
                        label = { Text(mode.label) },
                        modifier = Modifier.testTag("orientation_chip_${mode.name.lowercase()}")
                    )
                }
            }

            HorizontalDivider()

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Repeat, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Column(modifier = Modifier.weight(1f)) {
                    Text("A–B Scene Loop", style = MaterialTheme.typography.titleMedium)
                    val loopLabel = when {
                        playerState.abLoopStartMs != null && playerState.abLoopEndMs != null ->
                            "Looping ${formatDuration(playerState.abLoopStartMs)} -> ${formatDuration(playerState.abLoopEndMs)}"
                        playerState.abLoopStartMs != null ->
                            "Point A set at ${formatDuration(playerState.abLoopStartMs)} • Tap Set B"
                        else -> "Off"
                    }
                    Text(
                        text = loopLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (playerState.abLoopStartMs != null || playerState.abLoopEndMs != null) {
                    TextButton(onClick = { PlayerController.clearAbLoop() }) {
                        Text("Clear")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { PlayerController.setAbLoopStart() },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("ab_set_a_button")
                ) {
                    Text("Set A (${formatDuration(playerState.positionMs)})", maxLines = 1)
                }
                FilledTonalButton(
                    onClick = { PlayerController.setAbLoopEnd() },
                    enabled = playerState.abLoopStartMs != null &&
                        playerState.positionMs > (playerState.abLoopStartMs + 500L),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("ab_set_b_button")
                ) {
                    Text("Set B", maxLines = 1)
                }
            }

            HorizontalDivider()

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Timestamp Bookmarks (${bookmarks.size})", style = MaterialTheme.typography.titleMedium)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = bookmarkLabel,
                    onValueChange = { bookmarkLabel = it.take(60) },
                    label = { Text("Moment name (optional)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val finalLabel = bookmarkLabel.trim().ifEmpty {
                            "At ${formatDuration(playerState.positionMs)}"
                        }
                        onAddBookmark(finalLabel, playerState.positionMs)
                        bookmarkLabel = ""
                    },
                    enabled = playerState.activeVideoId != null,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("save_bookmark_button")
                ) {
                    Icon(Icons.Filled.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("Intro", "Key Scene", "Charge Break", "Outro").forEach { quickLabel ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            if (playerState.activeVideoId != null) {
                                onAddBookmark(quickLabel, playerState.positionMs)
                            }
                        },
                        label = { Text("+ $quickLabel") }
                    )
                }
            }

            if (bookmarks.isEmpty()) {
                Text(
                    text = "No bookmarks saved for this video yet. Save timestamps to jump back instantly across devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                bookmarks.forEach { bm ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .clickable {
                                PlayerController.seekTo(bm.positionMs)
                                onDismiss()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bookmark,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = bm.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatDuration(bm.positionMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = { onDeleteBookmark(bm.id) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete bookmark")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpNextQueueSheet(
    playerState: PlayerUiState,
    cabinUtility: CabinUtilityState,
    autoPlayNext: Boolean,
    onSetAutoPlayNext: (Boolean) -> Unit,
    onStartSleepTimer: (Int) -> Unit,
    onSetSleepEndOfVideo: (Boolean) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Filled.Bedtime, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sleep Timer", style = MaterialTheme.typography.titleMedium)
                    val sleepStatus = when {
                        cabinUtility.sleepRemainingSec > 0 ->
                            "Pausing in ${formatCountdownSeconds(cabinUtility.sleepRemainingSec)}"
                        cabinUtility.sleepAtEndOfVideo -> "Pausing at end of current video"
                        else -> "Off"
                    }
                    Text(
                        text = sleepStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (cabinUtility.sleepRemainingSec > 0 || cabinUtility.sleepAtEndOfVideo) {
                    TextButton(onClick = onCancelSleepTimer) {
                        Text("Cancel")
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(15, 30, 45, 60).forEach { mins ->
                    FilterChip(
                        selected = cabinUtility.sleepRemainingSec in ((mins - 1) * 60 + 1)..(mins * 60),
                        onClick = { onStartSleepTimer(mins) },
                        label = { Text("${mins}m") }
                    )
                }
                FilterChip(
                    selected = cabinUtility.sleepAtEndOfVideo,
                    onClick = { onSetSleepEndOfVideo(!cabinUtility.sleepAtEndOfVideo) },
                    label = { Text("End of Video") }
                )
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Up Next Queue (${playerState.queue.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-Next", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(6.dp))
                    Switch(
                        checked = autoPlayNext,
                        onCheckedChange = onSetAutoPlayNext,
                        modifier = Modifier.testTag("queue_autonext_switch")
                    )
                }
            }

            if (playerState.queue.isEmpty()) {
                Text(
                    text = "Queue is empty. Play a video or add items from your Deck.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                playerState.queue.forEachIndexed { index, item ->
                    val isCurrent = index == playerState.currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { PlayerController.jumpToQueueIndex(index) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isCurrent) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Now playing",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${item.resolutionLabel} • ${formatDuration(item.durationMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = { PlayerController.moveQueueItem(index, index - 1) },
                            enabled = index > 0,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up", modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { PlayerController.moveQueueItem(index, index + 1) },
                            enabled = index < playerState.queue.lastIndex,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down", modifier = Modifier.size(18.dp))
                        }
                        if (playerState.queue.size > 1) {
                            IconButton(
                                onClick = { PlayerController.removeQueueItem(index) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove from queue", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

fun formatCountdownSeconds(totalSec: Int): String {
    val safe = totalSec.coerceAtLeast(0)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
