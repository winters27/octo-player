package app.winters.octo.desktop.system

// What each system's media controls are handed for the song playing. These
// are plain values, worked out here so they can be tested anywhere; the
// system-specific code only passes them on.

// Whether anything plays, in the system library's numbers.
enum class NativeStatus(val code: Int) { Stopped(1), Playing(2), Paused(3) }

// The song for the system library, which shows it in the Windows media
// controls (the flyout, the lock screen) and in macOS's Now Playing.
data class NativeTrack(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val durationMs: Long,
)

// Where the song is, and which buttons work.
data class NativePlayback(
    val status: NativeStatus,
    val positionMs: Long,
    val canPrevious: Boolean,
    val canNext: Boolean,
)

fun nativeTrackOf(now: NowPlaying) = NativeTrack(
    title = now.title,
    artist = now.artist,
    album = now.album,
    albumArtist = now.albumArtist,
    durationMs = now.durationMs.coerceAtLeast(0),
)

fun nativePlaybackOf(now: NowPlaying?, positionMs: Long) = NativePlayback(
    status = when {
        now == null -> NativeStatus.Stopped
        now.playing -> NativeStatus.Playing
        else -> NativeStatus.Paused
    },
    positionMs = clampPosition(positionMs, now?.durationMs ?: 0),
    canPrevious = now?.canPrevious == true,
    canNext = now?.canNext == true,
)

// The library converts for each system: to ticks of 100 nanoseconds on
// Windows, and on macOS to seconds with a rate of 1 while playing and 0
// while paused, from which Now Playing moves the time on by itself.

// Linux: the MPRIS player's properties. Times are in microseconds there.
object Mpris {
    const val BUS_NAME = "org.mpris.MediaPlayer2.octo"
    const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
    const val ROOT = "org.mpris.MediaPlayer2"
    const val PLAYER = "org.mpris.MediaPlayer2.Player"

    // What a track id is when there is none, as the specification says.
    const val NO_TRACK = "/org/mpris/MediaPlayer2/TrackList/NoTrack"

    // A D-Bus object path for a queue entry. Paths allow only letters,
    // digits and "_" in each part, and the entry key is a plain number.
    fun trackPath(now: NowPlaying): String = "/app/winters/octo/track/e${now.entryKey}"

    fun status(now: NowPlaying?): String = when {
        now == null -> "Stopped"
        now.playing -> "Playing"
        else -> "Paused"
    }

    fun micros(ms: Long): Long = ms * 1000

    // The Metadata property, as plain Kotlin values: strings, lists of
    // strings, Longs and Ints. The D-Bus side wraps each in a variant.
    // `artUrl` is a file:// address, never the server's signed one.
    fun metadata(now: NowPlaying?, artUrl: String?): Map<String, Any> {
        if (now == null) return mapOf("mpris:trackid" to ObjectPath(NO_TRACK))
        val out = LinkedHashMap<String, Any>()
        out["mpris:trackid"] = ObjectPath(trackPath(now))
        if (now.durationMs > 0) out["mpris:length"] = micros(now.durationMs)
        out["xesam:title"] = now.title
        if (now.artist.isNotBlank()) out["xesam:artist"] = listOf(now.artist)
        if (now.album.isNotBlank()) out["xesam:album"] = now.album
        if (now.albumArtist.isNotBlank()) out["xesam:albumArtist"] = listOf(now.albumArtist)
        now.trackNumber?.let { out["xesam:trackNumber"] = it }
        now.discNumber?.let { out["xesam:discNumber"] = it }
        if (now.genres.isNotEmpty()) out["xesam:genre"] = now.genres
        if (!artUrl.isNullOrBlank()) out["mpris:artUrl"] = artUrl
        return out
    }

    // Every Player property but Metadata and Position, as plain values.
    fun playerProperties(now: NowPlaying?, volume: Double): Map<String, Any> = linkedMapOf(
        "PlaybackStatus" to status(now),
        "LoopStatus" to "None",
        "Rate" to 1.0,
        "Shuffle" to false,
        "Volume" to volume.coerceIn(0.0, 1.0),
        "MinimumRate" to 1.0,
        "MaximumRate" to 1.0,
        "CanGoNext" to (now?.canNext == true),
        "CanGoPrevious" to (now?.canPrevious == true),
        "CanPlay" to (now != null),
        "CanPause" to (now != null),
        "CanSeek" to ((now?.durationMs ?: 0) > 0),
        "CanControl" to true,
    )

    // The MediaPlayer2 properties.
    fun rootProperties(): Map<String, Any> = linkedMapOf(
        "CanQuit" to true,
        "CanRaise" to true,
        "HasTrackList" to false,
        "Identity" to "Octo",
        // The launcher file the Linux packages install: package name, then app name.
        "DesktopEntry" to "octo-Octo",
        "SupportedUriSchemes" to listOf("file", "octo"),
        "SupportedMimeTypes" to listOf("audio/mpeg", "audio/flac", "audio/mp4", "audio/aac", "audio/ogg", "audio/opus", "audio/wav", "audio/aiff"),
        "CanSetFullscreen" to false,
        "Fullscreen" to false,
    )
}

// A D-Bus object path, told apart from a plain string until it is sent.
data class ObjectPath(val path: String)

// A position kept inside the song, when its length is known.
fun clampPosition(positionMs: Long, durationMs: Long): Long =
    if (durationMs > 0) positionMs.coerceIn(0, durationMs) else positionMs.coerceAtLeast(0)
