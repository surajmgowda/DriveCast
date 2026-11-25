package com.example.data

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

class SyncRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing cloud sync.")
    }

    fun observeVideos(userId: String): Flow<List<SyncedVideoEntry>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Signed-in user mismatch for video library." }
        val path = "users/$uid/videos"
        emitAll(
            db.collection("users").document(uid).collection("videos")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        SyncedVideoEntry::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeEqPresets(userId: String): Flow<List<SyncedEqPreset>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Signed-in user mismatch for EQ presets." }
        val path = "users/$uid/eq_presets"
        emitAll(
            db.collection("users").document(uid).collection("eq_presets")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        SyncedEqPreset::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.name }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeSettings(userId: String): Flow<SyncedSettings?> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Signed-in user mismatch for settings." }
        val path = "users/$uid/settings/preferences"
        emitAll(
            db.collection("users").document(uid).collection("settings").document("preferences")
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            SyncedSettings::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else null
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    suspend fun upsertVideo(entry: SyncedVideoEntry): Result<Unit> {
        val safeDocId = stableDocId(entry.id.ifBlank { entry.uriString })
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/videos/$safeDocId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("videos").document(safeDocId)
            docPath = docRef.path

            val sanitizedTitle = entry.displayName.trim().ifEmpty { "Untitled Video" }.take(255)
            val sanitizedUri = entry.uriString.trim().take(1024)
            require(sanitizedUri.isNotEmpty()) { "Video URI must not be blank" }
            val clampedDuration = entry.durationMs.coerceIn(0L, 86_400_000L)
            val clampedPosition = entry.lastPositionMs.coerceIn(0L, 86_400_000L)
            val clampedAudioIdx = entry.audioTrackIndex.coerceIn(-1, 64)
            val clampedSubIdx = entry.subtitleTrackIndex.coerceIn(-1, 64)
            val clampedSpeed = entry.playbackSpeed.coerceIn(0.25, 4.0)
            val validAspect = AspectMode.fromRaw(entry.aspectMode).name
            val clampedDelay = entry.audioDelayMs.coerceIn(-1000, 1000)

            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                docRef.update(
                    mapOf(
                        "uriString" to sanitizedUri,
                        "displayName" to sanitizedTitle,
                        "durationMs" to clampedDuration,
                        "lastPositionMs" to clampedPosition,
                        "audioTrackIndex" to clampedAudioIdx,
                        "subtitleTrackIndex" to clampedSubIdx,
                        "playbackSpeed" to clampedSpeed,
                        "aspectMode" to validAspect,
                        "cachedOffline" to entry.cachedOffline,
                        "mimeType" to entry.mimeType.take(100),
                        "resolutionLabel" to entry.resolutionLabel.take(50),
                        "audioDelayMs" to clampedDelay,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "uriString" to sanitizedUri,
                        "displayName" to sanitizedTitle,
                        "durationMs" to clampedDuration,
                        "lastPositionMs" to clampedPosition,
                        "audioTrackIndex" to clampedAudioIdx,
                        "subtitleTrackIndex" to clampedSubIdx,
                        "playbackSpeed" to clampedSpeed,
                        "aspectMode" to validAspect,
                        "cachedOffline" to entry.cachedOffline,
                        "mimeType" to entry.mimeType.take(100),
                        "resolutionLabel" to entry.resolutionLabel.take(50),
                        "audioDelayMs" to clampedDelay,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updatePlaybackProgress(
        videoDocId: String,
        positionMs: Long,
        audioTrackIndex: Int,
        subtitleTrackIndex: Int,
        playbackSpeed: Double,
        aspectMode: AspectMode,
        audioDelayMs: Int
    ): Result<Unit> {
        val safeDocId = stableDocId(videoDocId)
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/videos/$safeDocId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("videos").document(safeDocId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "lastPositionMs" to positionMs.coerceIn(0L, 86_400_000L),
                    "audioTrackIndex" to audioTrackIndex.coerceIn(-1, 64),
                    "subtitleTrackIndex" to subtitleTrackIndex.coerceIn(-1, 64),
                    "playbackSpeed" to playbackSpeed.coerceIn(0.25, 4.0),
                    "aspectMode" to aspectMode.name,
                    "audioDelayMs" to audioDelayMs.coerceIn(-1000, 1000),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteVideo(videoDocId: String): Result<Unit> {
        val safeDocId = stableDocId(videoDocId)
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/videos/$safeDocId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("videos").document(safeDocId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun upsertEqPreset(preset: SyncedEqPreset): Result<Unit> {
        val safeId = stableDocId(preset.id.ifBlank { preset.name })
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/eq_presets/$safeId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("eq_presets").document(safeId)
            docPath = docRef.path

            val bands = preset.bandLevelsMb.ifEmpty { listOf(0L, 0L, 0L, 0L, 0L) }.take(10)
            val safeName = preset.name.trim().ifEmpty { "Custom" }.take(60)
            val safeBass = preset.bassBoostStrength.coerceIn(0, 1000)
            val safeLoudness = preset.loudnessGainMb.coerceIn(0, 1500)

            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                docRef.update(
                    mapOf(
                        "name" to safeName,
                        "bandLevelsMb" to bands,
                        "bassBoostStrength" to safeBass,
                        "loudnessGainMb" to safeLoudness,
                        "activePreset" to preset.activePreset,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "name" to safeName,
                        "bandLevelsMb" to bands,
                        "bassBoostStrength" to safeBass,
                        "loudnessGainMb" to safeLoudness,
                        "activePreset" to preset.activePreset,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteEqPreset(presetId: String): Result<Unit> {
        val safeId = stableDocId(presetId)
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/eq_presets/$safeId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("eq_presets").document(safeId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun upsertSettings(settings: SyncedSettings): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/settings/preferences"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("settings").document("preferences")
            docPath = docRef.path

            val safeQuality = QualityProfile.fromRaw(settings.qualityProfile).name
            val safeTransport = PreferredTransport.fromRaw(settings.preferredTransport).name
            val safeCacheLimit = settings.offlineCacheLimitMb.coerceIn(256, 16384)

            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                docRef.update(
                    mapOf(
                        "qualityProfile" to safeQuality,
                        "autoResume" to settings.autoResume,
                        "offlineCacheLimitMb" to safeCacheLimit,
                        "preferredTransport" to safeTransport,
                        "parkedOnlyEnforced" to true,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "qualityProfile" to safeQuality,
                        "autoResume" to settings.autoResume,
                        "offlineCacheLimitMb" to safeCacheLimit,
                        "preferredTransport" to safeTransport,
                        "parkedOnlyEnforced" to true,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun ensureDefaultPresetsSeeded() {
        val uid = auth.currentUser?.uid ?: return
        try {
            val existing = db.collection("users").document(uid).collection("eq_presets").limit(1).get().await()
            if (existing.isEmpty) {
                DEFAULT_EQ_PRESETS.forEach { preset ->
                    upsertEqPreset(preset.copy(userId = uid))
                }
            }
            val settingsSnap = db.collection("users").document(uid).collection("settings").document("preferences").get().await()
            if (!settingsSnap.exists()) {
                upsertSettings(SyncedSettings(userId = uid))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "users/$uid/eq_presets")
        }
    }

    fun observePlaylists(userId: String): Flow<List<SyncedPlaylist>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Signed-in user mismatch for playlists." }
        val path = "users/$uid/playlists"
        emitAll(
            db.collection("users").document(uid).collection("playlists")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        SyncedPlaylist::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.name.lowercase() }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun upsertPlaylist(playlist: SyncedPlaylist): Result<Unit> {
        val safeId = stableDocId(playlist.id.ifBlank { "pl_${playlist.name.lowercase()}" })
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/playlists/$safeId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("playlists").document(safeId)
            docPath = docRef.path

            val safeName = playlist.name.trim().ifEmpty { "Roadtrip Mix" }.take(60)
            val safeVideoIds = playlist.videoIds
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.length <= 128 }
                .distinct()
                .take(100)

            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                docRef.update(
                    mapOf(
                        "name" to safeName,
                        "videoIds" to safeVideoIds,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "name" to safeName,
                        "videoIds" to safeVideoIds,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> {
        val safeId = stableDocId(playlistId)
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/playlists/$safeId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("playlists").document(safeId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    companion object {
        fun stableDocId(rawKey: String): String {
            if (rawKey.matches(Regex("^[a-zA-Z0-9_\\-]{1,120}$"))) {
                return rawKey
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(rawKey.toByteArray())
            return "vid_" + digest.take(16).joinToString("") { "%02x".format(it) }
        }

        val DEFAULT_EQ_PRESETS = listOf(
            SyncedEqPreset(
                id = "windsor_cabin",
                name = "Windsor Cabin",
                bandLevelsMb = listOf(300L, 150L, -50L, 200L, 350L),
                bassBoostStrength = 420,
                loudnessGainMb = 250,
                activePreset = true
            ),
            SyncedEqPreset(
                id = "cinema_pro",
                name = "Cinema Surround",
                bandLevelsMb = listOf(400L, 200L, 0L, 150L, 300L),
                bassBoostStrength = 600,
                loudnessGainMb = 350,
                activePreset = false
            ),
            SyncedEqPreset(
                id = "vocal_clarity",
                name = "Vocal Clarity",
                bandLevelsMb = listOf(-150L, 0L, 350L, 400L, 150L),
                bassBoostStrength = 100,
                loudnessGainMb = 450,
                activePreset = false
            ),
            SyncedEqPreset(
                id = "flat_studio",
                name = "Flat Reference",
                bandLevelsMb = listOf(0L, 0L, 0L, 0L, 0L),
                bassBoostStrength = 0,
                loudnessGainMb = 0,
                activePreset = false
            )
        )
    }
}
