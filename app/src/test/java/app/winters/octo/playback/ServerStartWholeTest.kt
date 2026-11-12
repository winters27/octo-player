package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// An answer of at most QUEUE_WINDOW entries plays whole from memory: no
// build, no copy and no drained file left, every song in the player, so
// Next moves at once wherever it is pressed (the player's own Next is the
// notification's, the car's and the widget's). Each step the copy takes is
// taken in memory, in its order: links, not trusted for a song the server
// marks outside the library; the pool, over every unlinked entry's title
// keys; the settle, a match group's song or a find; a find downloaded
// since as its song while the library has it; each song once; and
// library songs alone where the list keeps them.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class ServerStartWholeTest {
    private val songs = LinkedHashMap<String, Song>()
    private val rig = ServerStartRig(songs)
    private var lists = 0

    @After
    fun close() = rig.close()

    private fun song(id: String, title: String, artist: String = "Band", external: Boolean = false) =
        Song(id, title = title, artist = artist, isExternal = external).also { songs[id] = it }

    private fun whole(entries: List<String>, libraryOnly: Boolean = false): StartRead.Whole {
        val id = "W${lists++}"
        rig.server.lists[id] = entries
        return runBlocking { rig.starts.read(ListSpec.Online(OnlineKind.List, id, libraryOnly), null, false, false, null, 500L + lists) }.expect()
    }

    private fun fixture() {
        rig.db.putLibrary(listOf(libraryTrack("tl", "Linked", "Band"), libraryTrack("tx", "Outside", "Band"))) { "s-${it.id.removePrefix("t")}" }
        rig.db.putLibrary(listOf(libraryTrack("ty", "Twin", "Band"), libraryTrack("ta", "Downloaded", "Band")), serverId = { null })
        song("s-l", "Linked")
        song("s-x", "Outside", external = true)
        song("s-u", "Twin")
        song("s-u2", "Twin")
        song("s-n", "Nobody knows", "Nobody")
        song("s-a", "Downloaded", "Somebody")
        song("s-b", "Gone since", "Somebody")
        runBlocking {
            rig.db.onlineDao().insert(
                listOf(
                    OnlineSongEntity("find:s-a", SERVER_SOURCE, "s-a", "Downloaded", "Somebody", "", null, null, 1, null, null, null, 1, adoptedId = "ta"),
                    OnlineSongEntity("find:s-b", SERVER_SOURCE, "s-b", "Gone since", "Somebody", "", null, null, 1, null, null, null, 1, adoptedId = "t-missing"),
                ),
            )
        }
    }

    @Test
    fun aLinkIsTheLibrarySongUnlessTheServerMarksItOutside() {
        fixture()
        assertEquals(listOf("tl"), whole(listOf("s-l")).ids)
        // Marked outside: its link is not trusted, and no unlinked entry
        // files its title, so it is a find.
        assertEquals(listOf("find:s-x"), whole(listOf("s-x")).ids)
    }

    @Test
    fun thePoolFilesEveryUnlinkedEntrysKeys() {
        fixture()
        assertEquals(listOf("ty"), whole(listOf("s-u")).ids)
        // The entry marked outside, beside one whose keys file its title.
        song("s-o", "Outside", "Somebody else")
        assertEquals(listOf("tx", "find:s-o"), whole(listOf("s-x", "s-o")).ids)
    }

    @Test
    fun theSettleGivesAMatchGroupsSongOrAFind() {
        fixture()
        val read = whole(listOf("s-n", "s-u"))
        assertEquals(listOf("find:s-n", "ty"), read.ids)
        // The find is held for the play, with what the server said of it.
        assertEquals(listOf("find:s-n"), read.finds.map { it.id })
        assertEquals("Nobody knows", read.finds.single().title)
    }

    @Test
    fun aFindDownloadedSincePlaysAsItsSongWhileTheLibraryHasIt() {
        fixture()
        assertEquals(listOf("ta", "find:s-b"), whole(listOf("s-a", "s-b")).ids)
    }

    @Test
    fun eachSongPlaysOnceAtItsFirstEntry() {
        fixture()
        assertEquals(listOf("tl", "ty", "find:s-n"), whole(listOf("s-l", "s-u", "s-l", "s-n", "s-u2", "s-n")).ids)
    }

    @Test
    fun aListKeepingLibrarySongsPlaysThemAlone() {
        fixture()
        assertEquals(listOf("tl", "ty"), whole(listOf("s-l", "s-n", "s-u"), libraryOnly = true).ids)
    }

    @Test
    fun aSmallListPlaysWholeWithNoBuildNoCopyAndNextAtOnce() {
        fixture()
        (0 until 60).forEach { song("m$it", "Song ${wordOf(it)}") }
        rig.server.albums["A"] = "Album" to (listOf("s-l", "s-n") + (0 until 60).map { "m$it" })
        val id = runBlocking { rig.store.claimList() }
        assertTrue(rig.play(PlayRequest(ListSpec.Online(OnlineKind.Album, "A"), token = id)).started)
        assertEquals(0, rig.count("SELECT COUNT(*) FROM server_entry WHERE owner = ?", WorkOwners.marked("queue:$id")))
        assertEquals(0, rig.count("SELECT COUNT(*) FROM server_head WHERE owner = ?", WorkOwners.marked("queue:$id")))
        assertEquals(emptyList<String>(), rig.files())
        assertTrue("no build", rig.window.building.value.isEmpty())
        assertEquals(listOf("tl", "find:s-n") + (0 until 60).map { "find:m$it" }, rig.held())
        // Next moves at once: the next song is the player's already.
        rig.player.seekToNext()
        assertEquals("find:s-n", rig.current())
        rig.player.seekToNext()
        assertEquals("find:m0", rig.current())
    }
}
