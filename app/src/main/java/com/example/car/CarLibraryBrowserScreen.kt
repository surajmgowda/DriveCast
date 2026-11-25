package com.example.car

import android.net.Uri
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.data.LocalMediaLibrary
import com.example.data.SyncedVideoEntry
import com.example.player.PlayerController
import com.example.safety.SafetyGateManager
import com.example.ui.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Android Auto Car App Library Screen displaying the user's DriveCast video library
 * via a host-constraint-aware [ListTemplate] so users can browse, toggle Audio-Only
 * Drive Mode, and select/play tracks safely while driving or parked.
 */
class CarLibraryBrowserScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    private var folderBootstrapAttempted = false
    private var currentPage = 0

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        CarDiagnosticLogger.log(
            source = "CarLibraryScreen",
            message = "onStart(librarySize=${PlayerController.librarySnapshot.value.size}, hostLimit=${resolveHostListLimit()})",
            level = DiagLevel.INFO
        )
        observerJob = screenScope.launch {
            combine(
                PlayerController.librarySnapshot,
                PlayerController.uiState,
                SafetyGateManager.state
            ) { library, playerState, safetyState ->
                Triple(library.size, playerState.activeVideoId to playerState.isPlaying, safetyState.isBlocked to safetyState.audioDriveModeEnabled)
            }.collectLatest {
                invalidate()
            }
        }

        if (!folderBootstrapAttempted && PlayerController.librarySnapshot.value.isEmpty()) {
            folderBootstrapAttempted = true
            bootstrapLibraryFromSavedFolder()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        observerJob?.cancel()
        observerJob = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        screenScope.cancel()
        super.onDestroy(owner)
    }

    private fun bootstrapLibraryFromSavedFolder() {
        screenScope.launch(Dispatchers.IO) {
            try {
                val localMedia = LocalMediaLibrary(carContext)
                val savedFolder = localMedia.offlineFolderUriFlow.firstOrNull() ?: return@launch
                val scanned = localMedia.scanFolderTree(Uri.parse(savedFolder))
                if (scanned.isNotEmpty() && PlayerController.librarySnapshot.value.isEmpty()) {
                    val entries = scanned.map { meta ->
                        SyncedVideoEntry(
                            id = meta.docId,
                            displayName = meta.displayName,
                            uriString = meta.uri.toString(),
                            durationMs = meta.durationMs,
                            resolutionLabel = meta.resolutionLabel,
                            mimeType = meta.mimeType
                        )
                    }
                    PlayerController.updateLibrarySnapshot(entries)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Car library folder bootstrap skipped: ${e.message}")
            }
        }
    }

    override fun onGetTemplate(): Template {
        val videos = PlayerController.librarySnapshot.value
        val playerState = PlayerController.uiState.value
        val safetyState = SafetyGateManager.state.value
        val maxListItems = resolveHostListLimit()

        val listBuilder = ItemList.Builder()

        if (videos.isEmpty()) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("No videos in DriveCast Deck")
                    .addText("Open DriveCast on your phone to add MP4/MKV files or scan a folder.")
                    .build()
            )
            listBuilder.setNoItemsMessage("Add videos on your phone to play them here.")
        } else {
            val pageRows = buildPaginatedPage(videos, maxListItems)
            val pageEntries = pageRows.entries
            val hasPrev = pageRows.hasPrev
            val hasNext = pageRows.hasNext

            if (hasPrev) {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("Previous Page")
                        .addText("Show earlier videos (${currentPage}/${pageRows.totalPages})")
                        .setOnClickListener {
                            currentPage = (currentPage - 1).coerceAtLeast(0)
                            invalidate()
                        }
                        .build()
                )
            }

            pageEntries.forEach { entry ->
                val isActive = entry.id == playerState.activeVideoId
                val statusPrefix = when {
                    isActive && playerState.isPlaying -> "[Playing] "
                    isActive -> "[Paused] "
                    else -> ""
                }
                val modeLine = when {
                    safetyState.isBlocked && safetyState.audioDriveModeEnabled ->
                        "Audio Drive • Video blanked while moving"
                    safetyState.isBlocked ->
                        "Driving (${"%.0f".format(safetyState.speedKmh)} km/h) • Tap for Audio Drive"
                    entry.lastPositionMs > 2_000L &&
                        (entry.durationMs <= 0L || entry.lastPositionMs < entry.durationMs - 3_000L) ->
                        "Resume from ${formatDuration(entry.lastPositionMs)}"
                    else ->
                        "Parked • Tap to play"
                }

                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("$statusPrefix${entry.displayName}")
                        .addText("${entry.resolutionLabel} • ${formatDuration(entry.durationMs)}")
                        .addText(modeLine)
                        .setOnClickListener {
                            onVideoSelected(entry)
                        }
                        .build()
                )
            }

            if (hasNext) {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("Next Page")
                        .addText("More videos (Page ${currentPage + 2}/${pageRows.totalPages})")
                        .setOnClickListener {
                            currentPage = (currentPage + 1).coerceAtMost(pageRows.totalPages - 1)
                            invalidate()
                        }
                        .build()
                )
            }
        }

        val headerTitle = if (videos.size > maxListItems) {
            val pages = computeTotalPages(videos.size, maxListItems)
            "DriveCast Library (${currentPage + 1}/$pages)"
        } else {
            "DriveCast Library"
        }

        val headerBuilder = Header.Builder()
            .setTitle(headerTitle)
            .setStartHeaderAction(Action.BACK)

        val audioDriveAction = Action.Builder()
            .setTitle(if (safetyState.audioDriveModeEnabled) "Audio: ON" else "Audio Drive")
            .setBackgroundColor(
                if (safetyState.audioDriveModeEnabled) CarColor.BLUE else CarColor.DEFAULT
            )
            .setOnClickListener {
                val nextEnabled = !SafetyGateManager.state.value.audioDriveModeEnabled
                SafetyGateManager.setAudioDriveMode(nextEnabled)
                if (nextEnabled && PlayerController.uiState.value.activeVideoId != null) {
                    if (!PlayerController.uiState.value.isPlaying) {
                        PlayerController.togglePlayPause()
                    }
                } else if (!nextEnabled && SafetyGateManager.shouldPausePlayback()) {
                    PlayerController.pausePlayback()
                }
                invalidate()
            }
            .build()
        headerBuilder.addEndHeaderAction(audioDriveAction)

        if (playerState.activeVideoId != null) {
            val playPauseAction = Action.Builder()
                .setTitle(if (playerState.isPlaying) "Pause" else "Play")
                .setBackgroundColor(
                    if (playerState.isPlaying) CarColor.GREEN else CarColor.PRIMARY
                )
                .setOnClickListener {
                    if (SafetyGateManager.state.value.isBlocked &&
                        !SafetyGateManager.state.value.audioDriveModeEnabled
                    ) {
                        SafetyGateManager.setAudioDriveMode(true)
                    }
                    PlayerController.togglePlayPause()
                    invalidate()
                }
                .build()
            headerBuilder.addEndHeaderAction(playPauseAction)
        }

        return ListTemplate.Builder()
            .setHeader(headerBuilder.build())
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun onVideoSelected(entry: SyncedVideoEntry) {
        PlayerController.ensureServiceConnected(carContext)
        val safetyState = SafetyGateManager.state.value
        if (safetyState.isBlocked && !safetyState.audioDriveModeEnabled) {
            SafetyGateManager.setAudioDriveMode(true)
        }
        PlayerController.playFromCarLibrary(entry)
        screenManager.pop()
    }

    private fun resolveHostListLimit(): Int {
        return try {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
                .coerceAtLeast(MIN_CAR_LIST_LIMIT)
        } catch (_: Exception) {
            MIN_CAR_LIST_LIMIT
        }
    }

    private data class PaginatedSlice(
        val entries: List<SyncedVideoEntry>,
        val hasPrev: Boolean,
        val hasNext: Boolean,
        val totalPages: Int
    )

    private fun computeTotalPages(totalItems: Int, maxRows: Int): Int {
        if (totalItems <= maxRows) return 1
        var remaining = totalItems
        var page = 0
        while (remaining > 0) {
            val reservePrev = if (page > 0) 1 else 0
            val capacityWithoutNext = (maxRows - reservePrev).coerceAtLeast(1)
            if (remaining <= capacityWithoutNext) {
                page += 1
                break
            }
            val take = (maxRows - reservePrev - 1).coerceAtLeast(1)
            remaining -= take
            page += 1
        }
        return page.coerceAtLeast(1)
    }

    private fun buildPaginatedPage(videos: List<SyncedVideoEntry>, maxRows: Int): PaginatedSlice {
        if (videos.size <= maxRows) {
            currentPage = 0
            return PaginatedSlice(videos, hasPrev = false, hasNext = false, totalPages = 1)
        }

        val totalPages = computeTotalPages(videos.size, maxRows)
        currentPage = currentPage.coerceIn(0, totalPages - 1)

        var offset = 0
        for (p in 0 until currentPage) {
            val reservePrev = if (p > 0) 1 else 0
            val take = (maxRows - reservePrev - 1).coerceAtLeast(1)
            offset += take
        }

        val hasPrev = currentPage > 0
        val reservePrev = if (hasPrev) 1 else 0
        val remaining = (videos.size - offset).coerceAtLeast(0)
        val fitsWithoutNext = remaining <= (maxRows - reservePrev).coerceAtLeast(1)
        val hasNext = !fitsWithoutNext
        val count = if (hasNext) (maxRows - reservePrev - 1).coerceAtLeast(1) else remaining

        return PaginatedSlice(
            entries = videos.subList(offset, (offset + count).coerceAtMost(videos.size)),
            hasPrev = hasPrev,
            hasNext = hasNext,
            totalPages = totalPages
        )
    }

    companion object {
        private const val TAG = "CarLibraryBrowserScreen"
        private const val MIN_CAR_LIST_LIMIT = 6
    }
}
