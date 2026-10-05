package app.winters.octo.playback

import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRadioTest {
    private fun track(id: String, artist: String = "Artist", genre: String = "Rock", rating: Int = 0) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = "Title $id", searchKey = id, sortKey = id,
        artist = artist, artistId = "ar-$id", album = "Album", albumId = "al-1", trackNo = null, discNo = null, year = 1995,
        durationMs = 200_000, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
        genre = genre, rating = rating,
    )

    @Test
    fun aPhoneSongCarriesWhatTheRadioWeighs() {
        val song = track("t1", rating = 1)
        val radio = song.radioSong(setOf("t1"), PlayedTrack(song, plays = 7, lastPlayedAt = 1_000L))
        assertEquals(listOf("Artist"), radio.artists)
        assertEquals("al-1", radio.album)
        assertEquals(listOf("Rock"), radio.genres)
        assertEquals(1995, radio.year)
        assertEquals(200_000L, radio.durationMs)
        assertTrue(radio.liked)
        assertEquals(1, radio.rating)
        assertEquals(7L, radio.plays)
        assertEquals(1_000L, radio.lastPlayedAt)
    }

    @Test
    fun aSongWithNoNameOrGenreStillHasAnArtist() {
        val radio = track("t2", artist = "", genre = "").radioSong(emptySet(), null)
        assertEquals(listOf("ar-t2"), radio.artists)
        assertEquals(emptyList<String>(), radio.genres)
        assertFalse(radio.liked)
        assertEquals(0L, radio.plays)
        assertNull(radio.lastPlayedAt)
    }
}
