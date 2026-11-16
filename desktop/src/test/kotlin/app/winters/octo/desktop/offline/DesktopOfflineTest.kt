package app.winters.octo.desktop.offline

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.SignInPages
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.offline.AdtsToM4a
import app.winters.octo.offline.KeptCopyCheck
import app.winters.octo.offline.checkKeptCopy
import app.winters.octo.playback.DownloadFormat
import app.winters.octo.subsonic.StreamQuality
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import okio.Buffer
import app.winters.octo.subsonic.gateway.GatewayCookies
import app.winters.octo.subsonic.DeviceIdentity
import app.winters.octo.subsonic.HeaderScope
import app.winters.octo.subsonic.PURPOSE_HEADER
import app.winters.octo.subsonic.serverHeaders
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.origin
import app.winters.octo.ui.family.FamilyModel
import app.winters.octo.ui.family.OFFLINE_COPIES_OFF
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Songs kept on this computer: picked by hand, the Liked songs and chosen
// playlists, fetched as offline copies, played from the folder, let go
// when no longer wanted, and refused for a family account without offline
// copies.
class DesktopOfflineTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = FakeServer()
    private val said = mutableListOf<String>()
    private var liked: List<Song> = emptyList()
    private var familyOn = false

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun song(id: String, title: String) = Song(id, title, album = "Album", artist = "Artist", track = 1, suffix = "flac")

    private fun offline(scope: CoroutineScope = this.scope): DesktopOffline {
        val settings = SettingsStore(File(folder.root, "settings.json"))
        val connection = server.connection(if (familyOn) listOf("octoFamily:1") else emptyList())
        // The server's own addresses hear what the request is for.
        val http = OkHttpClient.Builder()
            .serverHeaders {
                HeaderScope(
                    origins = setOf(origin(server.address.toHttpUrl())),
                    headers = emptyMap(),
                    gatewayOrigins = emptySet(),
                    cookies = GatewayCookies(),
                    device = DeviceIdentity("id", "Studio PC"),
                )
            }
            .build()
        val family = FamilyModel({ connection.client }, { familyOn }, scope)
        return DesktopOffline(settings, http, scope, File(folder.root, "kept"), { connection }, { liked }, family, { said += it }).also { it.start() }
    }

    private fun until(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(what, check())
    }

    @Test
    fun aSongKeptByHandIsFetchedAsAnOfflineCopyAndPlaysFromHere() {
        server.file("stream", ByteArray(1000) { 7 })
        val offline = offline()
        val one = song("tr-1", "One")
        offline.keep(listOf(one), true)
        until("kept") { offline.localFile("tr-1") != null }
        val file = offline.localFile("tr-1")!!
        assertEquals(1000L, file.length())
        assertEquals("01 One.flac", file.name)
        assertTrue(offline.isKeptByHand("tr-1"))
        val fetched = server.calls.first { it.url.pathSegments.last() == "stream" }
        assertEquals("offline", fetched.headers[PURPOSE_HEADER])
        assertEquals("raw", fetched.url.queryParameter("format"))
        // The count is updated just after the file lands.
        until("counted") { offline.status.kept == 1 }
        assertEquals(1000L, offline.status.bytes)

        // Let go: the file goes too.
        offline.keep(listOf(one), false)
        until("let go") { offline.localFile("tr-1") == null }
        assertFalse(file.exists())
    }

    // A download is named by what came: the type its bytes are, else the
    // type the server answered with, else the listed suffix.
    @Test
    fun aKeptFileIsNamedByTheTypeTheServerSent() {
        assertEquals("flac", keptSuffix("audio/flac", "m4a"))
        assertEquals("m4a", keptSuffix("audio/mp4; charset=binary", "opus"))
        assertEquals("opus", keptSuffix("audio/opus", "flac"))
        assertEquals("webm", keptSuffix("audio/webm", "m4a"))
        // A type it does not know keeps the listed suffix.
        assertEquals("flac", keptSuffix("application/octet-stream", "flac"))
        assertEquals("flac", keptSuffix(null, "flac"))
        assertEquals("audio/mp4", arrivedType("audio/mp4", "audio/opus", "audio/opus"))
        assertEquals("audio/mp4", arrivedType(null, "audio/MP4; x=1", "audio/opus"))
        assertEquals("audio/opus", arrivedType(null, "application/octet-stream", "audio/opus"))
    }

    // ADTS AAC as a server makes it: `count` frames of AAC-LC, 44.1 kHz stereo.
    private fun adts(count: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        repeat(count) {
            val length = 7 + 100
            out.write(
                byteArrayOf(
                    0xFF.toByte(), 0xF1.toByte(), 0x50, (0x80 or (length shr 11)).toByte(),
                    ((length shr 3) and 0xFF).toByte(), (((length and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte(),
                ),
            )
            out.write(ByteArray(100) { 0x21 })
        }
        return out.toByteArray()
    }

    // The start of an M4A file, as a server sends one.
    private fun m4a(): ByteArray = byteArrayOf(0, 0, 0, 0x18) + "ftypM4A ".toByteArray() + ByteArray(200) { 1 }

    private fun answerWith(type: String, bytes: ByteArray) =
        server.respond("stream") { MockResponse.Builder().setHeader("Content-Type", type).body(Buffer().write(bytes)).build() }

    // A lossy song already at or under the quality downloads as it is; a
    // lossless one asks for the chosen format at the quality's bitrate,
    // never with a length estimate. Red when the format is ignored or the
    // estimate is sent.
    @Test
    fun aDownloadAsksForTheChosenFormatOnlyWhenItMakesTheFileSmaller() {
        val flac = song("tr-1", "One")
        assertEquals(mapOf("format" to "raw"), downloadParams(flac, StreamQuality.Original, DownloadFormat.Opus))
        assertEquals(mapOf("format" to "opus", "maxBitRate" to "160"), downloadParams(flac, StreamQuality.Standard, DownloadFormat.Opus))
        assertEquals(mapOf("format" to "aac", "maxBitRate" to "96"), downloadParams(flac, StreamQuality.DataSaver, DownloadFormat.Aac))
        val small = song("tr-2", "Two").copy(suffix = "mp3", contentType = "audio/mpeg", bitRate = 128)
        assertEquals(mapOf("format" to "raw"), downloadParams(small, StreamQuality.Standard, DownloadFormat.Mp3))
        val big = small.copy(bitRate = 320)
        assertEquals(mapOf("format" to "mp3", "maxBitRate" to "160"), downloadParams(big, StreamQuality.Standard, DownloadFormat.Mp3))
    }

    // An Opus ask the server answers with an M4A is kept as the M4A it is,
    // noted as made smaller. Red while a smaller copy is always named .opus.
    @Test
    fun anAnswerOfAnotherTypeIsKeptAsWhatItIs() {
        answerWith("audio/mp4", m4a())
        val offline = offline()
        offline.setDownloadQuality(StreamQuality.Standard)
        offline.keep(listOf(song("tr-1", "One")), true)
        until("kept") { offline.localFile("tr-1") != null }
        assertEquals("01 One.m4a", offline.localFile("tr-1")!!.name)
        assertEquals("opus-160", offline.files.single().made)
        assertTrue(File(folder.root, "kept").walk().none { it.name.endsWith(".part") })
    }

    // AAC in ADTS is kept in an M4A, so it says its own length. Red when
    // the ADTS file is kept as it came.
    @Test
    fun aacInAdtsIsKeptAsAnM4a() {
        answerWith("audio/aac", adts(40))
        val offline = offline()
        offline.setDownloadQuality(StreamQuality.Standard)
        offline.setDownloadFormat(DownloadFormat.Aac)
        offline.keep(listOf(song("tr-1", "One")), true)
        until("kept") { offline.localFile("tr-1") != null }
        val file = offline.localFile("tr-1")!!
        assertEquals("01 One.m4a", file.name)
        assertEquals("ftyp", String(file.readBytes().copyOfRange(4, 8)))
        assertEquals(40, AdtsToM4a.samplesIn(file))
        assertEquals("aac-160", offline.files.single().made)
    }

    // A file kept before `made` was noted reads as it always did: found,
    // played, and checked by the old rule. Red when `made` is required.
    @Test
    fun filesKeptBeforeTheFormatWasNotedAreFoundAndCheckedAsBefore() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """[{"server":"s","id":"tr-1","path":"s/Artist/Album/01 One.mp3","size":500,"title":"One","artist":"Artist","fileSize":500}]"""
        val kept = json.decodeFromString(ListSerializer(KeptFile.serializer()), old).single()
        assertNull(kept.made)
        // A kept MP3 of an MP3 song was the server's file (smaller copies were
        // all Opus then), so it is as it is, by the old rule and still.
        assertTrue(keptAsIs(kept.path, "mp3", kept.made))
        assertTrue(keptAsIs("s/Artist/Album/01 One.flac", "flac", null))
        assertEquals(KeptCopyCheck.Keep, checkKeptCopy(500, 500, 500, keptAsIs(kept.path, "mp3", kept.made)))
        assertEquals(KeptCopyCheck.Refetch, checkKeptCopy(500, 800, 500, keptAsIs(kept.path, "mp3", kept.made)))
    }

    // A smaller copy of a song's own type is never taken for the server's
    // file: with no size noted, a new server size is noted, not fetched
    // again. Red when `made` is ignored.
    @Test
    fun aSmallerCopyOfTheSongsOwnTypeIsNeverTheFileAsItIs() {
        assertFalse(keptAsIs("s/Artist/Album/01 One.mp3", "mp3", "mp3-160"))
        assertFalse(keptAsIs("s/Artist/Album/01 One.flac", "flac", "server"))
        assertEquals(KeptCopyCheck.Note, checkKeptCopy(null, 9_000, 4_000, keptAsIs("s/Artist/Album/01 One.mp3", "mp3", "mp3-160")))
        assertEquals(KeptCopyCheck.Refetch, checkKeptCopy(null, 9_000, 4_000, keptAsIs("s/Artist/Album/01 One.mp3", "mp3", null)))
    }

    // Part files a stopped run left in the server's folder go at the next
    // start; the animated art's own part files and anything else stay.
    // Red without the cleanup, or with one that walks the whole folder.
    @Test
    fun partFilesLeftInTheServersFolderGoAtTheNextStart() {
        server.file("stream", ByteArray(10) { 1 })
        val first = offline()
        first.keep(listOf(song("tr-1", "One")), true)
        until("kept") { first.localFile("tr-1") != null }
        val kept = first.localFile("tr-1")!!
        val serverFolder = kept.parentFile.parentFile.parentFile
        val left = File(kept.parentFile, "02 Two.download.part").apply { writeText("x") }
        val loops = File(serverFolder, ".animated/al-1.part").apply { parentFile.mkdirs(); writeText("x") }
        val mine = File(folder.root, "kept/mine.part").apply { writeText("x") }
        val again = offline()
        until("cleaned") { !left.exists() }
        assertTrue(loops.exists())
        assertTrue(mine.exists())
        assertTrue(kept.exists())
        assertTrue(again.localFile("tr-1") != null)
    }

    // Letting every song go while one is put into its M4A keeps nothing of
    // it. Red when the rename does not look again first.
    @Test
    fun lettingGoWhileARewrapRunsKeepsNothing() {
        answerWith("audio/aac", adts(40))
        val offline = offline()
        offline.setDownloadQuality(StreamQuality.Standard)
        offline.setDownloadFormat(DownloadFormat.Aac)
        val rewrapped = java.util.concurrent.atomic.AtomicBoolean(false)
        offline.rewrapAdts = { input, output ->
            offline.removeAll()
            AdtsToM4a.rewrap(input, output).also { rewrapped.set(true) }
        }
        offline.keep(listOf(song("tr-1", "One")), true)
        until("rewrapped") { rewrapped.get() }
        until("settled") { !offline.status.busy }
        until("nothing kept") { File(folder.root, "kept").walk().none { it.isFile && it.name != DesktopOffline.INDEX } }
        assertTrue(offline.files.none { it.id == "tr-1" })
    }

    @Test
    fun aLowerDownloadQualityFetchesOpusThroughTheStreamParameters() {
        server.file("stream", ByteArray(100) { 3 })
        val offline = offline()
        offline.setDownloadQuality(app.winters.octo.subsonic.StreamQuality.Standard)
        offline.keep(listOf(song("tr-1", "One")), true)
        until("kept") { offline.localFile("tr-1") != null }
        assertEquals("01 One.opus", offline.localFile("tr-1")!!.name)
        val fetched = server.calls.first { it.url.pathSegments.last() == "stream" }
        assertEquals("opus", fetched.url.queryParameter("format"))
        assertEquals("160", fetched.url.queryParameter("maxBitRate"))
        assertEquals("offline", fetched.headers[PURPOSE_HEADER])
    }

    @Test
    fun likedSongsAndAPlaylistAreKeptWhileChosen() {
        server.file("stream", ByteArray(10) { 1 })
        liked = listOf(song("tr-1", "Liked").copy(starred = "2026-10-01T00:00:00Z"), song("tr-2", "Not liked"))
        server.answer("getPlaylist", """"playlist":{"id":"p1","name":"Road trip","entry":[${app.winters.octo.desktop.songJson("tr-9", "Trip song")}]}""")
        val offline = offline()
        offline.keepLiked(true)
        offline.keepPlaylist("p1", true)
        until("both kept") { offline.localFile("tr-1") != null && offline.localFile("tr-9") != null }
        assertNull(offline.localFile("tr-2"))
        assertTrue(offline.isPlaylistKept("p1"))
        offline.keepPlaylist("p1", false)
        until("playlist let go") { offline.localFile("tr-9") == null }
        assertTrue(offline.localFile("tr-1") != null)
    }

    @Test
    fun aFamilyAccountWithoutOfflineCopiesGetsNoneButKeepsWhatItHas() {
        familyOn = true
        server.answer("getFamily", """"family":{"me":{"username":"winters","role":"Listener","managed":true,"abilities":{"offlineCopies":false}}}""", type = "octo")
        server.answer("getFamilyDevices", """"familyDevices":{"device":[]}""", type = "octo")
        server.file("stream", ByteArray(10) { 1 })
        val offline = offline()
        offline.keep(listOf(song("tr-1", "One")), true)
        until("refused") { OFFLINE_COPIES_OFF in said }
        assertNull(offline.localFile("tr-1"))
        assertTrue(server.calls.none { it.url.pathSegments.last() == "stream" })
        assertEquals(1, offline.status.waiting)
    }

    @Test
    fun aKeptFileOfASwappedServerFileIsFetchedAgain() {
        server.file("stream", ByteArray(1000) { 7 })
        liked = listOf(song("tr-1", "One").copy(size = 1000))
        val offline = offline()
        offline.keep(liked, true)
        until("kept") { offline.localFile("tr-1") != null }
        assertEquals(1000L, offline.files.single().fileSize)
        // The server swaps the song's file for another under the same id.
        server.file("stream", ByteArray(800) { 9 })
        liked = listOf(song("tr-1", "One").copy(size = 800))
        offline.poke()
        until("fetched again") { offline.localFile("tr-1")?.length() == 800L }
        assertEquals(800L, offline.files.single().fileSize)
        assertEquals(2, server.calls.count { it.url.pathSegments.last() == "stream" })
    }

    @Test
    fun aFileKeptBeforeSizesWereNotedIsCheckedByItsOwnSize() {
        server.file("stream", ByteArray(1000) { 7 })
        liked = listOf(song("tr-1", "One").copy(size = 1000))
        val first = offline()
        first.keep(liked, true)
        until("kept") { first.localFile("tr-1") != null }
        // As an older list of kept files has it: no size noted.
        val index = File(first.folder, DesktopOffline.INDEX)
        until("listed") { index.isFile && "fileSize" in index.readText() }
        index.writeText(index.readText().replace(Regex(",\\s*\"fileSize\":\\s*\\d+"), ""))
        val again = offline()
        until("noted") { again.files.singleOrNull()?.fileSize == 1000L }
        assertTrue(again.localFile("tr-1") != null)
        assertEquals(1, server.calls.count { it.url.pathSegments.last() == "stream" })
    }

    @Test
    fun aKeptFileIsTheFileAsItIsWhenItHasTheSongsType() {
        assertTrue(keptAsIs("server/Artist/Album/01 One.flac", "flac"))
        assertTrue(keptAsIs("server/Artist/Album/01 One.FLAC", "flac"))
        assertFalse(keptAsIs("server/Artist/Album/01 One.opus", "flac"))
        // Smaller copies were made as Opus before `made` was noted, so an
        // Opus file never says.
        assertFalse(keptAsIs("server/Artist/Album/01 One.opus", "opus"))
        assertFalse(keptAsIs("server/Artist/Album/01 One", null))
        // A copy made smaller never is.
        assertFalse(keptAsIs("server/Artist/Album/01 One.flac", "flac", "opus-160"))
    }

    @Test
    fun keptSongsAreFoundAgainAfterARestart() {
        server.file("stream", ByteArray(10) { 1 })
        val first = offline()
        first.keep(listOf(song("tr-1", "One")), true)
        until("kept") { first.localFile("tr-1") != null }
        val again = offline()
        until("read back") { again.localFile("tr-1") != null }
    }

    // A sign-in page answering in the server's place is never kept as a
    // song. Red when the HTML check before saving is removed.
    @Test
    fun aSignInPageInPlaceOfASongIsNeverKept() {
        server.respond("stream") { SignInPages.page() }
        val offline = offline()
        offline.keep(listOf(song("tr-1", "One")), true)
        until("tried") { offline.status.failed == 1 }
        assertNull(offline.localFile("tr-1"))
        assertTrue(File(folder.root, "kept").walk().none { it.isFile && it.name != DesktopOffline.INDEX })
    }

    // Authentik's 401 page in place of a song is never kept. Red when
    // the download keeps an answer whatever its status (the isSuccessful
    // check in fetch).
    @Test
    fun aSignInPagesRefusalInPlaceOfASongIsNeverKept() {
        server.respond("stream") { SignInPages.authentik() }
        val offline = offline()
        offline.keep(listOf(song("tr-1", "One")), true)
        until("tried") { offline.status.failed == 1 }
        assertNull(offline.localFile("tr-1"))
        assertTrue(File(folder.root, "kept").walk().none { it.isFile && it.name != DesktopOffline.INDEX })
    }

    // A sign-in page an older version kept in a song's place is dropped and
    // the song fetched again. Red when kept files are not looked at. The
    // older version has stopped before the page is put in the file, as an
    // older run of the app has: still running, its own look at the file
    // would fetch the song a second time.
    @Test
    fun aSignInPageKeptAsASongBeforeIsDroppedAndFetchedAgain() {
        val audio = ByteArray(1000) { 7 }
        server.file("stream", audio)
        val older = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = offline(older)
        first.keep(listOf(song("tr-1", "One")), true)
        until("kept") { first.localFile("tr-1") != null }
        runBlocking { older.coroutineContext.job.cancelAndJoin() }
        first.localFile("tr-1")!!.writeText("\n<!DOCTYPE html><html><body>Sign in</body></html>")
        server.answer("getSong", """"song":{"id":"tr-1","title":"One","artist":"Artist","album":"Album","track":1,"suffix":"flac"}""")
        val next = offline()
        // A refetch replaces the file, so it can go between the look and the read.
        until("fetched again") { runCatching { next.localFile("tr-1")?.readBytes() }.getOrNull()?.contentEquals(audio) == true }
        assertEquals(2, server.calls.count { it.url.pathSegments.last() == "stream" })
    }
}
