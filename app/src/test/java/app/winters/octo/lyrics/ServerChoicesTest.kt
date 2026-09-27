package app.winters.octo.lyrics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import app.winters.octo.subsonic.LYRICS_AUTO
import app.winters.octo.subsonic.LYRICS_NONE
import app.winters.octo.subsonic.LyricsCandidates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

// Lyrics chosen on a server that keeps one choice for every app, beside
// the lyrics only this phone has, and who wins.
class ServerChoicesTest {
    // What an Octo server answers getLyricsCandidates with.
    private val listed = """
        {"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","lyricsCandidates":{"id":"song-1","choice":"auto","candidate":[
          {"id":"kugou:9f2a.77c1","source":"kugou","title":"Café","artist":"Octo Test","album":"Premier","duration":215,"kind":"word","sameSong":true,"chosen":false,"preview":["Café au lait","日本語の歌"]},
          {"id":"lrclib:4242","source":"lrclib","title":"Café (Live)","artist":"Octo Test","album":null,"duration":null,"kind":"line","sameSong":false,"chosen":false,"preview":["Café au lait"]},
          {"id":"netease:77","source":"netease","title":"Café","artist":"Octo Test","album":"Premier","duration":214,"kind":"plain","sameSong":true,"chosen":false,"preview":[]}]}}}
    """.trimIndent()

    private fun candidates(choice: String = LYRICS_AUTO): LyricsCandidates {
        val json = Json { ignoreUnknownKeys = true }
        val body = json.parseToJsonElement(listed).jsonObject.getValue("subsonic-response").jsonObject.getValue("lyricsCandidates")
        return json.decodeFromJsonElement(LyricsCandidates.serializer(), body).copy(choice = choice)
    }

    private fun timed(source: LyricsSource, text: String, serverPick: String? = null) =
        Lyrics(synced = true, lines = listOf(LyricLine(startMs = 1_000, text = text)), source = source, serverPick = serverPick)

    private val songFile = LyricsCandidate(LyricsPick.Own(LyricsSource.SongFile), CandidateOrigin.SongFile, timed(LyricsSource.SongFile, "tag"))
    private val lrcFile = LyricsCandidate(LyricsPick.Own(LyricsSource.LyricsFile), CandidateOrigin.LyricsFile, timed(LyricsSource.LyricsFile, "lrc"))
    private val automatic = timed(LyricsSource.Server, "found by the server")

    private val auto = LyricsPick.OnServer(LYRICS_AUTO)
    private val kugou = LyricsPick.OnServer("kugou:9f2a.77c1")
    private val lrclib = LyricsPick.OnServer("lrclib:4242")
    private val netease = LyricsPick.OnServer("netease:77")

    // A store kept in memory, as the phone's keeps it on disk.
    private class MemoryStore : DataStore<Preferences> {
        private val current = MutableStateFlow(PreferencesFileSerializer.defaultValue)
        override val data: Flow<Preferences> = current

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(current.value).also { current.value = it }
    }

    @Test
    fun wordTimingsAreAskedForWhenTheServerListsVersionTwo() {
        assertEquals(ServerLyricsCall.WithWords, serverLyricsCall(setOf("songLyrics:1", "songLyrics:2", "octoLyrics:1")))
        assertEquals(ServerLyricsCall.BySongId, serverLyricsCall(setOf("songLyrics:1")))
        assertEquals(ServerLyricsCall.ByName, serverLyricsCall(setOf("formPost:1")))
    }

    @Test
    fun onlyAServerListingTheExtensionKeepsChoices() {
        assertTrue(offersLyricsChoices(setOf("songLyrics:2", "octoLyrics:1")))
        assertFalse(offersLyricsChoices(setOf("songLyrics:2", "octoAcquisitions:1")))
    }

    @Test
    fun theServerCopiesComeWithWhatTheyAre() {
        val found = serverCandidates(candidates(), automatic, listOf(songFile, lrcFile))
        assertEquals(listOf(auto, kugou, lrclib, netease, songFile.pick, lrcFile.pick), found.map { it.pick })

        val first = found[1]
        assertEquals("From KuGou", candidateLabel(first))
        assertEquals(LyricsKind.Words, first.kind)
        assertEquals("Word by word", first.kind.label)
        assertEquals("Premier", first.album)
        assertEquals(215_000L, first.durationMs)
        assertEquals(listOf("Café au lait", "日本語の歌"), first.preview)
        assertEquals("From LRCLIB", candidateLabel(found[2]))
        assertEquals(LyricsKind.Lines, found[2].kind)
        assertEquals("", found[2].album)
        assertEquals(0L, found[2].durationMs)
        assertEquals("From NetEase", candidateLabel(found[3]))
        assertEquals(LyricsKind.Plain, found[3].kind)
        assertEquals("Automatic", candidateLabel(found[0]))
        assertEquals("From the song file", candidateLabel(found[4]))
    }

    @Test
    fun automaticCarriesTheServersLyricsOnlyWhileItFindsThemItself() {
        assertEquals(automatic, serverCandidates(candidates(LYRICS_AUTO), automatic, emptyList()).first().lyrics)
        assertNull(serverCandidates(candidates("kugou:9f2a.77c1"), automatic, emptyList()).first().lyrics)
        assertNull(serverCandidates(candidates(LYRICS_NONE), null, emptyList()).first().lyrics)
    }

    @Test
    fun anUnreachableServerLeavesTheLocalLyrics() {
        assertEquals(listOf(songFile.pick), serverCandidates(null, null, listOf(songFile)).map { it.pick })
    }

    @Test
    fun copiesWithTheSameFirstLinesAreAllOffered() {
        // KuGou and LRCLIB often hold the same words; only the server can tell them apart.
        val same = candidates().let { it.copy(candidate = it.candidate.map { copy -> copy.copy(preview = listOf("Same")) }) }
        val list = candidateList(serverCandidates(same, automatic, emptyList()), current = auto, showing = automatic)
        assertEquals(listOf(auto, kugou, lrclib, netease), list.items.map { it.pick })
    }

    @Test
    fun thePinnedCopyIsShowingAndComesFirst() {
        val showing = timed(LyricsSource.Server, "pinned words", serverPick = "lrclib:4242")
        val current = showingPick("lrclib:4242", showing)
        assertEquals(lrclib, current)
        val list = candidateList(serverCandidates(candidates("lrclib:4242"), null, listOf(songFile)), current, showing)
        assertEquals(listOf(lrclib, auto, kugou, netease, songFile.pick), list.items.map { it.pick })
        assertEquals(0, list.showing)
    }

    @Test
    fun theServersOwnFindIsAutomatic() {
        val list = candidateList(serverCandidates(candidates(), automatic, listOf(songFile)), showingPick(LYRICS_AUTO, automatic), automatic)
        assertEquals(auto, list.items[list.showing].pick)
    }

    @Test
    fun aPickOnThisPhoneWinsOverTheServersChoice() {
        // The phone picked its song file's lyrics; the server has a copy pinned.
        val showing = songFile.lyrics
        val current = showingPick("kugou:9f2a.77c1", showing)
        assertEquals(songFile.pick, current)
        val list = candidateList(serverCandidates(candidates("kugou:9f2a.77c1"), null, listOf(songFile, lrcFile)), current, showing)
        assertEquals(songFile.pick, list.items.first().pick)
        assertEquals(0, list.showing)
    }

    @Test
    fun anOnlinePickMadeBeforeTheServerOfferedChoicesStillShows() {
        val picked = Lyrics(true, listOf(LyricLine(0, text = "picked")), LyricsSource.Online, onlineId = 99)
        val list = candidateList(serverCandidates(candidates(), automatic, emptyList()), showingPick(LYRICS_AUTO, picked), picked)
        assertEquals(LyricsPick.Online(99), list.items.first().pick)
        assertEquals(0, list.showing)
    }

    @Test
    fun withoutTheExtensionNothingChanges() {
        val server = LyricsCandidate(LyricsPick.Own(LyricsSource.Server), CandidateOrigin.Server, automatic)
        assertEquals(LyricsPick.Own(LyricsSource.Server), showingPick(null, automatic))
        val list = candidateList(listOf(songFile, server), showingPick(null, automatic), automatic)
        assertEquals(listOf(server.pick, songFile.pick), list.items.map { it.pick })
        assertEquals("From your server", candidateLabel(server))
    }

    @Test
    fun aServerPinClearsThisPhonesPickAndHideAndForgetsTheSavedLyrics() = runTest {
        val choices = LyricsChoices(MemoryStore())
        choices.pick("s", LyricsPick.Own(LyricsSource.SongFile))
        choices.hide("s")
        val sent = mutableListOf<String>()
        var forgotten = 0
        sendServerChoice("s", "kugou:1", choices, send = { sent += it }, forget = { forgotten++ })
        assertEquals(listOf("kugou:1"), sent)
        assertEquals(1, forgotten)
        assertEquals(LyricsChoice(), choices.current("s"))
    }

    @Test
    fun automaticClearsThisPhonesPickToo() = runTest {
        val choices = LyricsChoices(MemoryStore())
        choices.pick("s", LyricsPick.Online(4))
        sendServerChoice("s", LYRICS_AUTO, choices, send = {}, forget = {})
        assertEquals(LyricsChoice(), choices.current("s"))
    }

    @Test
    fun hidingOnTheServerHidesHereAndKeepsThePickForLater() = runTest {
        val choices = LyricsChoices(MemoryStore())
        choices.pick("s", LyricsPick.Own(LyricsSource.LyricsFile))
        sendServerChoice("s", LYRICS_NONE, choices, send = {}, forget = {})
        assertEquals(LyricsChoice(LyricsPick.Own(LyricsSource.LyricsFile), hidden = true), choices.current("s"))
        sendServerChoice("s", LYRICS_AUTO, choices, send = {}, forget = {}, showAgain = true)
        assertEquals(LyricsChoice(LyricsPick.Own(LyricsSource.LyricsFile), hidden = false), choices.current("s"))
    }

    @Test
    fun aChoiceTheServerRefusesChangesNothingHere() = runTest {
        val choices = LyricsChoices(MemoryStore())
        choices.pick("s", LyricsPick.Online(4))
        var forgotten = false
        try {
            sendServerChoice("s", "kugou:1", choices, send = { throw IOException("down") }, forget = { forgotten = true })
            fail("expected the server's refusal")
        } catch (_: IOException) {
        }
        assertFalse(forgotten)
        assertEquals(LyricsChoice(LyricsPick.Online(4)), choices.current("s"))
    }

    @Test
    fun aSavedAnswerIsStaleWhenTheServersChoiceMoved() {
        val now = 1_000L
        fun saved(lyrics: Lyrics?, pick: String? = null) = CachedLyrics(now, lyrics, askedOnline = true, lookupVersion = ONLINE_LOOKUP_VERSION, pick = pick)
        val serverAuto = saved(timed(LyricsSource.Server, "auto"))
        val serverPinned = saved(timed(LyricsSource.Server, "pinned", serverPick = "kugou:1"))
        // Nothing saved, nothing to refresh.
        assertFalse(servedStale(null, "kugou:1"))
        // The same choice stands.
        assertFalse(servedStale(serverAuto, LYRICS_AUTO))
        assertFalse(servedStale(serverPinned, "kugou:1"))
        // Pinned, changed or unpinned elsewhere.
        assertTrue(servedStale(serverAuto, "kugou:1"))
        assertTrue(servedStale(serverPinned, "lrclib:2"))
        assertTrue(servedStale(serverPinned, LYRICS_AUTO))
        assertTrue(servedStale(serverAuto, LYRICS_NONE))
        // "None found" or the song file's, and now the server has a pin.
        assertTrue(servedStale(saved(null), "kugou:1"))
        assertTrue(servedStale(saved(timed(LyricsSource.SongFile, "tag")), "kugou:1"))
        assertFalse(servedStale(saved(timed(LyricsSource.SongFile, "tag")), LYRICS_AUTO))
        // Lyrics this phone picked stand whatever the server chose.
        assertFalse(servedStale(saved(timed(LyricsSource.SongFile, "tag"), pick = "own:SongFile"), "kugou:1"))
    }

    @Test
    fun droppingASongsAnswerLooksItUpAgain() {
        val folder = Files.createTempDirectory("lyrics").toFile()
        try {
            val cache = LyricsCache(folder)
            val entry = CachedLyrics(1, timed(LyricsSource.Server, "pinned", serverPick = "kugou:1"), askedOnline = true)
            cache.write("s", entry)
            cache.write("other", entry)
            assertEquals("kugou:1", cache.read("s")?.lyrics?.serverPick)
            cache.drop("s")
            assertNull(cache.read("s"))
            assertEquals(entry, cache.read("other"))
            // Dropping one that is not there is fine.
            cache.drop("never")
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun theServersAnswerIsFinalWhenItKeepsChoices() = runBlocking {
        val asked = mutableListOf<LyricsSource>()
        fun steps(server: suspend () -> Lyrics?, decides: Boolean) = lyricsSteps(
            server = { asked += LyricsSource.Server; server() },
            serverDecides = decides,
            songFile = { asked += LyricsSource.SongFile; timed(LyricsSource.SongFile, "tag") },
            lyricsFile = null,
            online = { asked += LyricsSource.Online; timed(LyricsSource.Online, "online") },
        )
        val plain = Lyrics(false, listOf(LyricLine(text = "plain")), LyricsSource.Server)

        // Plain lyrics the server chose are used as they are.
        assertEquals(plain, searchInOrder(steps({ plain }, decides = true)).lyrics)
        assertEquals(listOf(LyricsSource.Server), asked)

        // Nothing from the server (hidden there, or none found): the song
        // file is still read, but the online library is not asked directly.
        asked.clear()
        assertEquals(LyricsSource.SongFile, searchInOrder(steps({ null }, decides = true)).lyrics?.source)
        assertEquals(listOf(LyricsSource.Server, LyricsSource.SongFile), asked)
        asked.clear()
        val none = searchInOrder(
            lyricsSteps({ null }, serverDecides = true, songFile = null, lyricsFile = null, online = { asked += LyricsSource.Online; null }),
        )
        assertNull(none.lyrics)
        assertTrue(asked.isEmpty())

        // The server could not be reached: the online library stands in.
        asked.clear()
        val search = searchInOrder(
            lyricsSteps({ throw IOException("down") }, serverDecides = true, songFile = null, lyricsFile = null, online = {
                asked += LyricsSource.Online
                timed(LyricsSource.Online, "online")
            }),
        )
        assertEquals(LyricsSource.Online, search.lyrics?.source)
        assertEquals(listOf(LyricsSource.Online), asked)
        assertFalse(search.complete)

        // A server without the extension: plain lyrics wait for synced ones, as before.
        asked.clear()
        assertEquals(LyricsSource.SongFile, searchInOrder(steps({ plain }, decides = false)).lyrics?.source)
    }

    @Test
    fun theMenuSaysWhereLyricsAreFrom() {
        val worded = Lyrics(
            synced = true,
            lines = listOf(LyricLine(startMs = 0, text = "Café", words = listOf(LyricWord(0, 500, "Café", 0, 4)))),
            source = LyricsSource.Server,
        )
        assertEquals("From KuGou, word by word", worded.copy(serverPick = "kugou:9f2a.77c1").sourceLine())
        assertEquals("From your server, word by word", worded.sourceLine())
        assertEquals("From LRCLIB", timed(LyricsSource.Online, "x").sourceLine())
        assertEquals("From LRCLIB", timed(LyricsSource.Server, "x", serverPick = "lrclib:4242").sourceLine())
        assertEquals("From your server", timed(LyricsSource.Server, "x").sourceLine())
        assertEquals("From the song file", timed(LyricsSource.SongFile, "x").sourceLine())
    }

    @Test
    fun aSearchByHandIsATitleOrArtistAndTitle() {
        assertEquals("Café" to "Octo Test", splitLyricsSearch(" Octo Test - Café "))
        assertEquals("Stand by Me" to null, splitLyricsSearch("Stand by Me"))
        assertEquals("- Café" to null, splitLyricsSearch("- Café"))
    }

    @Test
    fun aServerChoiceIsNeverMistakenForAnotherPick() {
        assertEquals(kugou, decodePick(kugou.encoded()))
        assertEquals(auto, decodePick(auto.encoded()))
        assertNull(decodePick("server:"))
    }
}
