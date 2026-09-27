package app.winters.octo.desktop.lyrics

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.lyrics.LyricsSource
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

// Where a song's lyrics come from, in the phone's order: the server, the
// song file, then the online library. Everything here is pretend: a fake
// server and a fake library, never the real ones.
class LyricsSourcesTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = FakeServer()
    private val library = MockWebServer()
    private val libraryCalls = CopyOnWriteArrayList<String>()
    private var libraryAnswer: (RecordedRequest) -> MockResponse = { MockResponse.Builder().code(404).build() }
    private val song = Song("s1", "Karma Police", artist = "Radiohead", album = "OK Computer", duration = 264)

    init {
        library.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                libraryCalls += request.url.encodedPath
                return libraryAnswer(request)
            }
        }
        library.start()
    }

    @After
    fun close() {
        server.close()
        library.close()
    }

    private fun sources(extensions: List<String> = listOf("songLyrics:1"), settings: SettingsStore = SettingsStore(File(folder.root, "s.json"))): LyricsSources {
        server.answer("getOpenSubsonicExtensions")
        val connection = server.connection(extensions)
        return LyricsSources({ connection }, OkHttpClient(), OnlineLyrics(OkHttpClient(), library.url("/")), settings)
    }

    private fun serverLines(synced: Boolean) =
        """"lyricsList":{"structuredLyrics":[{"lang":"en","synced":$synced,"line":[{"start":0,"value":"Karma police"},{"start":4000,"value":"Arrest this man"}]}]}"""

    private fun libraryCopy(id: Long, synced: Boolean) =
        """{"id":$id,"trackName":"Karma Police","artistName":"Radiohead","albumName":"OK Computer","duration":264,"instrumental":false,""" +
            (if (synced) """"syncedLyrics":"[00:01.00]From the library"}""" else """"plainLyrics":"From the library, plain"}""")

    // An MP3 head with timed lyrics in it.
    private fun mp3WithLyrics(): ByteArray {
        val text = "[00:02.00]From the file"
        val frame = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + text.toByteArray()
        val body = "USLT".toByteArray() + byteArrayOf(0, 0, 0, frame.size.toByte(), 0, 0) + frame
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, body.size.toByte()) + body + ByteArray(32)
    }

    @Test
    fun theServersTimedLyricsComeFirst() = runBlocking {
        server.answer("getLyricsBySongId", serverLines(synced = true))
        val answer = sources().answerFor(song) as LyricsAnswer.Found
        assertEquals(LyricsSource.Server, answer.lyrics.source)
        assertEquals("Karma police", answer.lyrics.lines.first().text)
        assertTrue("the library is not asked", libraryCalls.isEmpty())
    }

    @Test
    fun theSongFileIsNextThenTheLibrary() = runBlocking {
        server.answer("getLyricsBySongId", """"lyricsList":{"structuredLyrics":[]}""")
        server.file("stream", mp3WithLyrics())
        val fromFile = sources().answerFor(song) as LyricsAnswer.Found
        assertEquals(LyricsSource.SongFile, fromFile.lyrics.source)
        assertEquals("From the file", fromFile.lyrics.lines.first().text)
        assertTrue(libraryCalls.isEmpty())
        // The stream asked for is the original file.
        val stream = server.calls.last { it.url.pathSegments.last() == "stream" }
        assertEquals("raw", stream.url.queryParameter("format"))
    }

    @Test
    fun theLibraryIsAskedWhenNothingElseHasThem() = runBlocking {
        server.answer("getLyricsBySongId", """"lyricsList":{"structuredLyrics":[]}""")
        server.file("stream", ByteArray(64))
        libraryAnswer = { MockResponse.Builder().body(libraryCopy(7, synced = true)).build() }
        val answer = sources().answerFor(song) as LyricsAnswer.Found
        assertEquals(LyricsSource.Online, answer.lyrics.source)
        assertEquals("/api/get", libraryCalls.first())
    }

    @Test
    fun plainLyricsWaitForTimedOnesElsewhere() = runBlocking {
        server.answer("getLyricsBySongId", serverLines(synced = false))
        server.file("stream", ByteArray(64))
        libraryAnswer = { MockResponse.Builder().body(libraryCopy(7, synced = true)).build() }
        val answer = sources().answerFor(song) as LyricsAnswer.Found
        assertEquals(LyricsSource.Online, answer.lyrics.source)
        assertTrue(answer.lyrics.synced)
    }

    @Test
    fun aServerThatKeepsChoicesHasTheLastWord() = runBlocking {
        server.answer("getLyricsBySongId", serverLines(synced = false))
        libraryAnswer = { MockResponse.Builder().body(libraryCopy(7, synced = true)).build() }
        val answer = sources(listOf("songLyrics:1", "octoLyrics:1")).answerFor(song) as LyricsAnswer.Found
        assertEquals(LyricsSource.Server, answer.lyrics.source)
        assertFalse(answer.lyrics.synced)
        assertTrue(libraryCalls.isEmpty())
    }

    @Test
    fun theLibraryIsLeftAloneWhenSwitchedOff() = runBlocking {
        server.answer("getLyricsBySongId", """"lyricsList":{"structuredLyrics":[]}""")
        server.file("stream", ByteArray(64))
        val settings = SettingsStore(File(folder.root, "s.json"))
        settings.update { it.copy(lyrics = it.lyrics.copy(online = false)) }
        assertEquals(LyricsAnswer.None, sources(settings = settings).answerFor(song))
        assertTrue(libraryCalls.isEmpty())
    }

    @Test
    fun aLibraryThatCannotBeReachedIsAFailureNotNone() = runBlocking {
        server.answer("getLyricsBySongId", """"lyricsList":{"structuredLyrics":[]}""")
        server.file("stream", ByteArray(64))
        libraryAnswer = { MockResponse.Builder().code(500).build() }
        val sources = sources()
        assertEquals(LyricsAnswer.Failed, sources.answerFor(song))
        // Not kept: asked again next time.
        libraryAnswer = { MockResponse.Builder().body(libraryCopy(7, synced = true)).build() }
        assertTrue(sources.answerFor(song) is LyricsAnswer.Found)
    }

    @Test
    fun hidingAndShowingAgain() = runBlocking {
        server.answer("getLyricsBySongId", serverLines(synced = true))
        val sources = sources()
        sources.hide(song)
        assertEquals(LyricsAnswer.Hidden, sources.answerFor(song))
        sources.show(song)
        assertTrue(sources.answerFor(song) is LyricsAnswer.Found)
    }

    @Test
    fun hidingOnAServerThatKeepsChoicesTellsIt() = runBlocking {
        server.answer("getLyricsBySongId", serverLines(synced = true))
        server.answer("setLyricsChoice", """"lyricsChoice":{"id":"s1","choice":"none"}""")
        sources(listOf("songLyrics:1", "octoLyrics:1")).hide(song)
        val call = server.calls.last { it.url.pathSegments.last() == "setLyricsChoice" }
        assertEquals("none", call.url.queryParameter("candidate"))
    }

    @Test
    fun aCopyPickedFromTheLibraryIsUsedFromThenOn() = runBlocking {
        server.answer("getLyricsBySongId", serverLines(synced = true))
        server.file("stream", ByteArray(64))
        libraryAnswer = { request ->
            when (request.url.encodedPath) {
                "/api/search" -> MockResponse.Builder().body("[${libraryCopy(42, synced = true)}]").build()
                "/api/get/42" -> MockResponse.Builder().body(libraryCopy(42, synced = true)).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        val settings = SettingsStore(File(folder.root, "s.json"))
        val sources = sources(settings = settings)
        val options = sources.optionsFor(song)
        assertEquals(listOf("From your server", "From LRCLIB"), options.map { it.from })
        sources.choose(song, options.last())
        assertEquals("online:42", settings.current.lyrics.picks["s1"])
        val answer = sources.answerFor(song) as LyricsAnswer.Found
        assertEquals(LyricsSource.Online, answer.lyrics.source)
        assertEquals(42L, answer.lyrics.onlineId)
    }

    @Test
    fun aServerCopyIsChosenOnTheServer() = runBlocking {
        server.answer("getLyricsBySongId", serverLines(synced = true))
        server.answer(
            "getLyricsCandidates",
            """"lyricsCandidates":{"id":"s1","choice":"auto","candidate":[{"id":"kugou:9","source":"kugou","title":"Karma Police","artist":"Radiohead","kind":"word","preview":["Karma police"]}]}""",
        )
        server.answer("setLyricsChoice", """"lyricsChoice":{"id":"s1","choice":"kugou:9"}""")
        val sources = sources(listOf("songLyrics:2", "octoLyrics:1"))
        val options = sources.optionsFor(song)
        assertEquals(listOf("Automatic", "From KuGou"), options.map { it.from })
        assertEquals(LyricsKind.Words, options[1].kind)
        sources.choose(song, options[1])
        val call = server.calls.last { it.url.pathSegments.last() == "setLyricsChoice" }
        assertEquals("kugou:9", call.url.queryParameter("candidate"))
    }
}
