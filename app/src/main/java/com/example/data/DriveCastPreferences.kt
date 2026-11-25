package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.driveCastDataStore by preferencesDataStore(name = "drivecast_cabin_prefs")

data class CabinPreferencesState(
    val audioDriveModeEnabled: Boolean = false,
    val batteryGuardThresholdPercent: Int = 15,
    val subtitleStyle: SubtitleStyleConfig = SubtitleStyleConfig(),
    val autoPlayNext: Boolean = true,
    val phoneRemoteModePreferred: Boolean = true
)

data class WidgetResumeSnapshot(
    val videoId: String = "",
    val title: String = "No recent video",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val activeEqName: String = "Windsor Cabin"
)

class DriveCastPreferences(private val context: Context) {
    private val appContext = context.applicationContext
    private val widgetPrefs = appContext.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE)

    val preferencesFlow: Flow<CabinPreferencesState> = appContext.driveCastDataStore.data.map { prefs ->
        CabinPreferencesState(
            audioDriveModeEnabled = prefs[KEY_AUDIO_DRIVE_MODE] ?: false,
            batteryGuardThresholdPercent = (prefs[KEY_BATTERY_GUARD] ?: 15).coerceIn(0, 40),
            subtitleStyle = SubtitleStyleConfig(
                fontScale = (prefs[KEY_SUB_FONT_SCALE] ?: 1.15f).coerceIn(0.75f, 1.8f),
                bgOpacity = (prefs[KEY_SUB_BG_OPACITY] ?: 0.72f).coerceIn(0f, 1f),
                colorPreset = SubtitleColorPreset.fromRaw(prefs[KEY_SUB_COLOR])
            ),
            autoPlayNext = prefs[KEY_AUTO_PLAY_NEXT] ?: true,
            phoneRemoteModePreferred = prefs[KEY_PHONE_REMOTE_PREFERRED] ?: true
        )
    }

    suspend fun setAudioDriveMode(enabled: Boolean) {
        appContext.driveCastDataStore.edit { it[KEY_AUDIO_DRIVE_MODE] = enabled }
    }

    suspend fun setBatteryGuardThreshold(percent: Int) {
        appContext.driveCastDataStore.edit { it[KEY_BATTERY_GUARD] = percent.coerceIn(0, 40) }
    }

    suspend fun setSubtitleStyle(style: SubtitleStyleConfig) {
        appContext.driveCastDataStore.edit {
            it[KEY_SUB_FONT_SCALE] = style.fontScale.coerceIn(0.75f, 1.8f)
            it[KEY_SUB_BG_OPACITY] = style.bgOpacity.coerceIn(0f, 1f)
            it[KEY_SUB_COLOR] = style.colorPreset.name
        }
    }

    suspend fun setAutoPlayNext(enabled: Boolean) {
        appContext.driveCastDataStore.edit { it[KEY_AUTO_PLAY_NEXT] = enabled }
    }

    suspend fun setPhoneRemoteModePreferred(enabled: Boolean) {
        appContext.driveCastDataStore.edit { it[KEY_PHONE_REMOTE_PREFERRED] = enabled }
    }

    fun saveWidgetSnapshot(snapshot: WidgetResumeSnapshot) {
        widgetPrefs.edit()
            .putString(W_KEY_VIDEO_ID, snapshot.videoId)
            .putString(W_KEY_TITLE, snapshot.title)
            .putLong(W_KEY_POS, snapshot.positionMs)
            .putLong(W_KEY_DUR, snapshot.durationMs)
            .putString(W_KEY_EQ, snapshot.activeEqName)
            .apply()
    }

    fun readWidgetSnapshot(): WidgetResumeSnapshot {
        return WidgetResumeSnapshot(
            videoId = widgetPrefs.getString(W_KEY_VIDEO_ID, "") ?: "",
            title = widgetPrefs.getString(W_KEY_TITLE, "Tap to launch DriveCast") ?: "Tap to launch DriveCast",
            positionMs = widgetPrefs.getLong(W_KEY_POS, 0L),
            durationMs = widgetPrefs.getLong(W_KEY_DUR, 0L),
            activeEqName = widgetPrefs.getString(W_KEY_EQ, "Windsor Cabin") ?: "Windsor Cabin"
        )
    }

    companion object {
        private val KEY_AUDIO_DRIVE_MODE = booleanPreferencesKey("audio_drive_mode_enabled")
        private val KEY_BATTERY_GUARD = intPreferencesKey("battery_guard_percent")
        private val KEY_SUB_FONT_SCALE = floatPreferencesKey("subtitle_font_scale")
        private val KEY_SUB_BG_OPACITY = floatPreferencesKey("subtitle_bg_opacity")
        private val KEY_SUB_COLOR = stringPreferencesKey("subtitle_color_preset")
        private val KEY_AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
        private val KEY_PHONE_REMOTE_PREFERRED = booleanPreferencesKey("phone_remote_mode_preferred")

        private const val WIDGET_PREFS_NAME = "drivecast_widget_prefs"
        private const val W_KEY_VIDEO_ID = "video_id"
        private const val W_KEY_TITLE = "title"
        private const val W_KEY_POS = "position_ms"
        private const val W_KEY_DUR = "duration_ms"
        private const val W_KEY_EQ = "eq_name"
    }
}
