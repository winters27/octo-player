package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.LikedTrackEntity
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.SAVE_GROUP_ENTRIES
import app.winters.octo.catalog.SAVE_GROUP_FLOOR
import app.winters.octo.catalog.insertRow
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

// Every route that plays a list of the library gets its first song to the
// player from reads alone, as the server's routes do: no write statement and
// no transaction begun, of any kind, before the start hands over (its walk
// let go of nothing it had not written). Each route runs on a library of its
// own, with no save,
// and parked between two pages of a save's run at the floor bound and the
// default, renames in force, where a library start (a stable read) answers.
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class LibraryStartNoWriteTest(private val route: String, private val bound: Long) {
    companion object {
        private val routes = listOf("songs", "songsWhole", "shuffle", "mostPlayed", "liked", "album", "artist", "playlist", "playlistWhole", "selection", "ids")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} bound={1}")
        fun cases(): List<Array<Any>> = routes.flatMap { r -> listOf(0L, SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES).map { arrayOf<Any>(r, it) } }
    }

    private fun ServerStartRig.play(where: String, spec: ListSpec, shuffle: Boolean = false, shownOverWindow: Boolean = true) {
        var answer: PlayAnswer? = null
        val token = runBlocking { store.claimList() }
        counted { answer = play(PlayRequest(spec, null, shuffle, shownOverWindow = shownOverWindow, token = token)) }.assertNoWrite(where)
        assertTrue("$spec did not start: $answer", answer!!.started)
    }

    private fun ids(db: CatalogDatabase, n: Int): List<String> = db.query("SELECT id FROM track ORDER BY rowid LIMIT ?", arrayOf<Any?>(n)).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    // The first song's album or artist (a library put in as songs alone has
    // no album or artist rows).
    private fun first(db: CatalogDatabase, column: String): String = db.query("SELECT `$column` FROM track ORDER BY rowid LIMIT 1", null).use { it.moveToFirst(); it.getString(0) }

    // The route's list made in the library as it stands, then played.
    private fun ServerStartRig.route(where: String) {
        val songs = ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false))
        when (route) {
            "songs" -> play(where, songs)
            "songsWhole" -> play(where, songs, shownOverWindow = false)
            "shuffle" -> play(where, songs, shuffle = true)
            "mostPlayed" -> play(where, ListSpec.Songs(SongScope.All, SortOrder(SongSort.MostPlayed, true)))
            "liked" -> {
                runBlocking { db.userDao().likeAll(ids(db, 40).mapIndexed { i, id -> LikedTrackEntity(id, "rk-$id", i.toLong()) }) }
                play(where, ListSpec.Songs(SongScope.Liked, SortOrder(SongSort.DateLiked, true)))
            }
            "album" -> play(where, ListSpec.Album(first(db, "albumId"), withFinds = false))
            "artist" -> play(where, ListSpec.Artist(first(db, "artistId"), ArtistOrder.entries.first()))
            "playlist", "playlistWhole", "selection" -> {
                val rows = if (route == "playlistWhole") 40 else 600
                val songIds = ids(db, 1_000)
                runBlocking { db.userDao().insertPlaylist(PlaylistEntity("pl", "List", createdAt = 1, updatedAt = 1)) }
                for (i in 0 until rows) {
                    val id = songIds[i % songIds.size]
                    insertRow(db, "playlist_item", mapOf("playlistId" to "pl", "itemSet" to 0, "trackId" to id, "relinkKey" to "rk-$id", "ord" to i, "itemKey" to i))
                }
                when (route) {
                    "playlist" -> play(where, ListSpec.Playlist("pl"))
                    "playlistWhole" -> play(where, ListSpec.Playlist("pl"), shownOverWindow = false)
                    else -> play(where, ListSpec.Selection(ListSpec.Playlist("pl"), (0 until 30).map { "$it" }), shownOverWindow = false)
                }
            }
            "ids" -> play(where, ListSpec.Ids(ids(db, 40)), shownOverWindow = false)
        }
    }

    @Test
    fun theStartHandsOverFromReads() {
        if (bound == 0L) {
            ServerStartRig(routeSongs).use { rig ->
                rig.routeLibrary()
                rig.route("$route, no save")
            }
            return
        }
        ParkedSave(bound).use { save ->
            save.park(between = true)
            save.rig.route("$route, bound $bound, parked after page ${save.turn} of a run")
            val groups = save.release()
            assertTrue("bound $bound: $groups groups", groups >= 2)
            assertTrue("bound $bound: the park after ${save.turn} sat in no run", save.hooks.inRun(save.turn - 1))
        }
    }
}
