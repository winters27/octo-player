package app.winters.octo.playback.today

import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.isFind
import app.winters.octo.discovery.librarySongsOnly
import app.winters.octo.radio.RadioInput
import app.winters.octo.radio.RadioSong
import app.winters.octo.radio.RadioTuning
import app.winters.octo.radio.radioMix
import kotlin.random.Random

// A labeled pin: LibraryRadio.songsLike's body and radioSong as
// playback/LibraryRadio.kt had them at c2208eaa, given what that body read
// whole (`library` in library order, the liked ids, the played songs, the
// server's suggestions and the settings), frozen as the measure the walked
// pool must match. Never edited to follow the app.
fun songsLike(
    library: List<TrackEntity>,
    liked: Set<String>,
    played: List<PlayedTrack>,
    seeds: List<TrackEntity>,
    suggestions: List<TrackEntity>,
    libraryOnly: Boolean,
    tuning: RadioTuning,
    count: Int,
    exclude: Set<String>,
    before: List<String>,
    now: Long,
    random: Random,
): List<String> {
    val seed = seeds.firstOrNull() ?: return emptyList()
    val suggested = suggestions.filter { it.id != seed.id }
    val byPlayed = played.associateBy { it.track.id }
    val byId = library.associateBy { it.id }
    fun TrackEntity.radio() = radioSong(liked, byPlayed[id])
    return radioMix(
        RadioInput(
            seeds = seeds.map { it.radio() },
            library = library.map { it.radio() },
            suggested = librarySongsOnly(suggested, libraryOnly) { isFind(it.id) }.map { it.radio() },
            before = before.mapNotNull(byId::get).map { it.radio() },
            exclude = exclude,
            now = now,
            tuning = tuning,
            beforeOutside = before.map(::isFind),
        ),
        count,
        random,
    ).map { it.id }
}

fun TrackEntity.radioSong(liked: Set<String>, played: PlayedTrack?): RadioSong = RadioSong(
    id = id,
    title = title,
    artists = listOf(artist).filter(String::isNotBlank).ifEmpty { listOf(artistId) },
    album = albumId,
    genres = listOfNotNull(genre.takeIf(String::isNotBlank)),
    year = year,
    durationMs = durationMs,
    liked = id in liked,
    rating = rating,
    plays = played?.plays?.toLong() ?: 0,
    lastPlayedAt = played?.lastPlayedAt,
)
