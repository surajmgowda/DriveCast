package com.example.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.car.CabinUtilityState
import com.example.car.TransportLinkState
import com.example.data.SyncRepository
import com.example.data.SyncedPlaylist
import com.example.data.SyncedVideoEntry
import com.example.safety.SafetyGateState
import com.example.sink.VideoSinkTelemetry
import com.example.viewmodel.UiState
import java.io.File

@Composable
fun AutoCockpitTopBar(
    safetyState: SafetyGateState,
    sinkTelemetry: VideoSinkTelemetry,
    transportState: TransportLinkState,
    cabinUtility: CabinUtilityState,
    onConfirmParked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.DirectionsCar,
                        contentDescription = "Vehicle status",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = sinkTelemetry.activeSinkType.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append("${sinkTelemetry.estimatedLatencyMs}ms")
                            if (transportState.usbPresentationDisplayName != null) {
                                append(" • USB-C")
                            } else if (transportState.isEncryptedBluetoothLink) {
                                append(" • BT Secure")
                            }
                            if (cabinUtility.chargingRemainingSec > 0) {
                                append(" • EV ${formatCountdownSeconds(cabinUtility.chargingRemainingSec)}")
                            } else {
                                append(" • ${safetyState.thermalHealth.label}")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                color = when {
                    safetyState.isBlocked && safetyState.audioDriveModeEnabled ->
                        MaterialTheme.colorScheme.primaryContainer
                    safetyState.isBlocked ->
                        MaterialTheme.colorScheme.errorContainer
                    else ->
                        MaterialTheme.colorScheme.secondaryContainer
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .clickable(enabled = safetyState.needsFreshParkedConfirmation) {
                        onConfirmParked()
                    }
                    .testTag("safety_status_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (safetyState.isBlocked) Icons.Filled.WarningAmber else Icons.Filled.Shield,
                        contentDescription = "Safety status",
                        tint = when {
                            safetyState.isBlocked && safetyState.audioDriveModeEnabled ->
                                MaterialTheme.colorScheme.primary
                            safetyState.isBlocked ->
                                MaterialTheme.colorScheme.error
                            else ->
                                MaterialTheme.colorScheme.secondary
                        },
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = when {
                            safetyState.isBlocked && safetyState.audioDriveModeEnabled -> "AUDIO"
                            safetyState.isBlocked -> "LOCKED"
                            else -> "PARKED"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            safetyState.isBlocked && safetyState.audioDriveModeEnabled ->
                                MaterialTheme.colorScheme.onPrimaryContainer
                            safetyState.isBlocked ->
                                MaterialTheme.colorScheme.error
                            else ->
                                MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DeckLibraryScreen(
    videosState: UiState<List<SyncedVideoEntry>>,
    playlistsState: UiState<List<SyncedPlaylist>>,
    isScanning: Boolean,
    offlineUsageMb: Long,
    transportState: TransportLinkState,
    onPickVideoFile: () -> Unit,
    onPickFolder: () -> Unit,
    onPlayVideo: (SyncedVideoEntry, List<SyncedVideoEntry>) -> Unit,
    onEnqueuePlayNext: (SyncedVideoEntry) -> Unit,
    onEnqueueAddToEnd: (SyncedVideoEntry) -> Unit,
    onCreatePlaylist: (String, String?) -> Unit,
    onToggleVideoInPlaylist: (SyncedPlaylist, String) -> Unit,
    onDeletePlaylist: (SyncedPlaylist) -> Unit,
    onToggleOfflineCache: (SyncedVideoEntry) -> Unit,
    onDeleteVideo: (SyncedVideoEntry) -> Unit
) {
    val playlists = (playlistsState as? UiState.Success)?.data.orEmpty()
    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("Charging Break") }
    var playlistPickerVideo by remember { mutableStateOf<SyncedVideoEntry?>(null) }

    val selectedPlaylist = playlists.firstOrNull { it.id == selectedPlaylistId }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                    MaterialTheme.colorScheme.surface
                                )
                            )
                        )
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "MG Windsor Pro EV • DriveCast",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "OnePlus 15R • Hardware Decode • Offline Cache: ${offlineUsageMb} MB",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = if (transportState.usbPresentationDisplayName != null) {
                                    Icons.Filled.Usb
                                } else {
                                    Icons.Filled.BluetoothConnected
                                },
                                contentDescription = "Active transport",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = onPickVideoFile,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp)
                                    .minimumInteractiveComponentSize()
                                    .testTag("add_video_button")
                            ) {
                                Icon(Icons.Filled.AddCircle, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.action_add_video))
                            }

                            FilledTonalButton(
                                onClick = onPickFolder,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp)
                                    .minimumInteractiveComponentSize()
                                    .testTag("pick_folder_button")
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.action_pick_folder))
                            }
                        }

                        if (isScanning) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        // Custom Playlists bar
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = selectedPlaylist == null,
                        onClick = { selectedPlaylistId = null },
                        label = { Text("All Videos") },
                        modifier = Modifier.testTag("playlist_chip_all")
                    )
                    playlists.forEach { pl ->
                        FilterChip(
                            selected = selectedPlaylist?.id == pl.id,
                            onClick = { selectedPlaylistId = pl.id },
                            label = { Text("${pl.name} (${pl.videoIds.size})") },
                            modifier = Modifier.testTag("playlist_chip_${pl.id}")
                        )
                    }
                    FilledTonalButton(
                        onClick = { showCreatePlaylistDialog = true },
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("new_playlist_button"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New Playlist", style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (selectedPlaylist != null && videosState is UiState.Success) {
                    val plVideos = videosState.data.filter { it.id in selectedPlaylist.videoIds }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = {
                                plVideos.firstOrNull()?.let { first -> onPlayVideo(first, plVideos) }
                            },
                            enabled = plVideos.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.PlaylistPlay, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play ${selectedPlaylist.name}")
                        }
                        TextButton(
                            onClick = {
                                onDeletePlaylist(selectedPlaylist)
                                selectedPlaylistId = null
                            }
                        ) {
                            Text("Delete Playlist", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        when (videosState) {
            is UiState.Loading -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            is UiState.Error -> {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            text = videosState.message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            is UiState.Success -> {
                val filteredVideos = if (selectedPlaylist == null) {
                    videosState.data
                } else {
                    videosState.data.filter { it.id in selectedPlaylist.videoIds }
                }

                if (filteredVideos.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Movie,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(44.dp)
                                )
                                Text(
                                    text = if (selectedPlaylist != null) {
                                        "Playlist \"${selectedPlaylist.name}\" is empty"
                                    } else {
                                        "Your Car Video Deck is empty"
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (selectedPlaylist != null) {
                                        "Use the menu button on any video in All Videos to add items to this playlist."
                                    } else {
                                        "Tap Add Video or Pick Folder to load MP4, MKV, HEVC, or WebM files from your OnePlus 15R. Resume positions and tracks sync automatically."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(filteredVideos, key = { it.id }) { video ->
                        VideoDeckCard(
                            video = video,
                            onPlay = { onPlayVideo(video, filteredVideos) },
                            onPlayNext = { onEnqueuePlayNext(video) },
                            onAddToQueue = { onEnqueueAddToEnd(video) },
                            onAddToPlaylist = { playlistPickerVideo = video },
                            onToggleOffline = { onToggleOfflineCache(video) },
                            onDelete = { onDeleteVideo(video) }
                        )
                    }
                }
            }
        }
    }

    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("Create Car Playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Playlist Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onCreatePlaylist(newPlaylistName, null)
                        showCreatePlaylistDialog = false
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    playlistPickerVideo?.let { targetVideo ->
        AlertDialog(
            onDismissRequest = { playlistPickerVideo = null },
            title = { Text("Add to Playlist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (playlists.isEmpty()) {
                        Text("Create a playlist first using New Playlist, or tap below to quick-create \"Roadtrip\".")
                    } else {
                        playlists.forEach { pl ->
                            val checked = targetVideo.id in pl.videoIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleVideoInPlaylist(pl, targetVideo.id) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { onToggleVideoInPlaylist(pl, targetVideo.id) }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(pl.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (playlists.isEmpty()) {
                    Button(
                        onClick = {
                            onCreatePlaylist("Roadtrip", targetVideo.id)
                            playlistPickerVideo = null
                        }
                    ) {
                        Text("Create \"Roadtrip\" & Add")
                    }
                } else {
                    TextButton(onClick = { playlistPickerVideo = null }) {
                        Text("Done")
                    }
                }
            }
        )
    }
}

@Composable
private fun VideoDeckCard(
    video: SyncedVideoEntry,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleOffline: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    val thumbBitmap = remember(video.id) {
        val docId = SyncRepository.stableDocId(video.id.ifBlank { video.uriString })
        val file = File(context.cacheDir, "thumb_$docId.jpg")
        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    val progressFraction = if (video.durationMs > 0L) {
        (video.lastPositionMs.toFloat() / video.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .clickable { onPlay() }
            .testTag("video_card_${video.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 104.dp, height = 62.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbBitmap != null) {
                        Image(
                            bitmap = thumbBitmap.asImageBitmap(),
                            contentDescription = video.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.78f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play ${video.displayName}",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = video.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${video.resolutionLabel} • ${formatDuration(video.durationMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            imageVector = Icons.Filled.CloudDone,
                            contentDescription = "Cloud synced",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Row {
                    IconButton(
                        onClick = onToggleOffline,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = if (video.cachedOffline) {
                                Icons.Filled.DownloadDone
                            } else {
                                Icons.Filled.Downloading
                            },
                            contentDescription = if (video.cachedOffline) "Cached offline" else "Cache offline",
                            tint = if (video.cachedOffline) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More actions",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Play Next") },
                                onClick = {
                                    menuExpanded = false
                                    onPlayNext()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Add to Up Next Queue") },
                                onClick = {
                                    menuExpanded = false
                                    onAddToQueue()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Add to Playlist...") },
                                onClick = {
                                    menuExpanded = false
                                    onAddToPlaylist()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Remove Video") },
                                leadingIcon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }

            if (progressFraction > 0.01f) {
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
