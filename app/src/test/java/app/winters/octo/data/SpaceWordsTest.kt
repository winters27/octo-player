package app.winters.octo.data

import android.database.sqlite.SQLiteFullException
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.SpaceFor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpaceWordsTest {
    // The copy's words are as they were, with a figure and without one.
    @Test
    fun theCopysWordsStay() {
        assertEquals("Not enough space to copy the library. Free up 13 MB, then try again.", NotEnoughSpace(12_500_000).userMessage())
        assertEquals("Not enough space to copy the library. Free up some space, then try again.", SQLiteFullException("full").userMessage())
        assertEquals("Not enough space to copy the library. Free up some space, then try again.", NotEnoughSpace(null).userMessage())
    }

    // A big library's ask reads with its thousands mark, as the 200k copy's 1,602 MB.
    @Test
    fun aBigAskCarriesItsComma() {
        val was = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.US)
        try {
            assertEquals("Not enough space to copy the library. Free up 1,602 MB, then try again.", NotEnoughSpace(1_601_200_000).userMessage())
        } finally {
            java.util.Locale.setDefault(was)
        }
    }

    // Each surface says what it was doing; a queue is played again.
    @Test
    fun eachSurfaceSaysWhatItWasDoing() {
        val mb = 4_000_001L
        assertEquals("Not enough space to show this list. Free up 5 MB, then try again.", NotEnoughSpace(mb, SpaceFor.List).userMessage())
        assertEquals("Not enough space to show this list. Free up some space, then try again.", NotEnoughSpace(null, SpaceFor.List).userMessage())
        assertEquals("Not enough space to update Library health. Free up 5 MB, then try again.", NotEnoughSpace(mb, SpaceFor.Health).userMessage())
        assertEquals("Not enough space to keep this list. Free up 5 MB, then try again.", NotEnoughSpace(mb, SpaceFor.KeepList).userMessage())
        assertEquals("Not enough space to update play counts. Free up 5 MB, then try again.", NotEnoughSpace(mb, SpaceFor.PlayCounts).userMessage())
        assertEquals("Not enough space to restore this backup. Free up 5 MB, then try again.", NotEnoughSpace(mb, SpaceFor.Restore).userMessage())
        assertEquals("Not enough space to save this backup. Free up 5 MB, then try again.", NotEnoughSpace(mb, SpaceFor.SaveBackup).userMessage())
        assertEquals("Not enough space to save this playlist. Free up 5 MB, then try again.", NotEnoughSpace(mb, SpaceFor.SavePlaylist).userMessage())
        assertEquals("Not enough space to queue every song. Free up 5 MB, then play it again.", NotEnoughSpace(mb, SpaceFor.Queue).userMessage())
        assertEquals("Not enough space to queue every song. Free up some space, then play it again.", NotEnoughSpace(null, SpaceFor.Queue).userMessage())
    }
}
