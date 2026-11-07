package app.winters.octo.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.connection.ConnectionSettings
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.startHome
import app.winters.octo.connection.testSecurity
import app.winters.octo.offline.dropHtmlCopies
import app.winters.octo.offline.isHtmlFile
import app.winters.octo.offline.refuseHtmlStart
import app.winters.octo.offline.startsLikeHtml
import app.winters.octo.subsonic.gateway.SignInPageAnswer
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

// A sign-in page answering in place of a song, cover, loop or file from the
// server is refused: one load, never tried again, never kept, and said in
// its own words. Outside stations and real files are left alone, and pages
// kept before this are told apart and let go.
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class SignInPageBytesTest {
    private val server = MockWebServer()
    private val outside = MockWebServer()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val security = testSecurity()
    private val caches = mutableListOf<SimpleCache>()
    private lateinit var main: HttpUrl
    private lateinit var bytes: OkHttpClient

    @Before
    fun start() {
        main = server.startHome()
        outside.start()
        security.configure(main, ConnectionSettings())
        bytes = security.byteClient(security.install(OkHttpClient.Builder()).build())
    }

    @After
    fun stop() {
        caches.forEach { it.release() }
        server.close()
        outside.close()
    }

    private fun cache(): SimpleCache =
        SimpleCache(File(context.cacheDir, "media-${System.nanoTime()}"), NoOpCacheEvictor(), StandaloneDatabaseProvider(context)).also(caches::add)

    private fun signInPage() =
        MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8").body("<html><body>Sign in</body></html>").build()

    private fun open(source: DataSource, url: HttpUrl): Long = source.open(DataSpec(Uri.parse(url.toString())))

    private fun loadError(error: IOException) = LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo(0, DataSpec(Uri.parse("octo-stream://song")), 0),
        MediaLoadData(C.DATA_TYPE_MEDIA),
        error,
        1,
    )

    private fun keep(cache: Cache, key: String, content: ByteArray) {
        val source = CacheDataSource(cache, ByteArrayDataSource(content))
        source.open(DataSpec.Builder().setUri(Uri.parse("test:$key")).setKey(key).build())
        val buffer = ByteArray(4096)
        var read = 0
        while (read != C.RESULT_END_OF_INPUT) read = source.read(buffer, 0, buffer.size)
        source.close()
    }

    // Red when the byte client has no RefuseHtml, when Retries ignores the
    // cause chain (Media3 would try about four times), or when
    // playFailureWords reads the error code only ("no connection").
    @Test
    fun aSongAnsweredByASignInPageIsOneLoadNeverRetriedNorSavedAndSaysSo() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", main.resolve("/login")!!.toString()).build())
        server.enqueue(signInPage())
        val saved = cache()
        val source = CacheDataSource(saved, OkHttpDataSource.Factory(bytes).createDataSource())
        val error = assertThrows(IOException::class.java) { open(source, main.resolve("/rest/stream?id=1")!!) }
        source.close()
        assertTrue(signInPageIn(error))
        // The request and its redirect, once; the load is not tried again.
        assertEquals(2, server.requestCount)
        assertEquals(C.TIME_UNSET, Retries { true }.getRetryDelayMsFor(loadError(error)))
        assertTrue(saved.keys.isEmpty())
        val failure = PlaybackException("Source error", error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals(GatewayWords.PLAY_FAILURE, playFailureWords(failure))
    }

    // Red when finalLoadError always says final.
    @Test
    fun anOrdinaryFailureIsStillTriedAgain() {
        assertTrue(Retries { true }.getRetryDelayMsFor(loadError(IOException("Connection reset"))) != C.TIME_UNSET)
        assertEquals(C.TIME_UNSET, Retries { false }.getRetryDelayMsFor(loadError(IOException("Connection reset"))))
    }

    // Red when RefuseHtml refuses any redirect.
    @Test
    fun aRedirectToTheSongStillPlays() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", main.resolve("/song.mp3")!!.toString()).build())
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/mpeg").body("ID3 a song").build())
        val source = OkHttpDataSource.Factory(bytes).createDataSource()
        open(source, main.resolve("/rest/stream?id=1")!!)
        source.close()
        assertEquals(2, server.requestCount)
    }

    // Red when RefuseHtml refuses beyond the server's own addresses.
    @Test
    fun anOutsideStationIsNeverRefused() {
        outside.enqueue(signInPage())
        val source = OkHttpDataSource.Factory(bytes).createDataSource()
        open(source, outside.url("/live"))
        source.close()
        assertEquals(1, outside.requestCount)
    }

    // Red when the byte client (covers, loops, kept files) lets HTML through.
    @Test
    fun aCoverOrLoopAnsweredByASignInPageIsRefused() {
        server.enqueue(signInPage())
        assertThrows(SignInPageAnswer::class.java) {
            bytes.newCall(Request.Builder().url(main.resolve("/rest/getCoverArt?id=al-1")!!).build()).execute().close()
        }
    }

    // Red when dropHtmlCopies lets nothing go, or everything.
    @Test
    fun pagesKeptInAStoreGoAndMediaStays() {
        val store = cache()
        keep(store, "page", "﻿\n<!DOCTYPE html><html><body>Sign in</body></html>".toByteArray())
        keep(store, "playlist", "#EXTM3U\n#EXT-X-VERSION:6\n".toByteArray())
        keep(store, "song", byteArrayOf(0x66, 0x4C, 0x61, 0x43) + ByteArray(2_000) { 1 })
        assertTrue(store.startsLikeHtml("page"))
        assertFalse(store.startsLikeHtml("song"))
        assertEquals(1, store.dropHtmlCopies())
        assertEquals(setOf("playlist", "song"), store.keys)
    }

    // Red when a download's first bytes or a kept file are not checked.
    @Test
    fun aDownloadThatStartsAsAPageIsNeverKept() {
        val page = "  <html>".toByteArray()
        assertThrows(SignInPageAnswer::class.java) { refuseHtmlStart(page, page.size) }
        val flac = byteArrayOf(0x66, 0x4C, 0x61, 0x43, 0, 0, 0, 34)
        refuseHtmlStart(flac, flac.size)
        val kept = File(context.cacheDir, "kept-${System.nanoTime()}.flac")
        kept.writeText("<html><body aria-label=\"authentik\"></body></html>")
        assertTrue(isHtmlFile(kept))
        kept.writeBytes(flac)
        assertFalse(isHtmlFile(kept))
        kept.delete()
    }
}
