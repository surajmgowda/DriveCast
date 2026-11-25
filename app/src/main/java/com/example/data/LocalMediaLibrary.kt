package com.example.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private val Context.videoDeckDataStore: DataStore<Preferences> by preferencesDataStore(name = "videodeck_offline_store")

data class LocalVideoMetadata(
    val uri: Uri,
    val docId: String,
    val displayName: String,
    val durationMs: Long,
    val mimeType: String,
    val resolutionLabel: String,
    val sizeBytes: Long,
    val thumbnailPath: String?
)

class LocalMediaLibrary(private val context: Context) {

    private val appContext = context.applicationContext
    private val lastPlayedUriKey = stringPreferencesKey("last_played_uri")
    private val offlineFolderUriKey = stringPreferencesKey("offline_folder_uri")

    val lastPlayedUriFlow: Flow<String?> = appContext.videoDeckDataStore.data.map { prefs ->
        prefs[lastPlayedUriKey]
    }

    val offlineFolderUriFlow: Flow<String?> = appContext.videoDeckDataStore.data.map { prefs ->
        prefs[offlineFolderUriKey]
    }

    suspend fun saveLastPlayedUri(uriString: String) {
        appContext.videoDeckDataStore.edit { prefs ->
            prefs[lastPlayedUriKey] = uriString
        }
    }

    suspend fun saveFolderTreeUri(treeUri: Uri) {
        try {
            appContext.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Persistable permission not granted for folder: ${e.message}")
        }
        appContext.videoDeckDataStore.edit { prefs ->
            prefs[offlineFolderUriKey] = treeUri.toString()
        }
    }

    suspend fun inspectVideoFile(uri: Uri): LocalVideoMetadata = withContext(Dispatchers.IO) {
        try {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some transient document providers do not support persistable grants
        }

        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Video File"
        var sizeBytes = 0L
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
            }
        }

        val mimeType = appContext.contentResolver.getType(uri) ?: inferMimeType(displayName)
        var durationMs = 0L
        var resolutionLabel = "HD"
        var thumbnailPath: String? = null

        val docId = SyncRepository.stableDocId(uri.toString())
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull() ?: 0L
            resolutionLabel = buildResolutionLabel(width, height, mimeType, bitrate)

            val thumbFile = File(appContext.cacheDir, "thumb_$docId.jpg")
            if (!thumbFile.exists()) {
                val frame = retriever.getFrameAtTime(
                    (durationMs * 1000L / 4L).coerceAtLeast(1_000_000L),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                if (frame != null) {
                    FileOutputStream(thumbFile).use { out ->
                        frame.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    }
                    frame.recycle()
                }
            }
            if (thumbFile.exists()) {
                thumbnailPath = thumbFile.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "Metadata extraction failed for $uri: ${e.message}")
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        LocalVideoMetadata(
            uri = uri,
            docId = docId,
            displayName = displayName,
            durationMs = durationMs,
            mimeType = mimeType,
            resolutionLabel = resolutionLabel,
            sizeBytes = sizeBytes,
            thumbnailPath = thumbnailPath
        )
    }

    suspend fun scanFolderTree(treeUri: Uri): List<LocalVideoMetadata> = withContext(Dispatchers.IO) {
        saveFolderTreeUri(treeUri)
        val results = mutableListOf<LocalVideoMetadata>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        appContext.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val docId = if (idIdx >= 0) cursor.getString(idIdx) else continue
                val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) ?: "" else ""
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "" else ""
                val isVideo = mime.startsWith("video/") || VIDEO_EXTENSIONS.any {
                    name.lowercase().endsWith(it)
                }
                if (isVideo) {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    results += inspectVideoFile(docUri)
                }
            }
        }
        results
    }

    suspend fun cacheVideoForOffline(uri: Uri, docId: String, maxBudgetMb: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val offlineDir = File(appContext.filesDir, "offline_media").apply { mkdirs() }
                val targetFile = File(offlineDir, "$docId.media")
                if (targetFile.exists() && targetFile.length() > 0L) return@withContext true

                val budgetBytes = maxBudgetMb.toLong() * 1024L * 1024L
                val usedBytes = offlineDir.listFiles()?.sumOf { it.length() } ?: 0L
                if (usedBytes >= budgetBytes) {
                    // Evict oldest cached file if over offline cache budget
                    offlineDir.listFiles()
                        ?.minByOrNull { it.lastModified() }
                        ?.delete()
                }

                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
                targetFile.exists() && targetFile.length() > 0L
            } catch (e: Exception) {
                Log.w(TAG, "Offline cache copy failed: ${e.message}")
                false
            }
        }

    fun resolveOfflineOrSourceUri(uriString: String, docId: String): Uri {
        val offlineFile = File(File(appContext.filesDir, "offline_media"), "$docId.media")
        return if (offlineFile.exists() && offlineFile.length() > 0L) {
            Uri.fromFile(offlineFile)
        } else {
            Uri.parse(uriString)
        }
    }

    fun removeOfflineCache(docId: String) {
        val offlineFile = File(File(appContext.filesDir, "offline_media"), "$docId.media")
        if (offlineFile.exists()) {
            offlineFile.delete()
        }
    }

    fun getOfflineCacheUsageMb(): Long {
        val offlineDir = File(appContext.filesDir, "offline_media")
        val bytes = offlineDir.listFiles()?.sumOf { it.length() } ?: 0L
        return bytes / (1024L * 1024L)
    }

    private fun inferMimeType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".avi") -> "video/x-msvideo"
            else -> "video/mp4"
        }
    }

    private fun buildResolutionLabel(width: Int, height: Int, mimeType: String, bitrate: Long): String {
        val res = when {
            height >= 2160 || width >= 3840 -> "4K"
            height >= 1440 -> "1440p"
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            height > 0 -> "${height}p"
            else -> "SD"
        }
        val container = when {
            mimeType.contains("matroska") -> "MKV"
            mimeType.contains("webm") -> "WebM"
            else -> "MP4"
        }
        val mbps = if (bitrate > 0) " • ${"%.1f".format(bitrate / 1_000_000.0)}M" else ""
        return "$res $container$mbps"
    }

    companion object {
        private const val TAG = "LocalMediaLibrary"
        private val VIDEO_EXTENSIONS = listOf(".mp4", ".mkv", ".webm", ".mov", ".m4v", ".avi", ".ts")
    }
}
