package app.winters.octo.playback

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.DeviceInfo
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionResult
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.ui.common.Feedback
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// What a tapped row or a page header shows of a play, for the starting
// state before anything is read.
data class Shown(val title: String?, val artist: String? = null, val artwork: String? = null)

// What the app shows about the song that is on.
data class NowPlaying(
    val trackId: String? = null,
    // The queue entry playing: the same song queued twice is two entries.
    val entryId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val artwork: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val quality: AudioQuality? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    // Playing on a TV or speaker, and that device's volume from 0 to 1.
    val casting: Boolean = false,
    val deviceVolume: Float? = null,
    // Marked explicit, for the "E" by its title.
    val explicit: Boolean = false,
    // Meant to play: true while it plays, and while it connects or buffers.
    val playWhenReady: Boolean = false,
    // A live station on now, with what it has on air.
    val live: LiveNow? = null,
    // While a play just asked for is starting, what shows in place of the
    // artist ("Starting"); the title and cover are the tapped song's.
    val starting: String? = null,
)

// The app's line to the playback service. Screens ask it to play things and
// read what is playing from it; the service does the rest. Open so a test
// can stand in for the service and hear what a screen asks to play.
@Singleton
open class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lengths: RealLengths,
    private val queue: QueueStore,
    private val startingPlays: StartingPlays,
    private val feedback: dagger.Lazy<Feedback>,
) {
    private val scope = MainScope()

    // The play the app is starting, shown at once until its first song is
    // the player's.
    val starting: StateFlow<StartingPlay?> = startingPlays.starting

    // Lists still being written into the queue, for Up next's quiet rows.
    val building: StateFlow<List<BuildingRow>> = queue.building
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    // Requests made before the connection is up run as soon as it is.
    private val waiting = mutableListOf<(MediaController) -> Unit>()

    private val _now = MutableStateFlow(NowPlaying())

    // What plays, or while a play is starting, the song it starts with as
    // the screen knew it, with the starting words.
    val now: StateFlow<NowPlaying> = combine(_now, startingPlays.starting) { now, starting ->
        if (starting == null) {
            now
        } else {
            now.copy(
                title = starting.title ?: now.title,
                artist = starting.artist ?: now.artist,
                artwork = starting.artwork ?: now.artwork,
                starting = starting.words,
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, NowPlaying())

    // The current song and everything after it, in the order they will play.
    private val _upNext = MutableStateFlow<List<QueueEntry>>(emptyList())
    val upNext: StateFlow<List<QueueEntry>> = _upNext

    // The songs played before the current one, oldest first.
    private val _played = MutableStateFlow<List<QueueEntry>>(emptyList())
    val played: StateFlow<List<QueueEntry>> = _played

    // Counts the times the player said its clock moved other than by
    // playing on: a seek, a new song, play or pause, or a new speed. The
    // lyrics take a fresh anchor on each.
    private val _timeEvents = MutableStateFlow(0)
    val timeEvents: StateFlow<Int> = _timeEvents

    init {
        // A song's real length found out while it plays reaches the queue
        // rows already on screen.
        scope.launch {
            lengths.corrected.collect {
                controller?.let { c ->
                    publish(c)
                    publishQueue(c)
                }
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
            // A play that was starting ends once its first song is the player's.
            startingPlays.playing(player.currentMediaItem?.entryId)
            if (events.containsAny(
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                )
            ) {
                _timeEvents.value++
            }
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                publishQueue(player)
            }
        }
    }

    // Called when the app comes to the front.
    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, OctoPlaybackService::class.java))
        val pending = MediaController.Builder(context, token)
            .setListener(
                object : MediaController.Listener {
                    // The service went away: a play still starting says it could not.
                    override fun onDisconnected(controller: MediaController) {
                        startingPlays.starting.value?.let { failed(it.token, START_FAILED_WORDS, false) }
                    }
                },
            )
            .buildAsync()
        future = pending
        pending.addListener(
            {
                val ready = runCatching { pending.get() }.getOrNull()
                if (ready == null) {
                    // A play that was starting cannot reach the service.
                    future = null
                    waiting.clear()
                    startingPlays.starting.value?.let { failed(it.token, START_FAILED_WORDS, false) }
                    return@addListener
                }
                controller = ready
                ready.addListener(listener)
                publish(ready)
                publishQueue(ready)
                waiting.forEach { it(ready) }
                waiting.clear()
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    // Called when the app leaves the front, so the service can stop when
    // nothing is playing.
    fun release() {
        controller?.removeListener(listener)
        future?.let(MediaController::releaseFuture)
        future = null
        controller = null
    }

    // A play that did not start: the starting state goes and, unless the
    // route shows its own fallback, the words show.
    private fun failed(token: Long, words: String?, handlesRefusal: Boolean) {
        if (!startingPlays.end(token)) return
        if (!handlesRefusal && words != null) feedback.get().show(words)
    }

    fun positionMs(): Long = controller?.currentPosition ?: 0

    // How fast the player is playing now, 1 being normal.
    fun speed(): Float = controller?.playbackParameters?.speed ?: 1f

    // Plays songs named one by one, from one of them, or shuffled: at most
    // QUEUE_WINDOW of them, around the one it starts from.
    fun playTracks(trackIds: List<String>, startIndex: Int = 0, shuffle: Boolean = false, source: String? = null) {
        if (trackIds.isEmpty()) return
        val (ids, at) = idsAround(trackIds, startIndex)
        playList(ListSpec.Ids(ids, source), if (shuffle) null else ListStart.At(at, 0), shuffle, source)
    }

    // Plays a station's songs from one of them, under its name; as they run
    // low, more of its kind are added. Shuffled, its songs play in a new
    // order, and the songs added later still come after them.
    fun playStation(trackIds: List<String>, startIndex: Int, id: String, name: String, shuffle: Boolean = false, handlesRefusal: Boolean = false) {
        val ids = trackIds.take(QUEUE_WINDOW)
        if (ids.isEmpty()) return
        playList(
            ListSpec.Ids(ids),
            if (shuffle) null else ListStart.At(startIndex.coerceIn(ids.indices), 0),
            shuffle,
            source = name,
            word = QueueSource.Station(id, name).encoded(),
            handlesRefusal = handlesRefusal,
        )
    }

    // Plays the stations a list holds, in its order, from the one tapped:
    // Back and Next go through them.
    fun playLineup(stationIds: List<String>, startIndex: Int, source: String? = null) {
        if (stationIds.isEmpty()) return
        val ids = stationIds.take(QUEUE_WINDOW)
        playList(ListSpec.Ids(ids, source), ListStart.At(startIndex.coerceIn(ids.indices), 0), source = source)
    }

    // With nothing queued, the play button shuffles the whole library. A
    // station that is connecting counts as playing, so the button stops it.
    fun togglePlayPause() = withController { c ->
        when {
            c.mediaItemCount == 0 -> playList(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), null, shuffle = true)
            c.isPlaying -> c.pause()
            c.playWhenReady && c.currentMediaItem?.mediaId?.let(::isRadio) == true -> c.pause()
            else -> {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
            }
        }
    }

    // Plays what was asked for out loud, like "Drake", with what the
    // assistant picked out of it (`extras`: an artist, an album, a song).
    // The service works out what that means and plays it only when it finds
    // something; saying nothing in particular shuffles everything.
    fun playFromSearch(query: String, extras: Bundle? = null) = withController { c ->
        c.sendCustomCommand(PLAY_FROM_SEARCH, playFromSearchArgs(query, extras))
    }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs.coerceAtLeast(0)) }

    // The volume of the TV or speaker music is cast to, from 0 to 1.
    fun setDeviceVolume(fraction: Float) = withController { c ->
        val max = c.deviceInfo.maxVolume.coerceAtLeast(1)
        c.setDeviceVolume(Math.round(fraction.coerceIn(0f, 1f) * max), 0)
    }

    // A volume button pressed while casting: the device goes up or down a
    // step. Answers whether it was taken, which it is only while casting.
    fun stepDeviceVolume(up: Boolean): Boolean {
        val c = controller ?: return false
        if (!_now.value.casting) return false
        if (up) c.increaseDeviceVolume(0) else c.decreaseDeviceVolume(0)
        return true
    }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    // Off, then the whole queue, then just this song.
    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun next() = withController { it.seekToNext() }

    fun previous() = withController { it.seekToPrevious() }

    // Moves from the "Up next" list, by position in the window. Taking songs
    // out goes through QueueEditor, by each song's key.
    fun moveInQueue(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }

    fun playAt(index: Int) = withController { c ->
        c.seekToDefaultPosition(index)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    // Plays an entry anywhere in the queue, past the window too.
    fun playEntry(key: String) = withController { c ->
        entryOfKey(key)?.let { c.sendCustomCommand(PLAY_ENTRY, Bundle().apply { putLong(ARG_ENTRY, it) }) }
    }

    // Plays the entry on (by its key) again from its start as a new play,
    // once a version was picked for its song. Nothing happens when another
    // entry is on by the time it arrives.
    fun restartFresh(entryKey: String) = withController { c ->
        c.sendCustomCommand(RESTART_FRESH, Bundle().apply { putString(ARG_ENTRY_KEY, entryKey) })
    }

    // ---- Up next past the songs the player holds ----

    // Up to `limit` of the queue's songs after (or before) the entry
    // `fromKey` in the order that plays, read from Room, nearest first.
    suspend fun queueRows(fromKey: String, forward: Boolean, limit: Int): List<QueueEntry> {
        val queueId = queue.inUse.value
        val entry = entryOfKey(fromKey) ?: return emptyList()
        if (queueId == 0L) return emptyList()
        return queue.inTurn {
            val row = queue.dao.entry(queueId, entry) ?: return@inTurn emptyList()
            val account = queue.dao.accountOn(queueId)
            val order = if (account?.shuffle == true) QueueOrder.Shuffle else QueueOrder.Play
            queue.page(queueId, order, row, forward, limit, Floors.of(account)).mapNotNull { r ->
                val t = queue.dao.track(r.trackId) ?: return@mapNotNull null
                QueueEntry(entryKey(r.entry), -1, t.id, t.title, t.artist, t.artwork, if (r.durationMs > 0) r.durationMs else t.durationMs, t.explicit == true)
            }
        }
    }

    // How many songs come after the entry `fromKey` in the order that plays,
    // and how long they play.
    suspend fun queueTail(fromKey: String): Pair<Int, Long> {
        val queueId = queue.inUse.value
        val entry = entryOfKey(fromKey) ?: return 0 to 0L
        if (queueId == 0L) return 0 to 0L
        return queue.inTurn {
            val row = queue.dao.entry(queueId, entry) ?: return@inTurn 0 to 0L
            val account = queue.dao.accountOn(queueId)
            val order = if (account?.shuffle == true) QueueOrder.Shuffle else QueueOrder.Play
            queue.tail(queueId, order, row, Floors.of(account))
        }
    }

    // ---- Playing a list ----

    // Plays a list named by what it is (`spec`), from `start` (a tapped
    // row, a song the page knows comes first, a place, or the list's first
    // row), or shuffled, in place of the queue. Every play of a list goes
    // through here; the tap only posts. The starting state shows at once
    // with `shown` (what the screen holds of the row or the list) until the
    // first song is the player's, the play is refused (its words show,
    // unless `handlesRefusal`: the route shows its own fallback), or it
    // fails to reach the service. `answered` hears how the play went: a
    // server folder's says whether its songs stopped at their limit, and a
    // refusal its words.
    open fun playList(
        spec: ListSpec,
        start: ListStart? = null,
        shuffle: Boolean = false,
        source: String? = null,
        playWhenReady: Boolean = true,
        pageOwner: String? = null,
        shownOverWindow: Boolean = false,
        handlesRefusal: Boolean = false,
        shown: Shown? = null,
        word: String? = null,
        answered: ((PlayAnswer) -> Unit)? = null,
    ) {
        scope.launch {
            val token = withContext(Dispatchers.IO) { queue.claimList() }
            startingPlays.start(StartingPlay(token, shown?.title, shown?.artist, shown?.artwork))
            val args = playListArgs(spec, start, shuffle, source, playWhenReady, pageOwner, shownOverWindow, token, word)
            withController { c ->
                val result = c.sendCustomCommand(PLAY_LIST, args)
                result.addListener(
                    {
                        val answer = runCatching { result.get() }.getOrNull()
                        when {
                            answer == null || answer.resultCode != SessionResult.RESULT_SUCCESS -> failed(token, START_FAILED_WORDS, handlesRefusal)
                            answer.extras.getString(ARG_REFUSAL) != null -> failed(token, answer.extras.getString(ARG_REFUSAL), handlesRefusal)
                            !answer.extras.getBoolean(ARG_STARTED, false) -> startingPlays.end(token)
                        }
                        answered?.invoke(
                            if (answer == null || answer.resultCode != SessionResult.RESULT_SUCCESS) {
                                PlayAnswer(false, refusal = START_FAILED_WORDS)
                            } else {
                                PlayAnswer(answer.extras.getBoolean(ARG_STARTED, false), answer.extras.getBoolean(ARG_CUT, false), answer.extras.getString(ARG_REFUSAL))
                            },
                        )
                    },
                    ContextCompat.getMainExecutor(context),
                )
            }
        }
    }

    // Puts a list next, or at the end of the queue before Autoplay's songs,
    // then hands `inserted` how many went in and a way to take them back
    // out. A list too big to put in whole is added as it is written; the
    // count is then what it held when added.
    fun addList(
        spec: ListSpec,
        next: Boolean,
        source: String? = null,
        inserted: (count: Int, undo: () -> Unit) -> Unit = { _, _ -> },
        shownOverWindow: Boolean = false,
    ) = withController { c ->
        val result = c.sendCustomCommand(if (next) ADD_NEXT else ADD_LAST, addListArgs(spec, source, shownOverWindow))
        result.addListener(
            {
                val answer = runCatching { result.get() }.getOrNull() ?: return@addListener
                if (answer.resultCode != SessionResult.RESULT_SUCCESS) return@addListener
                val listId = answer.extras.getLong(ARG_LIST, 0)
                val count = answer.extras.getInt(ARG_COUNT, 0)
                if (listId == 0L && count == 0) return@addListener
                inserted(count) {
                    withController { it.sendCustomCommand(REMOVE_ENTRIES, Bundle().apply { putLong(ARG_LIST, listId) }) }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun withController(action: (MediaController) -> Unit) {
        val ready = controller
        if (ready != null) action(ready) else {
            waiting += action
            connect()
        }
    }

    private val liveClock = LiveClock(SystemClock::elapsedRealtime)

    private fun publish(player: Player) {
        val item: MediaItem? = player.currentMediaItem
        val meta = item?.mediaMetadata
        val station = item?.takeIf { isRadio(it.mediaId) }
        val since = liveClock.heard(station?.let { it.entryId ?: it.mediaId }, player.isPlaying, player.playWhenReady)
        val live = station?.let {
            liveNowOf(it.mediaId, meta?.icyRead(), List(player.mediaItemCount) { i -> player.getMediaItemAt(i).mediaId }, player.currentMediaItemIndex, since)
        }
        _now.value = NowPlaying(
            trackId = item?.mediaId,
            entryId = item?.entryId,
            title = meta?.title?.toString(),
            artist = meta?.artist?.toString(),
            artwork = meta?.artworkRef(),
            album = meta?.albumTitle?.toString(),
            albumId = meta?.extra(EXTRA_ALBUM_ID),
            artistId = meta?.extra(EXTRA_ARTIST_ID),
            quality = audioQuality(meta?.extra(EXTRA_MIME), player.currentTracks)
                ?.playingAt(if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) null else outputRate()),
            isPlaying = player.isPlaying,
            durationMs = songLengthMs(listedLengthMs(item), player.duration),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            casting = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE,
            deviceVolume = deviceVolumeOf(player),
            explicit = meta?.isExplicit() == true,
            playWhenReady = player.playWhenReady,
            live = live,
        )
    }

    private fun deviceVolumeOf(player: Player): Float? {
        val info = player.deviceInfo
        if (info.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE || !player.isCommandAvailable(Player.COMMAND_GET_DEVICE_VOLUME)) return null
        val span = (info.maxVolume - info.minVolume).coerceAtLeast(1)
        return ((player.deviceVolume - info.minVolume).toFloat() / span).coerceIn(0f, 1f)
    }

    private fun publishQueue(player: Player) {
        val timeline = player.currentTimeline
        val shuffle = player.shuffleModeEnabled
        val items = List(player.mediaItemCount, player::getMediaItemAt)
        val trackIds = items.map { it.mediaId }
        val entryIds = items.map { it.entryId }
        val current = player.currentMediaItemIndex
        fun entry(slot: QueueSlot): QueueEntry {
            val item = items[slot.index]
            val meta = item.mediaMetadata
            return QueueEntry(
                key = slot.key,
                index = slot.index,
                trackId = trackIds[slot.index],
                title = meta.title?.toString().orEmpty(),
                artist = meta.artist?.toString().orEmpty(),
                artwork = meta.artworkRef(),
                durationMs = listedLengthMs(item),
                explicit = meta.isExplicit(),
                autoplay = item.isAutoplay,
                source = item.queueSource,
            )
        }
        _upNext.value = upNextOrder(trackIds, current, entryIds) {
            timeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, shuffle)
        }.map(::entry)
        _played.value = playedOrder(trackIds, current, entryIds) {
            timeline.getPreviousWindowIndex(it, Player.REPEAT_MODE_OFF, shuffle)
        }.map(::entry)
    }

    // A song's listed length, corrected where playing it found it wrong.
    private fun listedLengthMs(item: MediaItem?): Long =
        item?.let { lengths.lengthMs(it.mediaId, it.mediaMetadata.durationMs ?: 0) } ?: 0

    // The rate the phone's audio output mixes at, which Android resamples
    // songs to unless it plays them bit for bit.
    private fun outputRate(): Int? =
        context.getSystemService(AudioManager::class.java)?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
}

// At most QUEUE_WINDOW of a list of songs, those around `startIndex`, and
// where that one is among them.
fun idsAround(trackIds: List<String>, startIndex: Int): Pair<List<String>, Int> {
    val start = startIndex.coerceIn(0, (trackIds.size - 1).coerceAtLeast(0))
    if (trackIds.size <= QUEUE_WINDOW) return trackIds to start
    val from = (start - QUEUE_WINDOW / 2).coerceIn(0, trackIds.size - QUEUE_WINDOW)
    return trackIds.subList(from, from + QUEUE_WINDOW) to start - from
}

// Where songs added to the end of the queue go: before the first Autoplay
// song still to come in play order, otherwise at the very end.
internal fun endOfOwnSongs(player: Player): Int {
    val count = player.mediaItemCount
    return ownSongsAt(count, upcoming(player), List(count) { player.getMediaItemAt(it).isAutoplay })
}
