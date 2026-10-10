package app.winters.octo.ui.settings

import app.winters.octo.ambient.AmbientPrefs
import app.winters.octo.ambient.AmbientStrength
import app.winters.octo.connection.Place
import app.winters.octo.device.Access
import app.winters.octo.device.MusicFolder
import app.winters.octo.listening.ListenBrainzPrefs
import app.winters.octo.offline.CacheSize
import app.winters.octo.offline.OfflinePrefs
import app.winters.octo.playback.StreamQuality
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.player.StreamPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSummariesTest {
    private val minute = 60_000L
    private val now = 1_758_000_000_000L

    @Test
    fun playback() {
        assertEquals("Gapless · Autoplay", playbackSummary(PlayerPrefs()))
        assertEquals("Crossfade 8 s · Autoplay", playbackSummary(PlayerPrefs(crossfade = true)))
        assertEquals(
            "Crossfade 4 s · 1.25x · Skip silence",
            playbackSummary(PlayerPrefs(crossfade = true, crossfadeSeconds = 4, speed = 1.25f, autoplay = false, skipSilence = true)),
        )
        // Never more than three parts.
        assertEquals(
            "Gapless · 2x · Autoplay",
            playbackSummary(PlayerPrefs(speed = 2f, skipSilence = true)),
        )
    }

    @Test
    fun appearance() {
        assertEquals("Subtle glow · Live background", appearanceSummary(AmbientPrefs(), liveBackground = true))
        assertEquals("No glow", appearanceSummary(AmbientPrefs(strength = AmbientStrength.Off), liveBackground = false))
        assertEquals("Rich glow", appearanceSummary(AmbientPrefs(strength = AmbientStrength.Rich), liveBackground = false))
    }

    @Test
    fun library() {
        val folders = listOf(MusicFolder("Music", 2_000, true), MusicFolder("Podcasts", 40, false))
        assertEquals("2,357 songs · 1 folder left out", librarySummary(Access.Granted, 2_357, folders))
        assertEquals("1 song", librarySummary(Access.Granted, 1, emptyList()))
        assertEquals("Music on this phone not allowed yet", librarySummary(Access.Denied, 0, folders))
        assertEquals("2,357 songs on this phone", phoneLine(Access.Granted, 2_357))
        assertEquals("Allow access to the music on this phone", phoneLine(Access.NotAsked, 0))
    }

    @Test
    fun howLongAgo() {
        assertEquals("Synced just now", syncedAgo(now - 20_000, now))
        assertEquals("Synced 5 min ago", syncedAgo(now - 5 * minute, now))
        assertEquals("Synced 3 hr ago", syncedAgo(now - 3 * 60 * minute, now))
        assertEquals("Synced yesterday", syncedAgo(now - 30 * 60 * minute, now))
        assertEquals("Synced 4 days ago", syncedAgo(now - 4 * 24 * 60 * minute, now))
        // A clock that moved back is not "in the future".
        assertEquals("Just now", timeAgo(now + minute, now))
    }

    @Test
    fun server() {
        assertEquals("Syncing…", serverStatus(syncing = true, place = Place.Home, syncedAt = now, now = now))
        assertEquals("Connected at home · Synced 5 min ago", serverStatus(false, Place.Home, now - 5 * minute, now))
        assertEquals("Connected away · Not synced yet", serverStatus(false, Place.Away, null, now))
        assertEquals("Synced just now", serverStatus(false, null, now, now))
    }

    @Test
    fun streaming() {
        assertEquals("Wi-Fi Original · mobile Original · 2 GB cache", streamingSummary(StreamPrefs(), OfflinePrefs()))
        assertEquals(
            "Wi-Fi 320 kbps · mobile 128 kbps · no cache",
            streamingSummary(StreamPrefs(wifi = StreamQuality.Kbps320, mobile = StreamQuality.Kbps128), OfflinePrefs(cacheSize = CacheSize.Off)),
        )
    }

    @Test
    fun lyrics() {
        assertEquals("Found online · Screen stays on", lyricsSummary(online = true, keepScreenOn = true))
        assertEquals("From your music only", lyricsSummary(online = false, keepScreenOn = false))
    }

    @Test
    fun scrobbling() {
        assertEquals("Off", scrobblingSummary(ListenBrainzPrefs()))
        assertEquals("Not connected yet", scrobblingSummary(ListenBrainzPrefs(enabled = true)))
        assertEquals("Needs a new token", scrobblingSummary(ListenBrainzPrefs(enabled = true, user = "me", needsAttention = true)))
        assertEquals("ListenBrainz as me", scrobblingSummary(ListenBrainzPrefs(enabled = true, user = "me")))
    }

    @Test
    fun aboutAndBackup() {
        assertEquals("Version 1.2", aboutSummary("1.2"))
        assertTrue(BACKUP_SUMMARY.isNotBlank())
    }

    @Test
    fun linesUseTheMiddleDotAndNoDashes() {
        val lines = listOf(
            playbackSummary(PlayerPrefs(crossfade = true, speed = 1.5f)),
            streamingSummary(StreamPrefs(), OfflinePrefs()),
            serverStatus(false, Place.Home, now, now),
        )
        lines.forEach { line ->
            assertTrue(line, " · " in line)
            assertTrue(line, Char(0x2014) !in line && Char(0x2013) !in line)
        }
    }
}
