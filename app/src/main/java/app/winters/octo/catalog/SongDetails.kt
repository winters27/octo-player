package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.device.genreKey

// What the library knows about a song beyond what its lists show, gathered
// from every copy of it: the song info sheet and ListenBrainz read this.
data class SongDetails(
    // This edition's year, and the year the song first came out.
    val year: Int? = null,
    val originalYear: Int? = null,
    val genres: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val composer: String? = null,
    val bpm: Int? = null,
    val comment: String? = null,
    val explicit: Boolean? = null,
    val discTitle: String? = null,
    val mbRecordingId: String? = null,
    val mbAlbumId: String? = null,
    val mbReleaseGroupId: String? = null,
    val mbArtistIds: List<String> = emptyList(),
)

// The details of a song from its copies, by the merge rules in
// CatalogMerger: the phone's copy is asked first, then each server's; each
// detail comes from the first copy that has it, genres are every copy's
// together, and the artists are the fullest credit any copy gives.
fun songDetails(copies: List<SourceTrackEntity>): SongDetails {
    val ordered = copies.sortedBy { if (it.sourceId == DEVICE) "" else it.sourceId }
    fun <T> first(pick: (SourceTrackEntity) -> T?): T? = ordered.firstNotNullOfOrNull(pick)
    return SongDetails(
        year = first { it.year },
        originalYear = first { it.originalYear },
        genres = allGenres(ordered.map { copy -> splitLines(copy.genres).ifEmpty { listOf(copy.genre) } }),
        artists = fullestCredit(ordered.map { splitLines(it.artists) }),
        composer = first { it.composer?.takeIf(String::isNotBlank) },
        bpm = first { it.bpm?.takeIf { bpm -> bpm > 0 } },
        comment = first { it.comment?.takeIf(String::isNotBlank) },
        explicit = first { it.explicit },
        discTitle = first { it.discTitle?.takeIf(String::isNotBlank) },
        mbRecordingId = first { it.mbRecordingId?.takeIf(String::isNotBlank) },
        mbAlbumId = first { it.mbAlbumId?.takeIf(String::isNotBlank) },
        mbReleaseGroupId = first { it.mbReleaseGroupId?.takeIf(String::isNotBlank) },
        mbArtistIds = ordered.map { splitLines(it.mbArtistIds) }.firstOrNull { it.isNotEmpty() }.orEmpty(),
    )
}

// Every genre in these lists, in order, each once: spellings of one genre
// ("Hip Hop", "Hip-Hop/Rap") count as one, and the first spelling stays.
fun allGenres(lists: List<List<String>>): List<String> =
    lists.flatten().map(String::trim).filter(String::isNotEmpty).distinctBy(::genreKey)

// The credit naming the most artists; the first of equals.
fun fullestCredit(lists: List<List<String>>): List<String> =
    lists.fold(emptyList<String>()) { best, next -> if (next.size > best.size) next else best }

// A list kept as text, one value per line, and back.
fun joinLines(values: List<String>?): String = values.orEmpty().joinToString("\n")

fun splitLines(text: String): List<String> = text.lines().map(String::trim).filter(String::isNotEmpty)
