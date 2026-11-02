package app.winters.octo

import android.content.ComponentName
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Build
import androidx.media3.common.Player
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.searchKey
import app.winters.octo.playback.EXTRA_SOURCE
import app.winters.octo.playback.OctoPlaybackService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// Browses a library of its own the way a car does, through the old media
// browser. It fills the catalog and empties it again, so it runs only on an
// emulator, never on a phone with someone's music.
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
    private val tracks = albums.mapIndexed { i, album ->
        TrackEntity(
            id = "cartest:track:$i", sourceId = "device", nativeId = "$i", title = "Song $i", searchKey = "song $i", sortKey = "song $i",
            artist = "Car Test", artistId = "cartest:artist", album = album.title, albumId = album.id, trackNo = 1, discNo = 1, year = 2020,
            durationMs = 1_000, addedAt = 0, mimeType = "audio/mpeg", sizeBytes = 1, artwork = null, uri = "content://media/external/audio/media/${100_000 + i}",
        )
    }
    private val artist = ArtistEntity("cartest:artist", "device", "Car Test", "car test", "car test", albums.size, tracks.size, null)

    @Before
    fun seed() {
        assumeTrue("an emulator only", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        runBlocking { entry.catalog().replaceAll(tracks, albums, listOf(artist)) }
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
        assertTrue("never connected", connected.await(5, TimeUnit.SECONDS))
    }

    @After
    fun empty() {
        if (connected.count == 0L) instrumentation.runOnMainSync { browser.disconnect() }
        runBlocking { entry.catalog().replaceAll(emptyList(), emptyList(), emptyList()) }
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

    // Every album at the end of the folders and parts it is filed under.
    private fun albumsUnder(parentId: String): List<String> = children(parentId).flatMap { item ->
        val id = item.mediaId.orEmpty()
        if (id.startsWith("album:")) listOf(id) else albumsUnder(id)
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
        assertTrue(songs.isNotEmpty())
        assertTrue(songs.all { it.mediaId.orEmpty().startsWith("song:") && it.mediaId.orEmpty().endsWith("|in:$album") })
    }

    @Test
    fun anAlbumPlayedFromTheCarHeadsTheQueueWithItsName() {
        val token = SessionToken(context, ComponentName(context, OctoPlaybackService::class.java))
        lateinit var pending: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>
        instrumentation.runOnMainSync { pending = androidx.media3.session.MediaController.Builder(context, token).buildAsync() }
        val app = pending.get(10, TimeUnit.SECONDS)
        val heard = CountDownLatch(1)
        val source = AtomicReference<String?>()
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
            MediaController(context, browser.sessionToken).transportControls.playFromMediaId("album:${albums[4].id}", null)
        }
        assertTrue("nothing played", heard.await(10, TimeUnit.SECONDS))
        assertEquals("list:${albums[4].title}", source.get())
        instrumentation.runOnMainSync {
            app.stop()
            app.clearMediaItems()
            app.release()
        }
    }
}
