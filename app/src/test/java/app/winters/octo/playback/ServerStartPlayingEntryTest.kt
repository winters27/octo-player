package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.ServerHooks
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

// The library changes between a big list's start and its build's copy, so
// the copy works the start entry out to another song. The entry playing
// keeps the start's song (it is never pointed at the copy's), the build
// goes on from the start's place in the same answer, and each song once
// leaves out both: the start's song where the copy has it elsewhere, and
// the copy's song for the start's entry.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class ServerStartPlayingEntryTest {
    private val songs = LinkedHashMap<String, Song>()
    private val rig = ServerStartRig(songs)

    @After
    fun close() = rig.close()

    @Test
    fun theEntryPlayingKeepsItsSongAndTheBuildSkipsBothIds() {
        // The start entry matches "start" by title; a later entry is linked
        // to it too. Another library song waits for the copy's link.
        rig.db.putLibrary(listOf(libraryTrack("start", "Opening", "Band"), libraryTrack("other", "Elsewhere", "Band")), serverId = { null })
        Song("s0", title = "Opening", artist = "Band").also { songs["s0"] = it }
        (1 until 700).forEach { songs["s$it"] = Song("s$it", title = "Song ${wordOf(it)}", artist = "Filler") }
        rig.server.lists["L"] = (0 until 700).map { "s$it" }
        // s5 is the start's song by its link.
        link("s5", "start")
        val id = runBlocking { rig.store.claimList() }
        // Before the copy works the answer out, the start entry gains a link
        // to another song.
        rig.reads.hooks = object : ServerHooks() {
            override suspend fun filled(owner: String, pages: Int) {
                if (owner == WorkOwners.marked("queue:$id") && pages == 1) link("s0", "other")
            }
        }
        assertTrue(rig.play(PlayRequest(ListSpec.Online(OnlineKind.List, "L"), token = id)).started)
        assertEquals("start", rig.current())
        rig.joined()
        val queue = rig.songs()
        assertEquals("start", queue.first())
        assertEquals("the start's song once: $queue", 1, queue.count { it == "start" })
        assertTrue("the copy's song for the start entry is left out: $queue", "other" !in queue)
        // The rest follows the start's place in the answer.
        assertEquals((1 until 700).filter { it != 5 }.map { "find:s$it" }, queue.drop(1))
    }

    private fun link(serverId: String, trackId: String) = runBlocking {
        rig.db.sourceDao().putTracks(
            listOf(
                SourceTrackEntity(
                    id = "copy-$serverId", sourceId = SERVER_SOURCE, nativeId = serverId, title = "x", searchKey = "x", sortKey = "x",
                    artist = "Band", artistId = "ar", album = "Album", albumId = "al", trackNo = null, discNo = null, year = null, durationMs = 1,
                    addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null, albumOrder = 0, relinkKey = "", genre = "", mergedId = trackId,
                ),
            ),
        )
    }
}
