package app.winters.octo.desktop.discord

import app.winters.octo.desktop.system.NowPlaying
import app.winters.octo.desktop.system.openedFileId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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
        assertEquals("Karma Police", shown.details)
        assertEquals("Radiohead", shown.state)
        assertEquals("OK Computer", shown.largeText)
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
        assertEquals("demo", allowed.details)
        assertNull("no artist, no line for one", allowed.state)
        assertEquals("no album: the icon's own name", "Octo", allowed.largeText)
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
        val shown = DiscordActivity(details = "Karma Police", state = "Radiohead", startMs = 1_000, endMs = 265_000, largeText = "OK Computer")
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
        val a = DiscordActivity(details = "A", state = "B", startMs = 10_000, endMs = 200_000, largeText = "C")
        assertTrue(a.looksLike(a.copy(startMs = 11_000, endMs = 201_000)))
        assertFalse(a.looksLike(a.copy(startMs = 30_000, endMs = 220_000)))
        assertFalse(a.looksLike(a.copy(details = "D")))
        assertFalse(a.looksLike(null))
    }

    @Test
    fun templatesFillTheLines() {
        val shown = discordActivityFor(now(), 0, clock, on.copy(firstLine = "{title} ({album})", secondLine = "  "))!!
        assertEquals("Karma Police (OK Computer)", shown.details)
        assertNull("a blank second line shows none", shown.state)
        val blank = discordActivityFor(now(), 0, clock, on.copy(firstLine = ""))!!
        assertEquals("a blank first line falls back to the title", "Karma Police", blank.details)
    }

    // Brandon: songs whose album is their own title need no album in Discord.
    @Test
    fun anAlbumThatOnlyRepeatsTheTitleIsLeftOut() {
        val single = discordActivityFor(now(title = "Creep", album = "Creep - Single"), 0, clock, on, DiscordArtwork(cover = "https://covers.example/creep.jpg"))!!
        assertEquals("Creep", single.details)
        assertNull("no album under the cover", single.largeText)
        listOf("Creep", "creep", "CREEP!", "Creep (Single)", "Creep [EP]", "Creep - EP", "Creep: Single").forEach { album ->
            assertEquals(album, "", discordAlbum("Creep", album))
        }
        assertEquals("Don't Stop Me Now", "", discordAlbum("Don't Stop Me Now", "Dont Stop Me Now"))
        assertEquals("another album keeps its name", "Pablo Honey", discordAlbum("Creep", "Pablo Honey"))
        assertEquals("a title inside a longer album name keeps it", "Creep (Live at Glastonbury)", discordAlbum("Creep", "Creep (Live at Glastonbury)"))
        assertEquals("a title of only marks keeps its album", "Hits", discordAlbum("...", "Hits"))
        assertEquals("", discordAlbum("Creep", ""))
        // With the icon in place of a cover, the icon's own name still shows.
        val iconOnly = discordActivityFor(now(title = "Creep", album = "Creep"), 0, clock, on.copy(picture = DiscordPicture.Icon))!!
        assertEquals("Octo", iconOnly.largeText)
    }

    @Test
    fun aTemplateLeavesNoTraceOfAnAlbumLeftOut() {
        val brackets = discordActivityFor(now(title = "Creep", album = "Creep"), 0, clock, on.copy(firstLine = "{title} ({album})", secondLine = "{artist} - {album}"))!!
        assertEquals("Creep", brackets.details)
        assertEquals("Radiohead", brackets.state)
        val kept = discordActivityFor(now(), 0, clock, on.copy(secondLine = "{artist} - {album}"))!!
        assertEquals("Radiohead - OK Computer", kept.state)
        val dashTitle = discordActivityFor(now(title = "Song -"), 0, clock, on)!!
        assertEquals("a title is left as it is when nothing was taken out", "Song -", dashTitle.details)
    }

    @Test
    fun timeShowsWhatIsLeftWhatWasPlayedOrNothing() {
        val left = discordActivityFor(now(), 64_000, clock, on)!!
        assertEquals(clock - 64_000, left.startMs)
        assertEquals(clock + 200_000, left.endMs)
        val played = discordActivityFor(now(), 64_000, clock, on.copy(time = DiscordTime.Elapsed))!!
        assertEquals(clock - 64_000, played.startMs)
        assertNull(played.endMs)
        val none = discordActivityFor(now(), 64_000, clock, on.copy(time = DiscordTime.Off))!!
        assertNull(none.startMs)
        assertNull(none.endMs)
        assertNull(none.toJson()["timestamps"])
    }

    @Test
    fun pausedClearsUnlessKeptThenShowsAPauseBadgeAndNoTime() {
        assertNull(discordActivityFor(now(playing = false), 1_000, clock, on))
        val kept = discordActivityFor(now(playing = false), 1_000, clock, on.copy(whilePaused = true))!!
        assertEquals(DISCORD_PAUSE, kept.smallImage)
        assertEquals("Paused", kept.smallText)
        assertNull(kept.startMs)
        assertNull(kept.endMs)
    }

    @Test
    fun theCoverIsLargeAndTheArtistIsTheBadge() {
        val art = DiscordArtwork("https://is1.example/1024x1024bb.jpg", "https://e-cdns.example/p.jpg")
        val shown = discordActivityFor(now(), 0, clock, on, art)!!
        assertEquals(art.cover, shown.largeImage)
        assertEquals("OK Computer", shown.largeText)
        assertEquals(art.artist, shown.smallImage)
        assertEquals("Radiohead", shown.smallText)
        val bare = discordActivityFor(now(), 0, clock, on)!!
        assertEquals(DISCORD_ICON, bare.largeImage)
        assertEquals("OK Computer", bare.largeText)
        assertNull("no photo found, no badge", bare.smallImage)
        assertNull(bare.smallText)
        val icon = discordActivityFor(now(), 0, clock, on.copy(picture = DiscordPicture.Icon, badge = DiscordBadge.Off), art)!!
        assertEquals(DISCORD_ICON, icon.largeImage)
        assertNull(icon.smallImage)
    }

    @Test
    fun theSongAndArtistLinkToLastFm() {
        val shown = discordActivityFor(now(title = "Instant Crush", artist = "Daft Punk"), 0, clock, on)!!
        assertEquals("https://www.last.fm/music/Daft+Punk/_/Instant+Crush", shown.detailsUrl)
        assertEquals("https://www.last.fm/music/Daft+Punk", shown.stateUrl)
        assertEquals(DiscordButton("Open on Last.fm", "https://www.last.fm/music/Daft+Punk/_/Instant+Crush"), shown.button)
        val off = discordActivityFor(now(), 0, clock, on.copy(lastFmLinks = false))!!
        assertNull(off.detailsUrl)
        assertNull(off.stateUrl)
        assertNull(off.button)
    }

    @Test
    fun theJsonNamesWhatTheListShowsAndCarriesTheButton() {
        fun shows(name: DiscordListName) =
            discordActivityFor(now(), 0, clock, on.copy(listName = name))!!.toJson()["status_display_type"]!!.jsonPrimitive.int
        assertEquals(2, shows(DiscordListName.Song))
        assertEquals(1, shows(DiscordListName.Artist))
        assertEquals(0, shows(DiscordListName.App))
        val json = discordActivityFor(now(), 0, clock, on)!!.toJson()
        val button = json["buttons"]!!.jsonArray.single().jsonObject
        assertEquals("Open on Last.fm", button["label"]!!.jsonPrimitive.content)
        assertTrue(button["url"]!!.jsonPrimitive.content.startsWith("https://www.last.fm/music/"))
        assertTrue(json.containsKey("details_url"))
    }

    @Test
    fun nothingFromTheServerEverGoesOut() {
        val text = discordActivityFor(now(), 0, clock, on)!!.toJson().toString()
        assertFalse(text.contains("rest/"))
        assertFalse(text.contains("getCoverArt"))
        assertFalse(text.contains("al-1"))
    }

    @Test
    fun aDifferentPictureIsADifferentStatus() {
        val a = DiscordActivity(details = "A", state = "B", startMs = 10_000, endMs = 200_000)
        assertFalse(a.looksLike(a.copy(largeImage = "https://is1.example/cover.jpg")))
        assertFalse(a.looksLike(a.copy(smallImage = "https://e-cdns.example/p.jpg")))
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
