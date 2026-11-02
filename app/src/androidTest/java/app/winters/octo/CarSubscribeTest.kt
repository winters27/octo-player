package app.winters.octo

import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.winters.octo.playback.OctoPlaybackService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

// A car can keep any list it opens, to hear when it changes, and is never
// sent more than the page it asked for. Needs no library.
@RunWith(AndroidJUnit4::class)
class CarSubscribeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var browser: MediaBrowser

    @Before
    fun connect() {
        val token = SessionToken(context, ComponentName(context, OctoPlaybackService::class.java))
        lateinit var pending: ListenableFuture<MediaBrowser>
        instrumentation.runOnMainSync { pending = MediaBrowser.Builder(context, token).buildAsync() }
        browser = pending.get(10, TimeUnit.SECONDS)
    }

    @After
    fun disconnect() {
        instrumentation.runOnMainSync { browser.release() }
    }

    private fun <T> ask(call: MediaBrowser.() -> ListenableFuture<LibraryResult<T>>): LibraryResult<T> {
        lateinit var pending: ListenableFuture<LibraryResult<T>>
        instrumentation.runOnMainSync { pending = browser.call() }
        return pending.get(20, TimeUnit.SECONDS)
    }

    @Test
    fun everyListACarOpensCanBeKept() {
        listOf("lib:songs", "shelf:recentlyPlayed", "shelf:nearYou", "list:a:B", "az:A:tab:albums", "tab:home").forEach { id ->
            assertEquals(id, LibraryResult.RESULT_SUCCESS, ask { subscribe(id, null) }.resultCode)
        }
    }

    @Test
    fun aSongAStationOrAnUnknownIdCannotBeKept() {
        listOf("nothing:here", "song:device:1", "station:a:B").forEach { id ->
            assertNotEquals(id, LibraryResult.RESULT_SUCCESS, ask { subscribe(id, null) }.resultCode)
        }
    }

    @Test
    fun noMoreThanThePageAskedForIsSent() {
        val result: LibraryResult<ImmutableList<MediaItem>> = ask { getChildren("tab:library", 0, 2, null) }
        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertTrue(result.value!!.size <= 2)
        val search: LibraryResult<ImmutableList<MediaItem>> = ask {
            search("a", null)
            getSearchResult("a", 0, 1, null)
        }
        assertTrue(search.value.orEmpty().size <= 1)
    }
}
