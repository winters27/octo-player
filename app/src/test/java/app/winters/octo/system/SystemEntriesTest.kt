package app.winters.octo.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemEntriesTest {
    @Test
    fun thePhonesOwnAddressesGiveTheirId() {
        assertEquals(42L, mediaStoreIdOf("content://media/external/audio/media/42"))
        assertEquals(7L, mediaStoreIdOf("content://media/external_primary/audio/media/7"))
        assertEquals(1234L, mediaStoreIdOf("content://com.android.providers.media.documents/document/audio%3A1234"))
        assertEquals(1234L, mediaStoreIdOf("content://com.android.providers.media.documents/document/audio:1234"))
    }

    @Test
    fun otherAddressesHaveNoId() {
        assertNull(mediaStoreIdOf("content://media/external/images/media/42"))
        assertNull(mediaStoreIdOf("content://media/external/audio/media/42/albumart"))
        assertNull(mediaStoreIdOf("content://com.android.providers.downloads.documents/document/42"))
        assertNull(mediaStoreIdOf("file:///sdcard/Music/song.mp3"))
    }

    @Test
    fun theSameNameAndLengthIsTheSameSong() {
        val candidates = listOf(
            NamedCandidate(1, durationMs = 200_000, sizeBytes = 5_000),
            NamedCandidate(2, durationMs = 180_900, sizeBytes = 4_000),
        )
        assertEquals(2L, pickSameFile(candidates, durationMs = 180_000, sizeBytes = null))
        // Too far apart to be the same recording.
        assertNull(pickSameFile(candidates, durationMs = 150_000, sizeBytes = null))
    }

    @Test
    fun theSameSizeSettlesATie() {
        val candidates = listOf(
            NamedCandidate(1, durationMs = 180_000, sizeBytes = 5_000),
            NamedCandidate(2, durationMs = 180_000, sizeBytes = 4_000),
        )
        assertEquals(2L, pickSameFile(candidates, durationMs = 180_200, sizeBytes = 4_000))
        assertEquals(1L, pickSameFile(candidates, durationMs = 180_200, sizeBytes = null))
    }

    @Test
    fun withNoLengthOnlyTheSameSizeCounts() {
        val candidates = listOf(NamedCandidate(3, durationMs = null, sizeBytes = 9_000))
        assertEquals(3L, pickSameFile(candidates, durationMs = 100_000, sizeBytes = 9_000))
        assertNull(pickSameFile(candidates, durationMs = 100_000, sizeBytes = 8_000))
        assertNull(pickSameFile(candidates, durationMs = null, sizeBytes = null))
    }

    @Test
    fun aFilesNameLosesItsType() {
        assertEquals("A song", titleFromName("A song.mp3"))
        assertEquals("v1.2 mix", titleFromName("v1.2 mix.flac"))
        assertEquals("plain", titleFromName("plain"))
        assertNull(titleFromName(".mp3"))
        assertNull(titleFromName(null))
    }

    @Test
    fun shortcutsAreKnownByTheirIds() {
        LauncherShortcut.entries.forEach { assertEquals(it, shortcutOf(ACTION_SHORTCUT, it.id)) }
        assertNull(shortcutOf(ACTION_SHORTCUT, "nothing"))
        assertNull(shortcutOf("android.intent.action.MAIN", LauncherShortcut.Liked.id))
    }
}
