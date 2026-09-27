package app.winters.octo.lyrics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LyricsChoicesTest {
    // The bytes of the store's file, in the phone's own preferences format.
    private class SavedFile {
        var bytes: ByteArray? = null
    }

    // A store over the saved file that writes every change back in full,
    // as the phone's store does.
    private class FileBackedStore(private val file: SavedFile, start: Preferences) : DataStore<Preferences> {
        private val current = MutableStateFlow(start)
        private val writing = Mutex()
        override val data: Flow<Preferences> = current

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = writing.withLock {
            val next = transform(current.value)
            val out = ByteArrayOutputStream()
            PreferencesFileSerializer.writeTo(next, out)
            file.bytes = out.toByteArray()
            current.value = next
            next
        }
    }

    // Opening the store again on the same file stands in for the app starting again.
    private suspend fun open(file: SavedFile): LyricsChoices {
        val saved = file.bytes
        val start = if (saved == null) PreferencesFileSerializer.defaultValue else PreferencesFileSerializer.readFrom(ByteArrayInputStream(saved))
        return LyricsChoices(FileBackedStore(file, start))
    }

    @Test
    fun aSongNobodyChangedHasNoChoice() = runBlocking {
        assertEquals(LyricsChoice(), open(SavedFile()).current("lib:1"))
    }

    @Test
    fun aPickIsKeptPerSongAcrossARestart() = runBlocking {
        val file = SavedFile()
        val first = open(file)
        first.pick("lib:1", LyricsPick.Online(4242))
        first.pick("lib:2", LyricsPick.Own(LyricsSource.LyricsFile))

        val again = open(file)
        assertEquals(LyricsChoice(LyricsPick.Online(4242)), again.current("lib:1"))
        assertEquals(LyricsChoice(LyricsPick.Own(LyricsSource.LyricsFile)), again.current("lib:2"))
        assertEquals(LyricsChoice(), again.current("lib:3"))
    }

    @Test
    fun aNewPickReplacesTheLast() = runBlocking {
        val choices = open(SavedFile())
        choices.pick("lib:1", LyricsPick.Online(1))
        choices.pick("lib:1", LyricsPick.Own(LyricsSource.Server))
        assertEquals(LyricsPick.Own(LyricsSource.Server), choices.current("lib:1").pick)
    }

    @Test
    fun hidingLastsAcrossARestartAndShowingKeepsThePick() = runBlocking {
        val file = SavedFile()
        val first = open(file)
        first.pick("lib:1", LyricsPick.Online(7))
        first.hide("lib:1")
        first.hide("lib:2")

        val again = open(file)
        assertEquals(LyricsChoice(LyricsPick.Online(7), hidden = true), again.current("lib:1"))
        assertTrue(again.current("lib:2").hidden)

        again.show("lib:1")
        // Shown again, the song has the lyrics picked before it was hidden.
        assertEquals(LyricsChoice(LyricsPick.Online(7)), open(file).current("lib:1"))
        assertTrue(open(file).current("lib:2").hidden)
    }

    @Test
    fun pickingLyricsForAHiddenSongShowsItAgain() = runBlocking {
        val choices = open(SavedFile())
        choices.hide("lib:1")
        choices.pick("lib:1", LyricsPick.Own(LyricsSource.SongFile))
        assertEquals(LyricsChoice(LyricsPick.Own(LyricsSource.SongFile)), choices.current("lib:1"))
    }

    @Test
    fun theFlowFollowsOneSongOnly() = runBlocking {
        val choices = open(SavedFile())
        val watched = choices.choiceFor("lib:1")
        choices.pick("lib:2", LyricsPick.Online(2))
        assertEquals(LyricsChoice(), watched.first())
        choices.hide("lib:1")
        assertTrue(watched.first().hidden)
    }

    @Test
    fun picksReadBackAsWritten() {
        val picks = listOf(LyricsPick.Online(123), LyricsPick.Own(LyricsSource.Server), LyricsPick.Own(LyricsSource.SongFile), LyricsPick.Own(LyricsSource.LyricsFile))
        picks.forEach { assertEquals(it, decodePick(it.encoded())) }
        // Anything else is no pick at all.
        assertNull(decodePick(null))
        assertNull(decodePick("online:abc"))
        assertNull(decodePick("own:Online"))
        assertNull(decodePick("own:Radio"))
        assertNull(decodePick("something"))
    }

    @Test
    fun lyricsNameTheirOwnPick() {
        val words = listOf(LyricLine(text = "hi"))
        assertEquals(LyricsPick.Own(LyricsSource.Server), pickOf(Lyrics(false, words, LyricsSource.Server)))
        assertEquals(LyricsPick.Online(9), pickOf(Lyrics(false, words, LyricsSource.Online, onlineId = 9)))
        // Online lyrics saved before the number was kept cannot say.
        assertNull(pickOf(Lyrics(false, words, LyricsSource.Online)))
    }

    @Test
    fun aPickedAnswerStandsOnlyForItsPick() {
        val lyrics = Lyrics(true, listOf(LyricLine(startMs = 0, text = "hi")), LyricsSource.Online, onlineId = 5)
        val picked = CachedLyrics(savedAt = 0, lyrics = lyrics, askedOnline = true, lookupVersion = ONLINE_LOOKUP_VERSION, pick = "online:5")
        val usual = picked.copy(pick = null)
        assertTrue(picked.standsFor("online:5", now = 0, onlineAllowed = true))
        // Another pick, or none, asks again.
        assertFalse(picked.standsFor("online:6", now = 0, onlineAllowed = true))
        assertFalse(picked.standsFor(null, now = 0, onlineAllowed = true))
        // The usual answer does not stand for a pick.
        assertTrue(usual.standsFor(null, now = 0, onlineAllowed = true))
        assertFalse(usual.standsFor("online:5", now = 0, onlineAllowed = true))
        // A pick with nothing kept is fetched.
        assertFalse(picked.copy(lyrics = null).standsFor("online:5", now = 0, onlineAllowed = true))
    }
}
