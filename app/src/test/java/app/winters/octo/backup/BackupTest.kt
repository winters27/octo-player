package app.winters.octo.backup

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.playback.CopyPreference
import app.winters.octo.playback.StreamQuality
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.player.StreamPrefs
import app.winters.octo.playlists.BYTE_ORDER_MARK
import app.winters.octo.sound.EqMode
import app.winters.octo.sound.SoundSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private fun track(id: String, title: String, artist: String, album: String = "Album", ms: Long = 200_000, relink: String = "") = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = artist, artistId = "a", album = album, albumId = "al", trackNo = null, discNo = null, year = null,
        durationMs = ms, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null, relinkKey = relink,
    )

    private val full = Backup(
        createdAt = 1_758_000_000_000,
        player = PlayerPrefs(crossfade = true, crossfadeSeconds = 4, speed = 1.25f, keepPitch = false, skipSilence = true, resumeBluetooth = true),
        streaming = StreamPrefs(CopyPreference.BestQuality, StreamQuality.Kbps320, StreamQuality.Kbps128),
        sound = SoundBackup(
            perOutput = true,
            profiles = mapOf(
                "all" to SoundSettings(),
                "bluetooth:AA:BB" to SoundSettings(eqEnabled = true, mode = EqMode.Parametric, preampDb = -3f),
            ),
        ),
        presets = listOf(PresetBackup("Mine", List(10) { it.toFloat() })),
        library = LibraryBackup(excludedFolders = listOf("Podcasts"), syncQueue = false, newPlaylistsOnServer = true),
        server = ServerBackup("https://music.example.com", "brandon"),
        playlists = listOf(PlaylistBackup("Road trip", listOf(SongKey("k1", "Nightcall", "Kavinsky", "OutRun", 258_000)))),
        likes = listOf(SongKey("k1", "Nightcall", "Kavinsky", "OutRun")),
        ratings = listOf(RatedSong(SongKey("k2", "Genesis", "Justice", "Cross"), 5)),
    )

    @Test
    fun aBackupReadsBackTheSame() {
        val text = encodeBackup(full)
        assertEquals(BackupRead.Read(full), decodeBackup(text))
        assertTrue(text.contains("\"version\": 1"))
        assertTrue(text.contains("\"kind\": \"octo-settings\""))
    }

    @Test
    fun everyPlayerSettingIsWritten() {
        val text = encodeBackup(Backup(player = PlayerPrefs()))
        listOf(
            "liveBackground", "crossfade", "crossfadeSeconds", "lyricsOnline", "speed", "keepPitch", "pitchSemitones",
            "skipSilence", "resumeWired", "resumeBluetooth", "resumeAlways",
        ).forEach { assertTrue("$it is missing", text.contains("\"$it\"")) }
    }

    @Test
    fun neverHoldsSecrets() {
        val text = encodeBackup(full).lowercase()
        listOf("password", "token", "apikey", "api_key", "secret", "header", "certificate", "alias").forEach {
            assertFalse("$it is in the backup", text.contains(it))
        }
    }

    @Test
    fun aServerAddressLosesAnythingSecretWrittenIntoIt() {
        assertEquals("https://music.example.com", plainAddress("https://me:hunter2@music.example.com/?t=abc&s=salt"))
        assertEquals("http://10.0.0.5:4533/music", plainAddress("http://10.0.0.5:4533/music/"))
        assertEquals("music.example.com", plainAddress("music.example.com?u=me&p=x"))
    }

    @Test
    fun otherFilesAreRefused() {
        assertEquals(BackupRead.NotABackup, decodeBackup("not json"))
        assertEquals(BackupRead.NotABackup, decodeBackup("{\"kind\": \"something-else\", \"version\": 1}"))
        assertEquals(BackupRead.TooNew, decodeBackup("{\"kind\": \"octo-settings\", \"version\": 2}"))
    }

    @Test
    fun anOlderOrSmallerBackupStillReads() {
        val read = decodeBackup(BYTE_ORDER_MARK + "{\"kind\": \"octo-settings\", \"version\": 1, \"player\": {\"crossfade\": true}, \"extra\": 5}")
        val backup = (read as BackupRead.Read).backup
        assertEquals(PlayerPrefs(crossfade = true), backup.player)
        assertEquals(null, backup.sound)
        assertTrue(backup.playlists.isEmpty())
    }

    @Test
    fun anUnknownChoiceFallsBackToItsDefault() {
        val read = decodeBackup("{\"kind\": \"octo-settings\", \"streaming\": {\"copies\": \"Nonsense\", \"wifi\": \"Kbps256\"}}")
        val streaming = (read as BackupRead.Read).backup.streaming
        assertEquals(StreamPrefs(wifi = StreamQuality.Kbps256), streaming)
    }

    @Test
    fun songsAreFoundByRelinkKeyFirst() {
        val library = listOf(track("a", "Renamed", "Someone", relink = "k1"), track("b", "Nightcall", "Kavinsky", "OutRun"))
        assertEquals("a", SongFinder(library).find(SongKey("k1", "Nightcall", "Kavinsky", "OutRun")))
    }

    @Test
    fun thenByTitleArtistAndAlbum() {
        val library = listOf(
            track("live", "Nightcall", "Kavinsky", "Live", ms = 300_000),
            track("studio", "Nightcall", "Kavinsky", "OutRun", ms = 258_000),
        )
        val finder = SongFinder(library)
        assertEquals("studio", finder.find(SongKey("gone", "Nightcall", "Kavinsky", "OutRun")))
        // Another album: the same song by the same artist, nearest in length.
        assertEquals("studio", finder.find(SongKey("", "Nightcall", "Kavinsky", "Best Of", 259_000)))
        // Someone else's song of the same name is not it.
        assertEquals(null, finder.find(SongKey("", "Nightcall", "London Grammar", "If You Wait")))
        assertEquals(null, finder.find(SongKey("", "", "", "")))
    }

    @Test
    fun restorePlanMatchesAndCounts() {
        val library = listOf(track("n", "Nightcall", "Kavinsky", "OutRun", relink = "k1"), track("g", "Genesis", "Justice", "Cross"))
        val backup = full.copy(
            playlists = listOf(
                PlaylistBackup("Road trip", listOf(SongKey("k1"), SongKey("", "Genesis", "Justice", "Cross"), SongKey("x", "Gone", "Nobody"))),
                PlaylistBackup("Old one", listOf(SongKey("k1"))),
            ),
            likes = listOf(SongKey("k1"), SongKey("k1"), SongKey("missing", "Missing", "Nobody")),
            ratings = listOf(RatedSong(SongKey("", "Genesis", "Justice", "Cross"), 4), RatedSong(SongKey("k1"), 9)),
        )
        val plan = planRestore(backup, library, existingPlaylists = listOf(" old ONE "))
        assertEquals(listOf(PlaylistPlan("Road trip", listOf("n", "g"), 3)), plan.playlists)
        assertEquals(listOf("Old one"), plan.skippedPlaylists)
        assertEquals(listOf("n"), plan.likes)
        assertEquals(3, plan.likesTotal)
        // A rating outside 1 to 5 is dropped.
        assertEquals(mapOf("g" to 4), plan.ratings)
        assertEquals(2, plan.ratingsTotal)
        assertEquals(
            listOf(
                "Playlists: 2 of 3 songs found",
                "Already here, left as they are: Old one",
                "Likes: 1 of 3 found",
                "Ratings: 1 of 2 found",
            ),
            describePlan(plan),
        )
    }

    @Test
    fun describesWhatABackupHolds() {
        assertEquals(
            listOf(
                "Settings: player, streaming, sound, library",
                "1 output with its own sound",
                "1 equalizer preset",
                "1 playlist, 1 song",
                "1 liked song",
                "1 rating",
                "Server: https://music.example.com as brandon. Sign in again to use it.",
            ),
            describeBackup(full),
        )
    }
}
