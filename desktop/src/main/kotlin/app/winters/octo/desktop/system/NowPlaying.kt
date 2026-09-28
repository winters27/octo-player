package app.winters.octo.desktop.system

import app.winters.octo.desktop.player.PlayerState
import app.winters.octo.desktop.player.RepeatMode

// The song the system should show, taken from the player: what the media
// controls, the tray, the notifications and the mini player all read.
data class NowPlaying(
    // The queue entry, which tells two plays of the same song apart.
    val entryKey: Long,
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val durationMs: Long,
    val coverId: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val genres: List<String>,
    val playing: Boolean,
    // Previous always works with a song in: it goes back or starts over.
    val canPrevious: Boolean,
    val canNext: Boolean,
)

// What the system shows for the player's state, or null with nothing in.
fun nowPlayingOf(state: PlayerState): NowPlaying? {
    val entry = state.current ?: return null
    val song = entry.song
    val opened = openedFileOf(song.id)
    return NowPlaying(
        entryKey = entry.key,
        songId = song.id,
        title = song.title.ifBlank { opened?.let { titleFromFileName(it.path) } ?: "Unknown song" },
        artist = (song.displayArtist ?: song.artist).orEmpty(),
        album = song.album.orEmpty(),
        albumArtist = (song.displayAlbumArtist ?: song.albumArtists.joinToString(", ") { it.name }.ifBlank { null }).orEmpty(),
        durationMs = state.durationMs.takeIf { it > 0 } ?: (song.duration * 1000L),
        coverId = song.coverArt,
        trackNumber = song.track,
        discNumber = song.discNumber,
        genres = song.genres.ifEmpty { listOfNotNull(song.genre) },
        playing = state.playing,
        canPrevious = true,
        canNext = state.upcoming.isNotEmpty() || (state.repeat == RepeatMode.All && state.queue.isNotEmpty()),
    )
}
