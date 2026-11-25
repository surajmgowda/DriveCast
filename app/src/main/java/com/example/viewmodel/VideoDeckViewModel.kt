package com.example.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.text.Cue
import com.example.car.BluetoothTransportMonitor
import com.example.car.CabinUtilityManager
import com.example.car.CabinUtilityState
import com.example.car.TransportLinkState
import com.example.data.AspectMode
import com.example.data.CabinPreferencesState
import com.example.data.DriveCastPreferences
import com.example.data.LocalMediaLibrary
import com.example.data.PreferredTransport
import com.example.data.QualityProfile
import com.example.data.SubtitleStyleConfig
import com.example.data.SyncRepository
import com.example.data.SyncedEqPreset
import com.example.data.SyncedPlaylist
import com.example.data.SyncedSettings
import com.example.data.SyncedVideoEntry
import com.example.player.AudioEqualizerEngine
import com.example.player.EqualizerState
import com.example.player.PlayerController
import com.example.player.PlayerUiState
import com.example.safety.SafetyGateManager
import com.example.safety.SafetyGateState
import com.example.sink.SinkType
import com.example.sink.VideoSinkRouter
import com.example.sink.VideoSinkTelemetry
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

class VideoDeckViewModel(
    appContext: Context,
    private val syncRepository: SyncRepository,
    private val localLibrary: LocalMediaLibrary,
    private val transportMonitor: BluetoothTransportMonitor,
    private val preferences: DriveCastPreferences,
    private val currentUserId: String
) : ViewModel() {

    val videosState: StateFlow<UiState<List<SyncedVideoEntry>>> =
        syncRepository.observeVideos(currentUserId)
            .map<List<SyncedVideoEntry>, UiState<List<SyncedVideoEntry>>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val root = firestoreError ?: error
                Log.w(TAG, "Error observing synced videos", root)
                emit(UiState.Error(root.message ?: "Failed to sync video library"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = UiState.Loading
            )

    val playlistsState: StateFlow<UiState<List<SyncedPlaylist>>> =
        syncRepository.observePlaylists(currentUserId)
            .map<List<SyncedPlaylist>, UiState<List<SyncedPlaylist>>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val root = firestoreError ?: error
                Log.w(TAG, "Error observing playlists", root)
                emit(UiState.Error(root.message ?: "Failed to sync playlists"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = UiState.Loading
            )

    val eqPresetsState: StateFlow<UiState<List<SyncedEqPreset>>> =
        syncRepository.observeEqPresets(currentUserId)
            .map<List<SyncedEqPreset>, UiState<List<SyncedEqPreset>>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val root = firestoreError ?: error
                Log.w(TAG, "Error observing EQ presets", root)
                emit(UiState.Error(root.message ?: "Failed to sync EQ presets"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = UiState.Loading
            )

    val settingsState: StateFlow<UiState<SyncedSettings>> =
        syncRepository.observeSettings(currentUserId)
            .map<SyncedSettings?, UiState<SyncedSettings>> { settings ->
                UiState.Success(settings ?: SyncedSettings(userId = currentUserId))
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val root = firestoreError ?: error
                Log.w(TAG, "Error observing settings", root)
                emit(UiState.Error(root.message ?: "Failed to sync settings"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = UiState.Loading
            )

    val cabinPrefsState: StateFlow<CabinPreferencesState> =
        preferences.preferencesFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = CabinPreferencesState()
        )

    val playerState: StateFlow<PlayerUiState> = PlayerController.uiState
    val cuesState: StateFlow<List<Cue>> = PlayerController.cuesFlow
    val eqState: StateFlow<EqualizerState> = AudioEqualizerEngine.state
    val safetyState: StateFlow<SafetyGateState> = SafetyGateManager.state
    val sinkTelemetry: StateFlow<VideoSinkTelemetry> = VideoSinkRouter.telemetry
    val transportState: StateFlow<TransportLinkState> = transportMonitor.state
    val cabinUtilityState: StateFlow<CabinUtilityState> = CabinUtilityManager.state

    private val _isScanningMedia = MutableStateFlow(false)
    val isScanningMedia: StateFlow<Boolean> = _isScanningMedia.asStateFlow()

    private val _offlineUsageMb = MutableStateFlow(localLibrary.getOfflineCacheUsageMb())
    val offlineUsageMb: StateFlow<Long> = _offlineUsageMb.asStateFlow()

    private val _toastMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastMessages: SharedFlow<String> = _toastMessages.asSharedFlow()

    private var hasAppliedInitialPreset = false

    init {
        transportMonitor.startMonitoring()
        CabinUtilityManager.initialize(
            context = appContext,
            onPauseRequested = {
                viewModelScope.launch { PlayerController.pausePlayback() }
            },
            onNotify = { msg -> _toastMessages.tryEmit(msg) }
        )

        viewModelScope.launch {
            syncRepository.ensureDefaultPresetsSeeded()
        }

        viewModelScope.launch {
            videosState.collectLatest { state ->
                if (state is UiState.Success) {
                    PlayerController.updateLibrarySnapshot(state.data)
                }
            }
        }

        viewModelScope.launch {
            cabinPrefsState.collectLatest { prefs ->
                SafetyGateManager.setAudioDriveMode(prefs.audioDriveModeEnabled)
                CabinUtilityManager.setBatteryGuardThreshold(prefs.batteryGuardThresholdPercent)
                PlayerController.setAutoPlayNextEnabled(prefs.autoPlayNext)
            }
        }

        viewModelScope.launch {
            safetyState.collectLatest { gate ->
                transportMonitor.syncPresentationSafety(gate.isBlocked, gate.blockReason)
            }
        }

        viewModelScope.launch {
            eqPresetsState.collect { state ->
                if (!hasAppliedInitialPreset && state is UiState.Success && state.data.isNotEmpty()) {
                    hasAppliedInitialPreset = true
                    val active = state.data.firstOrNull { it.activePreset } ?: state.data.first()
                    AudioEqualizerEngine.applyPreset(
                        name = active.name,
                        bandLevelsMb = active.bandLevelsMb,
                        bassStrength = active.bassBoostStrength,
                        loudnessGainMb = active.loudnessGainMb
                    )
                }
            }
        }

        PlayerController.setProgressCheckpointListener { entry, posMs, audioIdx, subIdx, speed, aspect, delayMs ->
            viewModelScope.launch {
                syncRepository.updatePlaybackProgress(
                    videoDocId = entry.id,
                    positionMs = posMs,
                    audioTrackIndex = audioIdx,
                    subtitleTrackIndex = subIdx,
                    playbackSpeed = speed.toDouble(),
                    aspectMode = aspect,
                    audioDelayMs = delayMs
                )
            }
        }
    }

    fun refreshHardwareLinks() {
        transportMonitor.refreshAll()
        SafetyGateManager.startLocationUpdatesIfPermitted()
        SafetyGateManager.refreshThermalStatus()
    }

    fun importVideoUri(uri: Uri) {
        viewModelScope.launch {
            _isScanningMedia.value = true
            try {
                val meta = localLibrary.inspectVideoFile(uri)
                val entry = SyncedVideoEntry(
                    id = meta.docId,
                    userId = currentUserId,
                    uriString = meta.uri.toString(),
                    displayName = meta.displayName,
                    durationMs = meta.durationMs,
                    mimeType = meta.mimeType,
                    resolutionLabel = meta.resolutionLabel
                )
                val res = syncRepository.upsertVideo(entry)
                if (res.isSuccess) {
                    _toastMessages.tryEmit("Added ${meta.displayName} to DriveCast")
                } else {
                    _toastMessages.tryEmit(res.exceptionOrNull()?.localizedMessage ?: "Cloud sync failed")
                }
            } finally {
                _isScanningMedia.value = false
            }
        }
    }

    fun importFolderTree(treeUri: Uri) {
        viewModelScope.launch {
            _isScanningMedia.value = true
            try {
                val found = localLibrary.scanFolderTree(treeUri)
                if (found.isEmpty()) {
                    _toastMessages.tryEmit("No supported video files found in folder")
                } else {
                    var syncedCount = 0
                    found.forEach { meta ->
                        val entry = SyncedVideoEntry(
                            id = meta.docId,
                            userId = currentUserId,
                            uriString = meta.uri.toString(),
                            displayName = meta.displayName,
                            durationMs = meta.durationMs,
                            mimeType = meta.mimeType,
                            resolutionLabel = meta.resolutionLabel
                        )
                        if (syncRepository.upsertVideo(entry).isSuccess) {
                            syncedCount++
                        }
                    }
                    _toastMessages.tryEmit("Imported $syncedCount videos from folder")
                }
            } finally {
                _isScanningMedia.value = false
            }
        }
    }

    fun playVideo(entry: SyncedVideoEntry, queue: List<SyncedVideoEntry>) {
        val currentSettings = (settingsState.value as? UiState.Success)?.data
        val autoResume = currentSettings?.autoResume ?: true
        val profile = QualityProfile.fromRaw(currentSettings?.qualityProfile)
        PlayerController.applyQualityProfile(profile)
        VideoSinkRouter.updateQualityProfile(profile)

        val index = queue.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
        PlayerController.playVideoQueue(
            queue = queue,
            startIndex = index,
            resolveUri = { item -> localLibrary.resolveOfflineOrSourceUri(item.uriString, item.id) },
            autoResume = autoResume
        )
        viewModelScope.launch {
            localLibrary.saveLastPlayedUri(entry.uriString)
        }
    }

    fun resumeLastFromWidget(videos: List<SyncedVideoEntry>): Boolean {
        val snapshot = preferences.readWidgetSnapshot()
        val match = videos.firstOrNull { it.id == snapshot.videoId } ?: videos.firstOrNull()
        if (match != null) {
            playVideo(match, videos)
            return true
        }
        return false
    }

    fun enqueuePlayNext(entry: SyncedVideoEntry) {
        val added = PlayerController.addToQueueNext(entry) { item ->
            localLibrary.resolveOfflineOrSourceUri(item.uriString, item.id)
        }
        if (added) {
            _toastMessages.tryEmit("Queued next: ${entry.displayName}")
        }
    }

    fun enqueueAddToEnd(entry: SyncedVideoEntry) {
        val added = PlayerController.addToQueueEnd(entry) { item ->
            localLibrary.resolveOfflineOrSourceUri(item.uriString, item.id)
        }
        if (added) {
            _toastMessages.tryEmit("Added to Up Next: ${entry.displayName}")
        }
    }

    fun createPlaylist(name: String, initialVideoId: String? = null) {
        val trimmed = name.trim().ifEmpty { "Roadtrip Mix" }.take(60)
        val playlist = SyncedPlaylist(
            id = SyncRepository.stableDocId("pl_${trimmed.lowercase()}"),
            userId = currentUserId,
            name = trimmed,
            videoIds = listOfNotNull(initialVideoId)
        )
        viewModelScope.launch {
            val res = syncRepository.upsertPlaylist(playlist)
            if (res.isSuccess) {
                _toastMessages.tryEmit("Created playlist \"$trimmed\"")
            } else {
                _toastMessages.tryEmit(res.exceptionOrNull()?.localizedMessage ?: "Playlist creation failed")
            }
        }
    }

    fun toggleVideoInPlaylist(playlist: SyncedPlaylist, videoId: String) {
        val exists = videoId in playlist.videoIds
        val updatedIds = if (exists) {
            playlist.videoIds.filterNot { it == videoId }
        } else {
            playlist.videoIds + videoId
        }
        viewModelScope.launch {
            val res = syncRepository.upsertPlaylist(playlist.copy(videoIds = updatedIds))
            if (res.isSuccess) {
                val action = if (exists) "Removed from" else "Added to"
                _toastMessages.tryEmit("$action ${playlist.name}")
            }
        }
    }

    fun deletePlaylist(playlist: SyncedPlaylist) {
        viewModelScope.launch {
            val res = syncRepository.deletePlaylist(playlist.id)
            if (res.isSuccess) {
                _toastMessages.tryEmit("Deleted playlist \"${playlist.name}\"")
            }
        }
    }

    fun toggleOfflineCache(entry: SyncedVideoEntry) {
        viewModelScope.launch {
            val budgetMb = (settingsState.value as? UiState.Success)?.data?.offlineCacheLimitMb ?: 2048
            if (entry.cachedOffline) {
                localLibrary.removeOfflineCache(entry.id)
                syncRepository.upsertVideo(entry.copy(cachedOffline = false))
                _offlineUsageMb.value = localLibrary.getOfflineCacheUsageMb()
                _toastMessages.tryEmit("Removed offline cache for ${entry.displayName}")
            } else {
                _toastMessages.tryEmit("Caching ${entry.displayName} for offline car playback...")
                val ok = localLibrary.cacheVideoForOffline(
                    Uri.parse(entry.uriString),
                    entry.id,
                    budgetMb
                )
                if (ok) {
                    syncRepository.upsertVideo(entry.copy(cachedOffline = true))
                    _offlineUsageMb.value = localLibrary.getOfflineCacheUsageMb()
                    _toastMessages.tryEmit("Cached offline: ${entry.displayName}")
                } else {
                    _toastMessages.tryEmit("Unable to cache file locally")
                }
            }
        }
    }

    fun removeVideo(entry: SyncedVideoEntry) {
        viewModelScope.launch {
            localLibrary.removeOfflineCache(entry.id)
            val res = syncRepository.deleteVideo(entry.id)
            _offlineUsageMb.value = localLibrary.getOfflineCacheUsageMb()
            if (res.isFailure) {
                _toastMessages.tryEmit(res.exceptionOrNull()?.localizedMessage ?: "Delete failed")
            }
        }
    }

    fun selectEqPreset(preset: SyncedEqPreset, allPresets: List<SyncedEqPreset>) {
        AudioEqualizerEngine.applyPreset(
            name = preset.name,
            bandLevelsMb = preset.bandLevelsMb,
            bassStrength = preset.bassBoostStrength,
            loudnessGainMb = preset.loudnessGainMb
        )
        viewModelScope.launch {
            allPresets.forEach { item ->
                val shouldActivate = item.id == preset.id
                if (item.activePreset != shouldActivate) {
                    syncRepository.upsertEqPreset(item.copy(activePreset = shouldActivate))
                }
            }
        }
    }

    fun saveCustomEqPreset(name: String, existingPresets: List<SyncedEqPreset> = emptyList()) {
        val current = eqState.value
        val trimmed = name.trim().ifEmpty { "My Car Preset" }.take(60)
        val presetId = SyncRepository.stableDocId("eq_${trimmed.lowercase()}")
        val preset = SyncedEqPreset(
            id = presetId,
            userId = currentUserId,
            name = trimmed,
            bandLevelsMb = current.bands.map { it.levelMb.toLong() },
            bassBoostStrength = current.bassBoostStrength,
            loudnessGainMb = current.loudnessGainMb,
            activePreset = true
        )
        AudioEqualizerEngine.applyPreset(
            name = trimmed,
            bandLevelsMb = preset.bandLevelsMb,
            bassStrength = preset.bassBoostStrength,
            loudnessGainMb = preset.loudnessGainMb,
            virtualizerStrength = current.virtualizerStrength
        )
        viewModelScope.launch {
            existingPresets.forEach { item ->
                if (item.id != presetId && item.activePreset) {
                    syncRepository.upsertEqPreset(item.copy(activePreset = false))
                }
            }
            val res = syncRepository.upsertEqPreset(preset)
            if (res.isSuccess) {
                _toastMessages.tryEmit("Synced EQ preset \"$trimmed\" to cloud")
            } else {
                _toastMessages.tryEmit(res.exceptionOrNull()?.localizedMessage ?: "Failed to save preset")
            }
        }
    }

    fun deleteCustomEqPreset(preset: SyncedEqPreset) {
        viewModelScope.launch {
            val res = syncRepository.deleteEqPreset(preset.id)
            if (res.isSuccess) {
                _toastMessages.tryEmit("Removed preset \"${preset.name}\"")
            } else {
                _toastMessages.tryEmit(res.exceptionOrNull()?.localizedMessage ?: "Delete preset failed")
            }
        }
    }

    fun resetEqToFlat() {
        AudioEqualizerEngine.resetToFlat()
        _toastMessages.tryEmit("Reset equalizer to flat response")
    }

    fun updateSettings(
        qualityProfile: QualityProfile? = null,
        preferredTransport: PreferredTransport? = null,
        autoResume: Boolean? = null,
        offlineCacheLimitMb: Int? = null
    ) {
        val base = (settingsState.value as? UiState.Success)?.data ?: SyncedSettings(userId = currentUserId)
        val updated = base.copy(
            userId = currentUserId,
            qualityProfile = qualityProfile?.name ?: base.qualityProfile,
            preferredTransport = preferredTransport?.name ?: base.preferredTransport,
            autoResume = autoResume ?: base.autoResume,
            offlineCacheLimitMb = offlineCacheLimitMb ?: base.offlineCacheLimitMb,
            parkedOnlyEnforced = true
        )
        qualityProfile?.let {
            PlayerController.applyQualityProfile(it)
            VideoSinkRouter.updateQualityProfile(it)
        }
        preferredTransport?.let { transport ->
            val forcedSink = when (transport) {
                PreferredTransport.AUTO -> null
                PreferredTransport.ANDROID_AUTO_SURFACE -> SinkType.CAR_SURFACE
                PreferredTransport.USB_C_DISPLAY -> SinkType.MIRRORED_DISPLAY
                PreferredTransport.PHONE_ONLY -> SinkType.PHONE_SURFACE
            }
            VideoSinkRouter.routeToBestSink(forcedSink)
        }
        viewModelScope.launch {
            val res = syncRepository.upsertSettings(updated)
            if (res.isFailure) {
                _toastMessages.tryEmit(res.exceptionOrNull()?.localizedMessage ?: "Settings sync error")
            }
        }
    }

    fun setAudioDriveMode(enabled: Boolean) {
        viewModelScope.launch { preferences.setAudioDriveMode(enabled) }
    }

    fun setBatteryGuardThreshold(percent: Int) {
        viewModelScope.launch { preferences.setBatteryGuardThreshold(percent) }
    }

    fun setSubtitleStyle(style: SubtitleStyleConfig) {
        viewModelScope.launch { preferences.setSubtitleStyle(style) }
    }

    fun setAutoPlayNext(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoPlayNext(enabled) }
    }

    fun setPhoneRemoteModePreferred(enabled: Boolean) {
        viewModelScope.launch { preferences.setPhoneRemoteModePreferred(enabled) }
    }

    fun startChargingTimer(minutes: Int) = CabinUtilityManager.startChargingTimer(minutes)
    fun cancelChargingTimer() = CabinUtilityManager.cancelChargingTimer()
    fun dismissChargingAlert() = CabinUtilityManager.dismissChargingAlert()
    fun startSleepTimer(minutes: Int) = CabinUtilityManager.startSleepTimer(minutes)
    fun setSleepAtEndOfVideo(enabled: Boolean) = CabinUtilityManager.setSleepAtEndOfVideo(enabled)
    fun cancelSleepTimer() = CabinUtilityManager.cancelSleepTimer()

    override fun onCleared() {
        PlayerController.emitCheckpoint()
        transportMonitor.stopMonitoring()
        super.onCleared()
    }

    companion object {
        private const val TAG = "VideoDeckVM"
    }
}
