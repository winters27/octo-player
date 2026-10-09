package app.winters.octo.playback

import android.util.Log
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.isFind
import app.winters.octo.discovery.Discovery
import app.winters.octo.discovery.librarySongsOnly
import app.winters.octo.listening.PlayHistory
import app.winters.octo.player.PlayerSettings
import app.winters.octo.radio.RadioInput
import app.winters.octo.radio.RadioSong
import app.winters.octo.radio.radioMix
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// How many songs a radio plays after the one it started from.
const val RADIO_LENGTH = 50

// Octo's radio on the phone: songs like the seeds from the library and,
// when a server is signed in, from its suggestions, songs it found online
// among them (see radioMix). Works from the library alone. Start radio and
// Autoplay both use it. With "Library songs only" on, the server's songs
// found online are left out.
@Singleton
class LibraryRadio @Inject constructor(
    private val catalog: CatalogDao,
    private val user: UserDao,
    private val history: PlayHistory,
    private val discovery: Discovery,
    private val player: PlayerSettings,
) {
    // Ids of up to `count` songs like `seeds`, never the first seed or one
    // in `exclude`, spaced on from the songs in `before` (ids, oldest
    // first). Server trouble only loses its suggestions.
    suspend fun songsLike(
        seeds: List<TrackEntity>,
        count: Int,
        exclude: Set<String> = emptySet(),
        before: List<String> = emptyList(),
    ): List<String> {
        val seed = seeds.firstOrNull() ?: return emptyList()
        val suggested = try {
            withContext(Dispatchers.IO) { discovery.radio(seed) }.filter { it.id != seed.id }
        } catch (e: SubsonicException) {
            Log.w("Octo", "radio: server suggestions failed: ${e.javaClass.simpleName}")
            emptyList()
        }
        val libraryOnly = player.prefs.first().libraryOnly
        val library = catalog.tracks().first()
        val liked = user.likedIds().first().toHashSet()
        val played = history.tracks.first().associateBy { it.track.id }
        return withContext(Dispatchers.Default) {
            val byId = library.associateBy { it.id }
            fun TrackEntity.radio() = radioSong(liked, played[id])
            radioMix(
                RadioInput(
                    seeds = seeds.map { it.radio() },
                    library = library.map { it.radio() },
                    suggested = librarySongsOnly(suggested, libraryOnly) { isFind(it.id) }.map { it.radio() },
                    before = before.mapNotNull(byId::get).map { it.radio() },
                    exclude = exclude,
                    now = System.currentTimeMillis(),
                ),
                count,
            ).map { it.id }
        }
    }
}

// A phone song as the radio sees it.
internal fun TrackEntity.radioSong(liked: Set<String>, played: PlayedTrack?): RadioSong = RadioSong(
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
