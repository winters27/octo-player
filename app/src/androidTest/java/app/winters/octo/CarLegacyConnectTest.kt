package app.winters.octo

import android.content.ComponentName
import android.media.browse.MediaBrowser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.winters.octo.playback.OctoPlaybackService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// Connects the way Android Auto does, through the platform's old media
// browser rather than Media3's: the service answers it while its own main
// thread waits. Needs no library, so it runs on a fresh emulator.
@RunWith(AndroidJUnit4::class)
class CarLegacyConnectTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val connected = CountDownLatch(1)
    private lateinit var browser: MediaBrowser

    @Before
    fun connect() {
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
    }

    @After
    fun disconnect() {
        // A main thread that never answered would hold this up too.
        if (connected.count == 0L) instrumentation.runOnMainSync { browser.disconnect() }
    }

    @Test
    fun connectsAndListsTheTabs() {
        assertEquals(listOf("Home", "Radio", "Playlists", "Library"), titlesOf(root()))
    }

    @Test
    fun theLibraryListsThePhonesMusicPages() {
        assertEquals(listOf("Favorites", "Artists", "Albums", "Songs", "Genres", "Downloads"), titlesOf("tab:library"))
    }

    @Test
    fun aTabAndALibraryRowCanBeLookedUpById() {
        val home = lookUp("tab:home")
        assertNotNull(home)
        assertEquals("Home", home!!.description.title.toString())
        assertTrue(home.isBrowsable)
        assertEquals("Songs", lookUp("lib:songs")!!.description.title.toString())
        assertEquals("Home", lookUp("tab:recent")!!.description.title.toString())
    }

    @Test
    fun anUnknownIdFindsNothing() {
        assertNull(lookUp("nothing:here"))
    }

    // The browser's root, read once it has connected: the first test of a
    // run waits for the service to start.
    private fun root(): String {
        assertTrue("never connected", connected.await(10, TimeUnit.SECONDS))
        return browser.root
    }

    private fun titlesOf(parentId: String): List<String> {
        assertTrue("never connected", connected.await(10, TimeUnit.SECONDS))
        val loaded = CountDownLatch(1)
        val children = AtomicReference<List<MediaBrowser.MediaItem>>()
        instrumentation.runOnMainSync {
            browser.subscribe(
                parentId,
                object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, items: List<MediaBrowser.MediaItem>) {
                        children.set(items)
                        loaded.countDown()
                    }
                },
            )
        }
        assertTrue("no children", loaded.await(10, TimeUnit.SECONDS))
        return children.get().map { it.description.title.toString() }
    }

    // The item a car gets for an id, or null when the service says there is none.
    private fun lookUp(id: String): MediaBrowser.MediaItem? {
        assertTrue("never connected", connected.await(10, TimeUnit.SECONDS))
        val loaded = CountDownLatch(1)
        val found = AtomicReference<MediaBrowser.MediaItem?>()
        instrumentation.runOnMainSync {
            browser.getItem(
                id,
                object : MediaBrowser.ItemCallback() {
                    override fun onItemLoaded(item: MediaBrowser.MediaItem?) {
                        found.set(item)
                        loaded.countDown()
                    }

                    override fun onError(mediaId: String) = loaded.countDown()
                },
            )
        }
        assertTrue("no answer", loaded.await(10, TimeUnit.SECONDS))
        return found.get()
    }
}
