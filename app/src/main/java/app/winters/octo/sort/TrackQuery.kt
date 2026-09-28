package app.winters.octo.sort

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.playback.isLossless
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.SongFields
import app.winters.octo.query.isLosslessFormat
import app.winters.octo.query.places

// The phone's library songs, read the way the shared filters ask. Likes and
// plays live beside the songs, so they come in: the liked song ids, and each
// song's listening (the phone's plays and the server's, as the orders use).
// The phone keeps no album artist, composer, bit rate or BPM, so a filter on
// those matches nothing here.
class TrackFields(
    private val liked: Set<String> = emptySet(),
    private val listening: Map<String, Listening> = emptyMap(),
) : SongFields<TrackEntity> {
    override fun id(song: TrackEntity) = song.id
    override fun title(song: TrackEntity) = song.title
    override fun artist(song: TrackEntity) = song.artist.ifBlank { null }
    override fun album(song: TrackEntity) = song.album.ifBlank { null }
    override fun albumId(song: TrackEntity) = song.albumId
    override fun albumArtist(song: TrackEntity): String? = null
    override fun genres(song: TrackEntity) = listOfNotNull(song.genre.takeIf(String::isNotBlank))
    override fun composer(song: TrackEntity): String? = null
    override fun year(song: TrackEntity) = song.year
    override fun disc(song: TrackEntity) = song.discNo
    override fun track(song: TrackEntity) = song.trackNo

    // The catalogue keeps seconds.
    override fun addedAt(song: TrackEntity) = song.addedAt.takeIf { it > 0 }?.let { it * 1_000 }
    override fun lastPlayedAt(song: TrackEntity) = listening[song.id]?.lastPlayedAt?.takeIf { it > 0 }
    override fun plays(song: TrackEntity) = listening[song.id]?.plays?.toLong() ?: 0
    override fun rating(song: TrackEntity) = song.rating
    override fun favourite(song: TrackEntity) = song.id in liked
    override fun likedAt(song: TrackEntity): Long? = null
    override fun seconds(song: TrackEntity) = (song.durationMs / 1_000).toInt()
    override fun format(song: TrackEntity) = formatOf(song.mimeType)
    override fun lossless(song: TrackEntity) = isLossless(song.mimeType) || isLosslessFormat(formatOf(song.mimeType))
    override fun bitRate(song: TrackEntity): Int? = null
    override fun bpm(song: TrackEntity): Int? = null
}

// A file's kind from its MIME type, in the words a server's suffix uses:
// "audio/flac" is flac, "audio/mpeg" mp3, "audio/mp4" m4a.
fun formatOf(mimeType: String?): String? {
    val kind = mimeType?.lowercase()?.substringAfter('/', "")?.substringBefore(';')?.trim()?.removePrefix("x-") ?: return null
    return when (kind) {
        "" -> null
        "mpeg", "mp3", "mpeg3" -> "mp3"
        "mp4", "m4a" -> "m4a"
        "aac-adts" -> "aac"
        "wave", "vnd.wave" -> "wav"
        "aif" -> "aiff"
        "wavpack" -> "wv"
        else -> kind
    }
}

// A sorted list of songs as `query` filters it, in the same order, its
// letter headings kept in step with the songs left. The query's own order
// and limit are not used: the list keeps the order the listener chose.
fun filteredSongs(all: Sorted<TrackEntity>, query: LibraryQuery, fields: SongFields<TrackEntity>, now: Long): Sorted<TrackEntity> {
    if (!query.filters) return all
    val kept = query.copy(sort = null, limit = null).places(all.items, now, fields)
    val headings = all.headings
    return Sorted(kept.map(all.items::get), all.order, headings?.let { h -> kept.map(h::get) })
}
