package app.winters.octo.playback

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.listening.NOW
import app.winters.octo.listening.TodayPlays
import app.winters.octo.listening.playOn
import app.winters.octo.listening.timed
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.paging.SqlOrder
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random
import app.winters.octo.playback.today.songsLike as todaySongsLike

// A labeled pin. While 4,000 or fewer songs could fit, the radio's picks
// are today's, pick for pick, under one seeded Random: the library in
// library order (sortKey, then id), songs sharing a sort key, genres in
// every case and with accents, blank genres and artists (an artist id
// stands in), one-star songs among the library and the suggestions, finds
// and library songs among the suggestions, plays from the stored totals
// (songs with no play left out), likes, songs left out by the exclusion
// function, and songs played before, a find among them. Walked in pages of
// 7, so every walk turns many pages.
@RunWith(RobolectricTestRunner::class)
class RadioPoolParityTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private val genres = listOf("Rock", "ROCK", "rock", "Röck", "Jazz", "jazz", "", " ", "Pop", "Électro", "electro")
    private val artists = listOf("Band", "band", "", "The Band", "Other", "Ñu")

    private fun library(): List<TrackEntity> {
        val r = Random(7)
        return (0 until 600).map { i ->
            radioTrack(
                id = "t%04d".format(600 - i),
                sortKey = "song %03d".format(i / 3),
                artist = artists[i % artists.size],
                artistId = "ar${i % 9}",
                genre = genres[(i * 7 + i / 5) % genres.size],
                albumId = "al${i % 40}",
                year = if (i % 13 == 0) null else 1980 + r.nextInt(40),
                rating = when {
                    i % 37 == 0 -> 1
                    i % 11 == 0 -> 5
                    else -> 0
                },
                durationMs = 120_000L + r.nextInt(200) * 1_000L,
            )
        }
    }

    @Test
    fun picksAreTodaysWhileFourThousandOrFewerFit() = timed(60) {
        val tracks = library()
        db.putTracks(tracks)
        val liked = tracks.filterIndexed { i, _ -> i % 5 == 0 }.map { it.id }
        db.likeAll(liked)
        tracks.forEachIndexed { i, t -> repeat(i % 4) { n -> db.playOn(t.id, NOW - (i * 10L + n) * 60_000) } }
        val rig = RadioRig(db, page = 7).started()
        val played = TodayPlays(db, "home").tracks()
        val byId = tracks.associateBy { it.id }
        val ordered = tracks.sortedWith { a, b -> SqlOrder.text(a.sortKey, b.sortKey).takeIf { it != 0 } ?: SqlOrder.text(a.id, b.id) }
        val find = radioTrack("find:x1", artist = "Band", genre = "Rock")
        val oneStar = radioTrack("find:x2", artist = "Ñu", genre = "Jazz", rating = 1)
        val excludedSet = tracks.filterIndexed { i, _ -> i % 17 == 0 }.map { it.id }.toSet()
        var picked = 0
        for (s in 0 until 12) {
            val seed = tracks[(s * 47) % tracks.size]
            val seeds = listOf(seed, tracks[(s * 47 + 5) % tracks.size])
            val suggestions = listOf(tracks[(s * 31) % tracks.size], find, seed, oneStar, tracks[(s * 13 + 1) % tracks.size])
            rig.suggestions = suggestions
            val before = listOf(tracks[(s * 3) % tracks.size].id, "find:gone", tracks[(s * 5 + 2) % tracks.size].id)
            val now = rig.radio.songsLike(seeds, RADIO_LENGTH, { ids -> ids.filterTo(HashSet(), excludedSet::contains) }, before, Random(s))
            val today = todaySongsLike(
                ordered, liked.toSet(), played, seeds, suggestions, libraryOnly = false, PlayerPrefs().radioTuning,
                RADIO_LENGTH, excludedSet, before, NOW, Random(s),
            )
            assertEquals("seed $s (${byId[seed.id]?.genre} by '${seed.artist}')", today, now)
            picked += now.size
        }
        assertTrue("the radios picked $picked songs", picked > 100)
        assertEquals(liked.size, db.userDao().likedAmong(liked.take(900)).first().size)
    }

    // "Library songs only" leaves the server's finds out of the radio, as
    // today.
    @Test
    fun libraryOnlyLeavesFindsOut() = timed(60) {
        val tracks = library()
        db.putTracks(tracks)
        val prefs = PlayerPrefs(libraryOnly = true)
        val rig = RadioRig(db, page = 50, prefs = prefs).started()
        val ordered = tracks.sortedWith { a, b -> SqlOrder.text(a.sortKey, b.sortKey).takeIf { it != 0 } ?: SqlOrder.text(a.id, b.id) }
        val seed = tracks[3]
        val suggestions = listOf(radioTrack("find:x1", artist = "Band", genre = "Rock"), tracks[9])
        rig.suggestions = suggestions
        val now = rig.radio.songsLike(listOf(seed), RADIO_LENGTH, random = Random(5))
        val today = todaySongsLike(ordered, emptySet(), emptyList(), listOf(seed), suggestions, true, prefs.radioTuning, RADIO_LENGTH, emptySet(), emptyList(), NOW, Random(5))
        assertEquals(today, now)
        assertTrue(now.none { it.startsWith("find:") })
    }
}
