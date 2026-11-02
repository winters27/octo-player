package app.winters.octo

import android.content.ComponentName
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistItemEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.searchKey
import app.winters.octo.playback.CarNode
import app.winters.octo.playback.EXTRA_SOURCE
import app.winters.octo.playback.OctoPlaybackService
import app.winters.octo.playback.PLAY_FROM_SEARCH
import app.winters.octo.playback.carId
import app.winters.octo.playback.playFromSearchArgs
import app.winters.octo.sort.SortList
import app.winters.octo.sort.sortPlaylists
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import androidx.media3.session.MediaController as AppController

// Browses a library of its own the way a car does, through the old media
// browser. It fills the catalog and the listener's playlists and empties
// them again, so it runs only on an emulator, never on a phone with
// someone's music.
@RunWith(AndroidJUnit4::class)
class CarBrowseTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val entry = EntryPointAccessors.fromApplication(context.applicationContext, CarTestEntry::class.java)
    private val connected = CountDownLatch(1)
    private lateinit var browser: MediaBrowser

    private val albums = (1..2_000).map { n ->
        val title = "Album %04d".format(n)
        AlbumEntity(
            id = "cartest:album:$n", sourceId = "device", nativeId = "$n", title = title, searchKey = searchKey(title), sortKey = searchKey(title),
            artist = "Car Test", artistId = "cartest:artist", year = 2020, songCount = 1, durationMs = 1_000, addedAt = n.toLong(), artwork = null,
        )
    }

    private fun track(id: String, title: String, album: AlbumEntity, uriIndex: Int) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = title, searchKey = searchKey(title), sortKey = searchKey(title),
        artist = "Car Test", artistId = "cartest:artist", album = album.title, albumId = album.id, trackNo = 1, discNo = 1, year = 2020,
        durationMs = 1_000, addedAt = 0, mimeType = "audio/mpeg", sizeBytes = 1, artwork = null, uri = "content://media/external/audio/media/${100_000 + uriIndex}",
    )

    // One song for each album, and two more on the first, which has three.
    private val tracks = albums.mapIndexed { i, album -> track("cartest:track:$i", "Song $i", album, i) } +
        listOf(track("cartest:track:x1", "Song x1", albums[0], 5_001), track("cartest:track:x2", "Song x2", albums[0], 5_002))
    private val artist = ArtistEntity("cartest:artist", "device", "Car Test", "car test", "car test", albums.size, tracks.size, null)

    // Three of the listener's own playlists, made and changed in different orders.
    private val playlists = listOf(
        PlaylistEntity("cartest:pl:1", "Road Trip", createdAt = 1, updatedAt = 3),
        PlaylistEntity("cartest:pl:2", "Morning", createdAt = 2, updatedAt = 2),
        PlaylistEntity("cartest:pl:3", "Late Night", createdAt = 3, updatedAt = 1),
    )

    @Before
    fun seed() {
        assumeTrue("an emulator only", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        runBlocking {
            entry.catalog().replaceAll(tracks, albums, listOf(artist))
            playlists.forEachIndexed { n, list ->
                entry.user().insertPlaylist(list)
                entry.user().insertPlaylistItems(
                    (0..1).map { k -> PlaylistItemEntity(playlistId = list.id, trackId = "cartest:track:${n * 2 + k}", relinkKey = "cartest:track:${n * 2 + k}", position = k) },
                )
            }
        }
        instrumentation.runOnMainSync {
            browser = MediaBrowser(
                context,
                ComponentName(context, OctoPlaybackService::class.java),
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() = connected.countDown()
                },
                null,
            )
            browser.connect()
        }
        assertTrue("never connected", connected.await(10, TimeUnit.SECONDS))
    }

    @After
    fun empty() {
        if (connected.count == 0L) instrumentation.runOnMainSync { browser.disconnect() }
        runBlocking {
            playlists.forEach { entry.user().deletePlaylist(it.id) }
            entry.catalog().replaceAll(emptyList(), emptyList(), emptyList())
        }
    }

    private fun children(parentId: String): List<MediaBrowser.MediaItem> {
        val loaded = CountDownLatch(1)
        val got = AtomicReference<List<MediaBrowser.MediaItem>>()
        instrumentation.runOnMainSync {
            browser.subscribe(
                parentId,
                object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, items: List<MediaBrowser.MediaItem>) {
                        got.set(items)
                        loaded.countDown()
                    }
                },
            )
        }
        assertTrue("no children of $parentId", loaded.await(20, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { browser.unsubscribe(parentId) }
        return got.get()
    }

    // A search as a Media3 browser makes it: the search, then its answer.
    private fun search(query: String): List<MediaItem> {
        val token = SessionToken(context, ComponentName(context, OctoPlaybackService::class.java))
        lateinit var pending: ListenableFuture<androidx.media3.session.MediaBrowser>
        instrumentation.runOnMainSync { pending = androidx.media3.session.MediaBrowser.Builder(context, token).buildAsync() }
        val media3 = pending.get(10, TimeUnit.SECONDS)
        try {
            lateinit var asked: ListenableFuture<LibraryResult<Void>>
            instrumentation.runOnMainSync { asked = media3.search(query, null) }
            asked.get(20, TimeUnit.SECONDS)
            lateinit var answer: ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>
            instrumentation.runOnMainSync { answer = media3.getSearchResult(query, 0, 200, null) }
            return answer.get(20, TimeUnit.SECONDS).value.orEmpty()
        } finally {
            instrumentation.runOnMainSync { media3.release() }
        }
    }

    // Every album at the end of the folders and parts it is filed under.
    private fun albumsUnder(parentId: String): List<String> = children(parentId).flatMap { item ->
        val id = item.mediaId.orEmpty()
        if (id.startsWith("album:")) listOf(id) else albumsUnder(id)
    }

    // The app's own controller of the player, as the phone's screens use it.
    private fun appController(): AppController {
        val token = SessionToken(context, ComponentName(context, OctoPlaybackService::class.java))
        lateinit var pending: com.google.common.util.concurrent.ListenableFuture<AppController>
        instrumentation.runOnMainSync { pending = AppController.Builder(context, token).buildAsync() }
        return pending.get(10, TimeUnit.SECONDS)
    }

    private fun <T> onMain(read: () -> T): T {
        val got = AtomicReference<T>()
        instrumentation.runOnMainSync { got.set(read()) }
        return got.get()
    }

    // Waits for `done` to come true, looking every 50 ms, for 10 seconds at most.
    private fun waitFor(what: String, done: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (!done()) {
            assertTrue(what, SystemClock.uptimeMillis() < end)
            SystemClock.sleep(50)
        }
    }

    private fun finish(app: AppController) {
        instrumentation.runOnMainSync {
            app.stop()
            app.clearMediaItems()
            app.release()
        }
    }

    // What heads the queue after a car plays `id` through the old browser.
    private fun playedSource(id: String): String? {
        val app = appController()
        val heard = CountDownLatch(1)
        val source = AtomicReference<String?>()
        try {
            instrumentation.runOnMainSync {
                app.addListener(
                    object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            val word = player.currentMediaItem?.mediaMetadata?.extras?.getString(EXTRA_SOURCE) ?: return
                            source.set(word)
                            heard.countDown()
                        }
                    },
                )
                MediaController(context, browser.sessionToken).transportControls.playFromMediaId(id, null)
            }
            assertTrue("nothing played for $id", heard.await(10, TimeUnit.SECONDS))
            return source.get()
        } finally {
            finish(app)
        }
    }

    @Test
    fun everyAlbumOfABigLibraryIsReachable() {
        val reached = albumsUnder("tab:albums")
        assertEquals(albums.size, reached.size)
        assertEquals(albums.map { "album:${it.id}" }.toSet(), reached.toSet())
    }

    @Test
    fun anAlbumsSongsCarryTheAlbum() {
        val album = "album:${albums.first().id}"
        val songs = children(album)
        assertEquals(3, songs.size)
        assertTrue(songs.all { it.mediaId.orEmpty().startsWith("song:") && it.mediaId.orEmpty().endsWith("|in:$album") })
    }

    @Test
    fun anAlbumPlayedFromTheCarHeadsTheQueueWithItsName() {
        assertEquals("list:${albums[4].title}", playedSource("album:${albums[4].id}"))
    }

    @Test
    fun aPlaylistOrTheSongsPlayedFromTheCarHeadTheQueueWithTheirNames() {
        assertEquals("list:Road Trip", playedSource("playlist:cartest:pl:1"))
        assertEquals("list:Songs", playedSource("lib:songs"))
    }

    @Test
    fun thePlaylistsTabListsThePhonesPlaylistsInItsOrder() {
        val expected = runBlocking {
            val order = entry.sorting().order(SortList.Playlists).first()
            sortPlaylists(entry.playlists().playlists.first().filter { list -> playlists.any { it.id == list.id } }, order).map { "playlist:${it.id}" }
        }
        val shown = children("tab:playlists").map { it.mediaId.orEmpty() }.filter { it.startsWith("playlist:cartest:") }
        assertEquals(expected, shown)
    }

    @Test
    fun aSearchFindsTheLibrarysAlbum() {
        assertTrue(search("Album 0005").any { it.mediaId == "album:${albums[4].id}" })
    }

    @Test
    fun addingFromTheCarAddsAWholeAlbumOrOnlyTheSongChosen() {
        val app = appController()
        try {
            val first = albums[0]
            instrumentation.runOnMainSync { app.addMediaItem(MediaItem.Builder().setMediaId("album:${first.id}").build()) }
            waitFor("the album was not added") { onMain { app.mediaItemCount } == 3 }
            // The controller shows the item as asked for at once, and the
            // service's song in its place once it answers.
            val chosen = carId(CarNode.Song("cartest:track:x1", CarNode.Album(first.id)))
            instrumentation.runOnMainSync { app.addMediaItem(MediaItem.Builder().setMediaId(chosen).build()) }
            waitFor("the song was not added") {
                onMain { app.mediaItemCount == 4 && app.getMediaItemAt(3).mediaId == "cartest:track:x1" }
            }
        } finally {
            finish(app)
        }
    }

    @Test
    fun aSpokenRequestThatFindsNothingLeavesTheQueueAsItWas() {
        val app = appController()
        try {
            instrumentation.runOnMainSync { app.setMediaItem(MediaItem.Builder().setMediaId("cartest:track:3").build()) }
            waitFor("the queue was not set") { onMain { app.currentMediaItem?.mediaId } == "cartest:track:3" }
            lateinit var asked: com.google.common.util.concurrent.ListenableFuture<SessionResult>
            instrumentation.runOnMainSync { asked = app.sendCustomCommand(PLAY_FROM_SEARCH, playFromSearchArgs("zzqq no such music", null)) }
            assertNotEquals(SessionResult.RESULT_SUCCESS, asked.get(10, TimeUnit.SECONDS).resultCode)
            assertEquals("cartest:track:3", onMain { app.currentMediaItem?.mediaId })
            assertEquals(1, onMain { app.mediaItemCount })
        } finally {
            finish(app)
        }
    }
}
