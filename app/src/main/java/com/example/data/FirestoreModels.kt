package com.example.data

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class AspectMode {
    FIT,
    FILL,
    ZOOM;

    companion object {
        fun fromRaw(raw: String?): AspectMode =
            entries.firstOrNull { it.name == raw } ?: FIT
    }
}

enum class QualityProfile(val label: String, val maxHeight: Int, val targetBitrateMbps: Int, val targetFps: Int) {
    AUTO_ADAPTIVE("Auto Adaptive", 1080, 12, 60),
    USB_C_1080P60("USB-C 1080p60 Hi-Bitrate", 1080, 20, 60),
    WIRELESS_720P60("Wireless 720p60 Low-Latency", 720, 8, 60),
    THERMAL_SAFE_720P30("Thermal-Safe 720p30", 720, 5, 30);

    companion object {
        fun fromRaw(raw: String?): QualityProfile =
            entries.firstOrNull { it.name == raw } ?: AUTO_ADAPTIVE
    }
}

enum class PreferredTransport(val label: String) {
    AUTO("Auto (Car Surface -> USB-C -> Phone)"),
    ANDROID_AUTO_SURFACE("Android Auto Surface"),
    USB_C_DISPLAY("Wired USB-C DisplayPort"),
    PHONE_ONLY("Phone Display Only");

    companion object {
        fun fromRaw(raw: String?): PreferredTransport =
            entries.firstOrNull { it.name == raw } ?: AUTO
    }
}

@Keep
@IgnoreExtraProperties
data class SyncedVideoEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val uriString: String = "",
    val displayName: String = "",
    val durationMs: Long = 0L,
    val lastPositionMs: Long = 0L,
    val audioTrackIndex: Int = -1,
    val subtitleTrackIndex: Int = -1,
    val playbackSpeed: Double = 1.0,
    val aspectMode: String = AspectMode.FIT.name,
    val cachedOffline: Boolean = false,
    val mimeType: String = "video/mp4",
    val resolutionLabel: String = "1080p",
    val audioDelayMs: Int = 0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class SyncedEqPreset(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val bandLevelsMb: List<Long> = listOf(0L, 0L, 0L, 0L, 0L),
    val bassBoostStrength: Int = 0,
    val loudnessGainMb: Int = 0,
    val activePreset: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class SyncedSettings(
    @DocumentId val id: String = "",
    val userId: String = "",
    val qualityProfile: String = QualityProfile.AUTO_ADAPTIVE.name,
    val autoResume: Boolean = true,
    val offlineCacheLimitMb: Int = 2048,
    val preferredTransport: String = PreferredTransport.AUTO.name,
    val parkedOnlyEnforced: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class SyncedPlaylist(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val videoIds: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

enum class SubtitleColorPreset(val label: String, val argbColor: Int) {
    WHITE("Crisp White", 0xFFFFFFFF.toInt()),
    AMBER("Warm Amber", 0xFFFBBF24.toInt()),
    CYAN("Electric Cyan", 0xFF38BDF8.toInt());

    companion object {
        fun fromRaw(raw: String?): SubtitleColorPreset =
            entries.firstOrNull { it.name == raw } ?: WHITE
    }
}

data class SubtitleStyleConfig(
    val fontScale: Float = 1.15f,
    val bgOpacity: Float = 0.72f,
    val colorPreset: SubtitleColorPreset = SubtitleColorPreset.WHITE
)
