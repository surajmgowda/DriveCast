package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.DriveCastPreferences
import com.example.data.SyncRepository
import com.example.data.SyncedVideoEntry
import com.example.player.PlayerController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("DriveCast", appName)
    }

    @Test
    fun `offline library snapshots persist across SyncRepository instances`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DriveCastPreferences(context)
        val repo1 = SyncRepository(db = null, auth = null, preferences = prefs)

        val customEntry = SyncedVideoEntry(
            id = "custom_trip_clip",
            displayName = "Roadtrip Dash Clip.mp4",
            uriString = "content://media/external/video/42",
            durationMs = 120_000L
        )
        repo1.upsertVideo(customEntry)

        val restored = prefs.readVideoLibrarySnapshot()
        assertTrue(restored.any { it.id == "custom_trip_clip" && it.displayName == "Roadtrip Dash Clip.mp4" })
    }

    @Test
    fun `playerController hydrates library snapshot for headless car sessions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PlayerController.ensureLibraryHydrated(context)
        assertTrue(PlayerController.librarySnapshot.value.isNotEmpty())
    }

    @Test
    fun `playerController queues playback state before service player binds`() {
        val entry = SyncedVideoEntry(
            id = "lazy_test_clip",
            displayName = "Deferred Dash Clip.mp4",
            uriString = "content://media/external/video/99",
            durationMs = 90_000L
        )
        PlayerController.playVideoQueue(
            queue = listOf(entry),
            startIndex = 0,
            resolveUri = { android.net.Uri.parse(it.uriString) },
            autoResume = false
        )
        val state = PlayerController.uiState.value
        assertEquals("lazy_test_clip", state.activeVideoId)
        assertEquals("Deferred Dash Clip.mp4", state.title)
        assertEquals(1, state.playlistSize)
    }
}
