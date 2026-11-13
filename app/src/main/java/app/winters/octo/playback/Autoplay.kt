package app.winters.octo.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.followGone
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.knownIds
import app.winters.octo.catalog.read
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.stableRead
import app.winters.octo.discovery.asTrack
import app.winters.octo.listening.PlayHistory
import app.winters.octo.listening.TotalsFailed
import app.winters.octo.output.OutputSwitch
import app.winters.octo.player.PlayerSettings
import app.winters.octo.ui.common.Feedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

// Songs played this recently are not picked again.
private const val RECENT_PLAYS = 50

// How many rows each walk of an album's or a genre's songs reads at a time.
internal const val AUTOPLAY_PAGE = 500

// One album's songs in the album index's order, after a song.
internal const val ALBUM_ROW_PAGE_SQL =
    "SELECT id, albumOrder FROM track WHERE albumId = ? AND (albumOrder, id) > (?, ?) ORDER BY albumOrder, id LIMIT ?"

// Points a test can stop Autoplay at. Each does nothing in the app.
internal open class AutoplayHooks {
    // After a try's walks of the seed's artist and genre, with the ids
    // picked from each.
    open suspend fun walked(artist: List<String>, genre: List<String>) {}

    // Inside the try that reads the picked songs, before it reads them.
    open suspend fun reading() {}
}

// How near the end of the last song the new songs go in: early enough for
// the server to answer and for a crossfade to load the next one.
private const val LEAD_MS = 20_000L
private const val LOAD_AHEAD_MS = 15_000L

// How often to look at how far into the last song the player is.
private const val WATCH_EVERY_MS = 1_000L

// Whether it is time to add songs: Autoplay is on, repeat is off, nothing
// comes after the song that is on, and it is within `leadMs` of its end.
fun autoplayDue(enabled: Boolean, repeatMode: Int, hasNext: Boolean, remainingMs: Long, leadMs: Long): Boolean =
    enabled && repeatMode == Player.REPEAT_MODE_OFF && !hasNext && remainingMs <= leadMs

// Keeps music playing when the queue runs out. Near the end of the last
// song, with repeat off, it adds songs like it (AutoplayPicker), marked as
// Autoplay's so the queue can tell them from the listener's own, at the
// queue's end. The service attaches its player and calls `check` whenever
// the song, the queue, repeat or playing changes. Turning the setting off
// stops it adding. While the play counts cannot be brought up to date it
// adds nothing, and their words show, as for a radio started then.
@OptIn(UnstableApi::class)
@Singleton
class Autoplay internal constructor(
    // Whether the setting is on, as it changes.
    private val turnedOn: Flow<Boolean>,
    // The songs to add after a seed: the queue's last songs, the listener's
    // own among them, and every song the queue holds.
    private val picksAfter: suspend (String, List<String>, List<String>, Set<String>) -> List<String>,
    // The songs the player can play, for ids.
    private val playable: suspend (List<String>) -> List<MediaItem>,
    private val stations: StationRefill,
    // Words for the listener.
    private val show: (String) -> Unit,
) {
    @Inject constructor(settings: PlayerSettings, picker: AutoplayPicker, playable: PlayableSongs, stations: StationRefill, feedback: Feedback) : this(
        settings.prefs.map { it.autoplay },
        picker::picksAfter,
        playable::items,
        stations,
        { words -> feedback.show(words) },
    )

    private var player: OutputSwitch? = null
    private var window: QueueWindow? = null
    private var scope: CoroutineScope? = null
    private var enabled = false
    private var settingsJob: Job? = null

    // Watches the last song until it is time, then adds; for one entry at a time.
    private var watch: Job? = null
    private var watching: String? = null

    // The last song Autoplay added after, so it tries once per song.
    private var doneFor: String? = null

    fun attach(player: OutputSwitch, scope: CoroutineScope, window: QueueWindow? = null) {
        this.player = player
        this.window = window
        this.scope = scope
        settingsJob = scope.launch {
            turnedOn.distinctUntilChanged().collect { on ->
                enabled = on
                check()
            }
        }
    }

    fun detach() {
        settingsJob?.cancel()
        stop()
        player = null
        window = null
        scope = null
    }

    fun check() {
        val player = player ?: return
        val item = player.currentMediaItem
        val key = item?.entryId
        val last = enabled && key != null && key != doneFor && !isRadio(item.mediaId) && !stations.keepsGoing(item) &&
            player.repeatMode == Player.REPEAT_MODE_OFF && !player.hasNextMediaItem() &&
            (player.isPlaying || player.playbackState == Player.STATE_ENDED)
        if (!last) {
            // Paused, repeat on, songs added, or switched off: stand down.
            stop()
            return
        }
        if (watch?.isActive == true && watching == key) return
        stop()
        watching = key
        watch = scope?.launch { watchAndAdd(player, key, item.mediaId) }
    }

    private fun stop() {
        watch?.cancel()
        watch = null
        watching = null
    }

    private suspend fun watchAndAdd(player: OutputSwitch, key: String, seedId: String) {
        // A planned blend can start up to LONGEST_EARLY_EXIT_MS before the end.
        val early = if (player.crossfadeMs > 0) LONGEST_EARLY_EXIT_MS else 0
        val lead = maxOf(LEAD_MS, player.crossfadeMs + early + LOAD_AHEAD_MS)
        while (!autoplayDue(enabled, player.repeatMode, player.hasNextMediaItem(), player.remainingMs(), lead)) {
            delay(WATCH_EVERY_MS)
        }
        // The player holds the queue's end (at most a window of it): its last
        // songs say what came before, and the listener's own among them keep
        // Autoplay near their taste.
        val entries = List(player.mediaItemCount) { player.getMediaItemAt(it) }
        val tail = entries.takeLast(30)
        val anchors = tail.filterNot { it.isAutoplay }.map { it.mediaId }.filter { it != seedId }.takeLast(10).reversed()
        val picked = try {
            picksAfter(seedId, tail.map { it.mediaId }, anchors, entries.mapTo(HashSet()) { it.mediaId })
        } catch (e: TotalsFailed) {
            doneFor = key
            Log.i("Octo", "autoplay: play counts failed")
            show(e.words)
            return
        }
        val items = playable(picked).map { it.markedAutoplay() }
        // Tried once for this song, whatever came of it.
        doneFor = key
        if (items.isEmpty()) {
            Log.i("Octo", "autoplay: nothing to add")
            return
        }
        // The add is the app's own upkeep: while a blend is under way it
        // waits. Either way, only if the song is still the last one and the
        // setting still on.
        player.upkeep {
            if (!enabled || player.currentMediaItem?.entryId != key || player.hasNextMediaItem()) return@upkeep
            val first = player.mediaItemCount
            val window = window
            if (window != null) window.addAtEnd(items, autoplay = true, source = QueueSource.Autoplay.encoded()) else player.addMediaItems(first, items)
            // The song already ended while the server answered: carry on.
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(first, 0)
                player.play()
            }
            Log.i("Octo", "autoplay: added ${items.size}")
        }
    }

    // How long until the song ends, in real time at the current speed.
    private fun OutputSwitch.remainingMs(): Long {
        val length = duration.takeIf { it > 0 } ?: currentMediaItem?.mediaMetadata?.durationMs ?: return 0
        return ((length - currentPosition).coerceAtLeast(0) / playbackParameters.speed).toLong()
    }
}

// What Autoplay adds after a song. Nothing it reads is whole: the songs
// played lately are one read of the stored totals, which songs the queue
// holds is asked of the database a chunk of ids at a time, and its own
// picks walk the seed's artist's albums and its genre a page at a time into
// fair samples of ten.
@Singleton
class AutoplayPicker @Inject constructor(
    private val radio: LibraryRadio,
    private val db: CatalogDatabase,
    private val merge: CatalogMerge,
    private val history: PlayHistory,
    private val queue: QueueStore,
    // Finds a start plays before their rows are written.
    private val finds: FindMap? = null,
) {
    private val catalog = db.dao()
    private val online = db.onlineDao()

    // Where the picks' draws come from; a test sets its own.
    internal var random: Random = Random.Default

    internal var hooks = AutoplayHooks()

    // Octo's radio for the seed and the listener's own recent songs
    // (`anchors`), from the library and the server's suggestions; else the
    // library's songs by the same artist, then in the same genre. Never a
    // song played lately, the seed, or one the queue holds anywhere (`held`
    // are the songs the player has; the rest of the queue is asked of the
    // database). `before` are the queue's last songs, oldest first. While the
    // play counts cannot be brought up to date it waits for nothing and
    // throws TotalsFailed, with their words, at once.
    suspend fun picksAfter(seedId: String, before: List<String>, anchors: List<String>, held: Set<String>): List<String> {
        val seed = seedTrack(seedId) ?: return emptyList()
        val recent = history.recentlyPlayedIds(RECENT_PLAYS)
        val skip = (recent + seed.id).toHashSet()
        val excluded: suspend (List<String>) -> Set<String> = { ids -> ids.filterTo(HashSet()) { it in skip || it in held } + queuedAmong(ids) }
        val own = catalog.tracksByIdsUnordered(anchors).associateBy { it.id }
        val seeds = listOf(seed) + anchors.mapNotNull(own::get)
        val mixed = radio.songsLike(seeds, AUTOPLAY_BATCH, excluded, before = before, random = random)
        if (mixed.isNotEmpty()) return mixed
        return fallbacks(seed, excluded)
    }

    // Which of these songs the queue in use holds, past the player's window
    // too: one read of the queue's rows, never a job of the queue's worker.
    private suspend fun queuedAmong(ids: List<String>): Set<String> {
        val generation = queue.inUse.value
        return if (generation == 0L) emptySet() else queue.heldAmong(generation, ids)
    }

    // The song as the library has it, or as found online.
    private suspend fun seedTrack(id: String): TrackEntity? =
        if (isFind(id)) (online.byIds(listOf(id)).firstOrNull() ?: finds?.get(id))?.asTrack() else catalog.track(id)

    // Up to AUTOPLAY_BATCH of the library's songs by the seed's artist, else
    // with songs in its genre after them, none `excluded`: as fair as
    // shuffling each list whole, putting them one after the other and
    // keeping the first ten distinct. The walks of the artist's albums and of
    // the genre run before each try, outside every lock, keeping fair samples
    // of ids; a try only reads the picked songs again by id, following one a
    // swap renamed and dropping one it took away, so a last try holds the
    // merge lock (a Read) for those reads alone.
    internal suspend fun fallbacks(seed: TrackEntity, excluded: suspend (List<String>) -> Set<String>): List<String> {
        var artist: List<String> = emptyList()
        var genre: List<String> = emptyList()
        return withContext(Dispatchers.IO) {
            stableRead(merge, HoldKind.Read, prepare = {
                artist = sameArtist(seed, excluded)
                genre = if (artist.size >= AUTOPLAY_BATCH) emptyList() else sameGenre(seed, excluded, artist.toHashSet(), AUTOPLAY_BATCH - artist.size)
                hooks.walked(artist, genre)
            }) {
                hooks.reading()
                val shown = still(artist).shuffled(random)
                (shown + still(genre).filterNot { it in shown }.shuffled(random)).distinct().take(AUTOPLAY_BATCH)
            }
        }
    }

    // The picked ids as the library has them now: each one's song, the song
    // a swap renamed it to, or nothing when it is gone.
    private fun still(ids: List<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val known = db.knownIds(ids)
        val gone = ids.filter { it !in known }
        val moved = if (gone.isEmpty()) emptyMap() else db.followGone(gone)
        return ids.mapNotNull { if (it in known) it else moved[it] }
    }

    // A fair sample of up to AUTOPLAY_BATCH songs by the seed's artist, none
    // excluded, its albums walked a page at a time. A song found online is
    // matched to a library artist by name.
    private suspend fun sameArtist(seed: TrackEntity, excluded: suspend (List<String>) -> Set<String>): List<String> {
        val artistIds = if (isFind(seed.id)) catalog.artistsWithKeys(listOf(searchKey(seed.artist))).map { it.id } else listOf(seed.artistId)
        val sample = Reservoir(AUTOPLAY_BATCH, random)
        for (album in artistIds.flatMap { catalog.artistAlbums(it).first() }) {
            var key = Long.MIN_VALUE to ""
            while (true) {
                val rows = db.read(ALBUM_ROW_PAGE_SQL, album.id, key.first, key.second, AUTOPLAY_PAGE) { it.getString(0) to it.getLong(1) }
                val ids = rows.map { it.first }
                val left = excluded(ids)
                ids.filterNot { it in left }.forEach(sample::offer)
                if (rows.size < AUTOPLAY_PAGE) break
                key = rows.last().let { it.second to it.first }
            }
        }
        return sample.kept()
    }

    // A fair sample of up to `count` songs in the seed's genre (spelt any
    // way but for case), none excluded nor among `artist`, walked a page at
    // a time on the genre's index.
    private suspend fun sameGenre(seed: TrackEntity, excluded: suspend (List<String>) -> Set<String>, artist: Set<String>, count: Int): List<String> {
        if (seed.genre.isBlank()) return emptyList()
        val sample = Reservoir(count, random)
        var key = Triple("", "", "")
        while (true) {
            val rows = db.read(GENRE_ROW_PAGE_SQL, seed.genre, key.first, key.second, key.third, AUTOPLAY_PAGE) {
                Triple(it.getString(1), it.getString(2), it.getString(0))
            }
            val ids = rows.map { it.third }.filterNot { it in artist }
            val left = excluded(ids)
            ids.filterNot { it in left }.forEach(sample::offer)
            if (rows.size < AUTOPLAY_PAGE) break
            key = rows.last()
        }
        return sample.kept()
    }
}
