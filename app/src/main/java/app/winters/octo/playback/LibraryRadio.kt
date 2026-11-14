package app.winters.octo.playback

import android.util.Log
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.ID_CHUNK
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.followGone
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.knownIds
import app.winters.octo.catalog.read
import app.winters.octo.catalog.readLong
import app.winters.octo.catalog.searchKey
import app.winters.octo.discovery.Discovery
import app.winters.octo.discovery.OwnedFinds
import app.winters.octo.discovery.librarySongsOnly
import app.winters.octo.listening.PlayCount
import app.winters.octo.listening.PlayTotals
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.player.PlayerSettings
import app.winters.octo.radio.RADIO_CANDIDATES
import app.winters.octo.radio.RadioInput
import app.winters.octo.radio.RadioPool
import app.winters.octo.radio.RadioSong
import app.winters.octo.radio.RadioTuning
import app.winters.octo.radio.radioArtistKey
import app.winters.octo.radio.radioMixPooled
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.ui.paging.SqlOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

// How many songs a radio plays after the one it started from.
const val RADIO_LENGTH = 50

// How many rows each walk of the pool reads at a time.
internal const val RADIO_PAGE = 500

// The library's songs by NOCASE genre: one row per class of spellings
// equal but for ASCII case, named by its smallest spelling.
internal const val GENRE_CLASS_PAGE_SQL =
    "SELECT MIN(genre COLLATE BINARY) AS spelling FROM track WHERE genre > ? GROUP BY genre ORDER BY genre LIMIT ?"

// One genre class's songs, in the genre index's order.
internal const val GENRE_ROW_PAGE_SQL =
    "SELECT id, sortKey, artistSortKey FROM track WHERE genre = ? AND (sortKey, artistSortKey, id) > (?, ?, ?) " +
        "ORDER BY sortKey, artistSortKey, id LIMIT ?"

// Each artist name and id the library files songs under, and whether one of
// their songs is rated one star.
internal const val ARTIST_PAIR_PAGE_SQL =
    "SELECT artist, artistId, MAX(rating = 1) AS oneStar FROM track WHERE (artist, artistId) > (?, ?) " +
        "GROUP BY artist, artistId ORDER BY artist, artistId LIMIT ?"

// One artist name and id's songs.
internal const val ARTIST_ROW_PAGE_SQL =
    "SELECT id, genre, rating FROM track WHERE artist = ? AND artistId = ? AND (rating, id) > (?, ?) ORDER BY rating, id LIMIT ?"

internal const val LIBRARY_SIZE_SQL = "SELECT COUNT(*) FROM track"

// Points a test can stop a radio at. Each does nothing in the app.
internal open class RadioHooks {
    // After a try's walks of the library, before it reads their songs:
    // how many songs could fit, and the ids kept of them.
    open suspend fun walked(offered: Int, kept: List<String>) {}

    // Inside the try that reads the songs, before it reads them.
    open suspend fun reading() {}
}

// Octo's radio on the phone: songs like the seeds from the library and,
// when a server is signed in, from its suggestions, songs it found online
// among them (see radioMix), tuned by the listener's radio settings. Works
// from the library alone. Start radio and Autoplay both use it. With
// "Library songs only" on, the server's songs found online are left out.
//
// The library is never read whole. Walks of its genre and artist indexes
// find the songs that could fit, keeping a fair sample of RADIO_CANDIDATES
// of them by id (all of them, in library order, when that many or fewer
// fit), and the artists with a song rated one star. Only the sampled songs
// are then read, with their likes and their stored plays. The walks run
// before each try of a read of the stored totals; the try itself reads by
// id and picks, so its last try holds the totals and the merge lock only
// for those reads.
@Singleton
class LibraryRadio internal constructor(
    private val db: CatalogDatabase,
    private val totals: PlayTotals,
    // The server's songs like a seed, best first; none without a server.
    private val suggestions: suspend (TrackEntity, Int) -> List<TrackEntity>,
    private val prefs: Flow<PlayerPrefs>,
    private val page: Int = RADIO_PAGE,
    // Songs the server owns that the phone's copy lacks (OwnedFinds): library
    // songs, kept under "Library songs only".
    private val owned: () -> Set<String> = { emptySet() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(db: CatalogDatabase, totals: PlayTotals, discovery: Discovery, settings: PlayerSettings, ownedFinds: OwnedFinds) :
        this(db, totals, { seed, count -> discovery.radio(seed, count) }, settings.prefs, owned = { ownedFinds.ids.value })

    internal var hooks = RadioHooks()

    // Try again, from the words failed play counts showed.
    fun tryAgain() = totals.tryAgain()

    // Ids of up to `count` songs like `seeds`, never the first seed or one
    // `excluded` names, spaced on from the songs in `before` (ids, oldest
    // first). `excluded` answers which of up to ID_CHUNK ids are left out;
    // it reads the database at most, and never waits. Server trouble only
    // loses its suggestions. Waits until the stored totals are built
    // through the library as it is, and throws TotalsFailed instead while
    // they cannot be.
    suspend fun songsLike(
        seeds: List<TrackEntity>,
        count: Int,
        excluded: suspend (ids: List<String>) -> Set<String> = { emptySet() },
        before: List<String> = emptyList(),
        random: Random = Random.Default,
    ): List<String> {
        val seed = seeds.firstOrNull() ?: return emptyList()
        if (count <= 0) return emptyList()
        val prefs = prefs.first()
        val tuning = prefs.radioTuning
        val suggested = try {
            withContext(Dispatchers.IO) { suggestions(seed, tuning.suggestions) }.filter { it.id != seed.id }
        } catch (e: SubsonicException) {
            Log.w("Octo", "radio: server suggestions failed: ${e.javaClass.simpleName}")
            emptyList()
        }
        val mine = owned()
        val kept = librarySongsOnly(suggested, prefs.libraryOnly) { isFind(it.id) && it.id !in mine }
        val genres = seeds.mapNotNullTo(HashSet()) { genreKeyOf(it.genre) }
        val artists = seeds.mapNotNullTo(HashSet()) { artistKeyOf(it.artist, it.artistId) }
        var walked: Walked? = null
        return withContext(Dispatchers.IO) {
            totals.read(HoldKind.Read, prepare = { walked = walk(genres, artists, random).also { hooks.walked(it.offered, it.ids) } }) {
                hooks.reading()
                pick(walked!!, seeds, kept, before, count, excluded, tuning, random)
            }
        }
    }

    // What the walks found: how many songs the library has, the sampled
    // ids, how many could fit in all, and the artists with a song rated
    // one star.
    private class Walked(val librarySize: Int, val ids: List<String>, val offered: Int, val disliked: Set<String>)

    // Walks the genre classes the seeds share, their songs, then every
    // artist the library files songs under, offering each song that could
    // fit once to a fair sample.
    private suspend fun walk(genres: Set<String>, artists: Set<String>, random: Random): Walked {
        val size = db.readLong(LIBRARY_SIZE_SQL).toInt()
        val classes = ArrayList<String>()
        var after = ""
        while (true) {
            val spellings = db.read(GENRE_CLASS_PAGE_SQL, after, page) { it.getString(0) }
            spellings.filterTo(classes) { it.isNotBlank() && searchKey(it) in genres }
            if (spellings.size < page) break
            after = spellings.last()
        }
        val sample = Reservoir(RADIO_CANDIDATES, random)
        for (genre in classes) {
            var key = Triple("", "", "")
            while (true) {
                val rows = db.read(GENRE_ROW_PAGE_SQL, genre, key.first, key.second, key.third, page) {
                    Triple(it.getString(1), it.getString(2), it.getString(0))
                }
                rows.forEach { sample.offer(it.third) }
                if (rows.size < page) break
                key = rows.last()
            }
        }
        val matched = classes.mapTo(HashSet(), ::asciiFold)
        val disliked = HashSet<String>()
        var pair = "" to ""
        while (true) {
            val pairs = db.read(ARTIST_PAIR_PAGE_SQL, pair.first, pair.second, page) { Triple(it.getString(0), it.getString(1), it.getInt(2) == 1) }
            for ((artist, artistId, oneStar) in pairs) {
                val key = artistKeyOf(artist, artistId) ?: continue
                if (oneStar) disliked += key
                if (key in artists) offerArtist(artist, artistId, matched, sample)
            }
            if (pairs.size < page) break
            pair = pairs.last().let { it.first to it.second }
        }
        return Walked(size, sample.kept(), sample.offered, disliked)
    }

    // An artist's songs, leaving out those whose genre class was offered.
    private fun offerArtist(artist: String, artistId: String, matched: Set<String>, sample: Reservoir) {
        var key = -1 to ""
        while (true) {
            val rows = db.read(ARTIST_ROW_PAGE_SQL, artist, artistId, key.first, key.second, page) {
                Triple(it.getString(0), it.getString(1), it.getInt(2))
            }
            for ((id, genre, _) in rows) if (genre.isBlank() || asciiFold(genre) !in matched) sample.offer(id)
            if (rows.size < page) break
            rows.last().let { key = it.third to it.first }
        }
    }

    // Reads the sampled songs, the seeds, the suggestions and the songs
    // before by id, with their likes and plays, and picks. A sampled id the
    // library no longer has is followed to the song it became, or dropped.
    private suspend fun pick(
        walked: Walked,
        seeds: List<TrackEntity>,
        suggested: List<TrackEntity>,
        before: List<String>,
        count: Int,
        excluded: suspend (List<String>) -> Set<String>,
        tuning: RadioTuning,
        random: Random,
    ): List<String> {
        val rows = tracksByIds(walked.ids)
        val gone = walked.ids.filter { it !in rows }
        val moved = if (gone.isEmpty()) emptyMap() else db.followGone(gone)
        val more = tracksByIds(moved.values.filter { it !in rows })
        val sampled = LinkedHashMap<String, TrackEntity>()
        for (id in walked.ids) (rows[id] ?: moved[id]?.let { rows[it] ?: more[it] })?.let { sampled.putIfAbsent(it.id, it) }
        val candidates = sampled.values.toMutableList()
        if (walked.offered > RADIO_CANDIDATES) {
            candidates.shuffle(random)
        } else {
            candidates.sortWith { a, b -> SqlOrder.text(a.sortKey, b.sortKey).takeIf { it != 0 } ?: SqlOrder.text(a.id, b.id) }
        }
        val beforeRows = tracksByIds(before)
        val everyone = (candidates.map { it.id } + seeds.map { it.id } + suggested.map { it.id } + beforeRows.keys).distinct()
        val liked = HashSet<String>()
        everyone.chunked(ID_CHUNK).forEach { liked += db.userDao().likedAmong(it).first() }
        val plays = totals.playsOf(everyone)
        fun TrackEntity.radio() = radioSong(id in liked, plays[id])
        val suggestedSongs = suggested.map { it.radio() }
        val owned = candidates.mapTo(HashSet()) { it.id }.apply { addAll(db.knownIds(suggested.map { it.id })) }
        val disliked = HashSet(walked.disliked).apply { suggestedSongs.filter { it.rating == 1 }.forEach { addAll(it.artists.mapNotNull(::radioArtistKey)) } }
        val skip = HashSet<String>()
        (candidates.map { it.id } + suggested.map { it.id }).distinct().chunked(ID_CHUNK).forEach { skip += excluded(it) }
        val pool = RadioPool(walked.librarySize, candidates.map { it.radio() }, rarity = null, disliked = disliked, owned = owned)
        return radioMixPooled(
            pool,
            RadioInput(
                seeds = seeds.map { it.radio() },
                library = emptyList(),
                suggested = suggestedSongs,
                before = before.mapNotNull(beforeRows::get).map { it.radio() },
                exclude = skip,
                now = clock(),
                tuning = tuning,
                // A song found online is not in `before` above, but its
                // kind still carries the run on.
                beforeOutside = before.map(::isFind),
            ),
            count,
            random,
        ).map { it.id }
    }

    private suspend fun tracksByIds(ids: List<String>): Map<String, TrackEntity> {
        val found = HashMap<String, TrackEntity>()
        ids.distinct().chunked(ID_CHUNK).forEach { chunk -> db.dao().tracksByIdsUnordered(chunk).forEach { found[it.id] = it } }
        return found
    }
}

// A fair sample of up to `size` ids from however many are offered, one at
// a time: every offered id is equally likely to be kept (Algorithm R).
// Until more than `size` are offered it keeps them all and draws nothing.
internal class Reservoir(private val size: Int, private val random: Random) {
    private val ids = ArrayList<String>()
    var offered = 0
        private set

    fun offer(id: String) {
        offered++
        if (ids.size < size) {
            ids += id
            return
        }
        val at = random.nextInt(offered)
        if (at < size) ids[at] = id
    }

    fun kept(): List<String> = ids.toList()
}

// A genre's key as the radio compares genres, or null for none.
internal fun genreKeyOf(genre: String): String? = genre.takeIf(String::isNotBlank)?.let(::searchKey)?.takeIf(String::isNotEmpty)

// A phone song's artist key as the radio compares artists: its artist's
// name, or its artist id when it has no name.
internal fun artistKeyOf(artist: String, artistId: String): String? = radioArtistKey(if (artist.isBlank()) artistId else artist)

// A text with ASCII letters lowercased and nothing else changed: the fold
// the database's NOCASE compares by.
internal fun asciiFold(text: String): String {
    if (text.none { it in 'A'..'Z' }) return text
    return buildString(text.length) { text.forEach { append(if (it in 'A'..'Z') it + 32 else it) } }
}

// A phone song as the radio sees it.
internal fun TrackEntity.radioSong(liked: Set<String>, played: PlayedTrack?): RadioSong =
    radioSong(id in liked, played?.let { PlayCount(id, it.plays, it.lastPlayedAt) })

internal fun TrackEntity.radioSong(liked: Boolean, played: PlayCount?): RadioSong = RadioSong(
    id = id,
    title = title,
    artists = listOf(artist).filter(String::isNotBlank).ifEmpty { listOf(artistId) },
    album = albumId,
    genres = listOfNotNull(genre.takeIf(String::isNotBlank)),
    year = year,
    durationMs = durationMs,
    liked = liked,
    rating = rating,
    plays = played?.plays?.toLong() ?: 0,
    lastPlayedAt = played?.lastPlayedAt,
)
