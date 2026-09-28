package app.winters.octo.desktop.discord

import app.winters.octo.desktop.system.NowPlaying
import app.winters.octo.desktop.system.openedFileId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordActivityTest {
    private val on = DiscordPrefs(on = true)
    private val clock = 1_800_000_000_000L

    private fun now(
        id: String = "s1",
        title: String = "Karma Police",
        artist: String = "Radiohead",
        album: String = "OK Computer",
        durationMs: Long = 264_000,
        playing: Boolean = true,
        speed: Float = 1f,
    ) = NowPlaying(
        entryKey = 1,
        songId = id,
        title = title,
        artist = artist,
        album = album,
        albumArtist = artist,
        durationMs = durationMs,
        coverId = "al-1",
        trackNumber = 6,
        discNumber = 1,
        genres = emptyList(),
        playing = playing,
        canPrevious = true,
        canNext = true,
        speed = speed,
    )

    @Test
    fun aPlayingSongShowsItsWordsAndWhenItEnds() {
        val shown = discordActivityFor(now(), positionMs = 64_000, clockMs = clock, prefs = on)!!
        assertEquals("Karma Police", shown.song)
        assertEquals("Radiohead", shown.artist)
        assertEquals("OK Computer", shown.album)
        assertEquals("started 64 s ago", clock - 64_000, shown.startMs)
        assertEquals("ends in 200 s", clock + 200_000, shown.endMs)
    }

    @Test
    fun nothingShowsWhilePausedStoppedOrOff() {
        assertNull("paused", discordActivityFor(now(playing = false), 1_000, clock, on))
        assertNull("nothing in", discordActivityFor(null, 0, clock, on))
        assertNull("off", discordActivityFor(now(), 1_000, clock, DiscordPrefs(on = false)))
    }

    @Test
    fun aFileOpenedFromThisComputerStaysPrivateUnlessAllowed() {
        val file = now(id = openedFileId("file:///C:/Users/b/Music/demo.flac", "demo"), title = "demo", artist = "", album = "")
        assertNull(discordActivityFor(file, 1_000, clock, on))
        val allowed = discordActivityFor(file, 1_000, clock, DiscordPrefs(on = true, openedFiles = true))!!
        assertEquals("demo", allowed.song)
        assertNull("no artist, no line for one", allowed.artist)
        assertNull(allowed.album)
    }

    @Test
    fun theTimesFollowThePlayingSpeed() {
        // Half way through a 200 s song at double speed: 50 s played in
        // real time, 50 s to go.
        val shown = discordActivityFor(now(durationMs = 200_000, speed = 2f), 100_000, clock, on)!!
        assertEquals(clock - 50_000, shown.startMs)
        assertEquals(clock + 50_000, shown.endMs)
    }

    @Test
    fun anUnknownLengthShowsOnlyTheTimePlayed() {
        val shown = discordActivityFor(now(durationMs = 0), 30_000, clock, on)!!
        assertEquals(clock - 30_000, shown.startMs)
        assertNull(shown.endMs)
    }

    @Test
    fun aPlaceBeyondTheEndStopsAtTheEnd() {
        val shown = discordActivityFor(now(durationMs = 10_000), 99_000, clock, on)!!
        assertEquals(clock - 10_000, shown.startMs)
        assertEquals(clock, shown.endMs)
    }

    @Test
    fun wordsAreMadeToFitDiscord() {
        assertNull(discordText("   "))
        assertEquals("one character is padded to two", "X\u2800", discordText("X"))
        val long = "a".repeat(300)
        val cut = discordText(long)!!
        assertEquals(128, cut.length)
        assertTrue(cut.endsWith("…"))
        // A character made of two halves is never split at the cut.
        val emoji = "a".repeat(126) + "\uD83C\uDFB5" + "tail"
        val safe = discordText(emoji)!!
        assertFalse(Character.isHighSurrogate(safe[safe.length - 2]))
    }

    @Test
    fun theCommandIsWhatDiscordReads() {
        val shown = DiscordActivity("Karma Police", "Radiohead", "OK Computer", 1_000, 265_000)
        val command = setActivityCommand(shown, pid = 42, nonce = "n1")
        assertEquals("SET_ACTIVITY", command["cmd"]!!.jsonPrimitive.content)
        val args = command["args"]!!.jsonObject
        assertEquals(42L, args["pid"]!!.jsonPrimitive.long)
        val activity = args["activity"]!!.jsonObject
        assertEquals("listening", 2, activity["type"]!!.jsonPrimitive.int)
        assertEquals("the song names the status", 2, activity["status_display_type"]!!.jsonPrimitive.int)
        assertEquals("Karma Police", activity["details"]!!.jsonPrimitive.content)
        assertEquals("Radiohead", activity["state"]!!.jsonPrimitive.content)
        assertEquals(1_000L, activity["timestamps"]!!.jsonObject["start"]!!.jsonPrimitive.long)
        assertEquals(265_000L, activity["timestamps"]!!.jsonObject["end"]!!.jsonPrimitive.long)
        val assets = activity["assets"]!!.jsonObject
        assertEquals("Octo's icon, never a server's cover", DISCORD_ICON, assets["large_image"]!!.jsonPrimitive.content)
        assertEquals("OK Computer", assets["large_text"]!!.jsonPrimitive.content)
        // Nothing from the server goes out: no address, no cover, no id.
        val text = command.toString()
        assertFalse(text.contains("http"))
        assertFalse(text.contains("al-1"))
        assertEquals("clearing sends no activity", JsonNull, setActivityCommand(null, 42, "n2")["args"]!!.jsonObject["activity"])
    }

    @Test
    fun aMomentOfDriftIsTheSameButASeekIsNot() {
        val a = DiscordActivity("A", "B", "C", 10_000, 200_000)
        assertTrue(a.looksLike(a.copy(startMs = 11_000, endMs = 201_000)))
        assertFalse(a.looksLike(a.copy(startMs = 30_000, endMs = 220_000)))
        assertFalse(a.looksLike(a.copy(song = "D")))
        assertFalse(a.looksLike(null))
    }

    @Test
    fun theWaitGrowsToAMinuteAndStartsOverOnSuccess() {
        val backoff = Backoff()
        val waits = List(8) { backoff.next() }
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L), waits)
        backoff.reset()
        assertEquals(2_000L, backoff.next())
    }

    @Test
    fun onlyARealApplicationIdIsTaken() {
        assertEquals("1234567890123456789", discordAppId(" 1234567890123456789 "))
        assertNull(discordAppId(null))
        assertNull(discordAppId(""))
        assertNull(discordAppId("paste-the-id-here"))
        assertNull(discordAppId("123"))
    }
}
