package app.winters.octo.player

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.animated.AnimatedArtSetting
import app.winters.octo.animated.AnimatedArts
import app.winters.octo.animated.FULL_SCREEN_BACKGROUND_GAIN
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.isFind
import app.winters.octo.discovery.asTrack
import app.winters.octo.lyrics.Lyrics
import app.winters.octo.lyrics.LyricsAnswer
import app.winters.octo.lyrics.LyricsChoices
import app.winters.octo.lyrics.LyricsRepository
import app.winters.octo.lyrics.LyricsSong
import app.winters.octo.lyrics.NetworkWatch
import app.winters.octo.playback.DeviceVolume
import app.winters.octo.playback.FindMap
import app.winters.octo.playback.LikeStore
import app.winters.octo.playback.NowPlaying
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.playback.QueueEditor
import app.winters.octo.playback.QueueEntry
import app.winters.octo.playback.SleepState
import app.winters.octo.playback.SleepTimer
import app.winters.octo.playback.isRadio
import app.winters.octo.player.immersive.BackgroundMode
import app.winters.octo.player.immersive.WashArtwork
import app.winters.octo.player.immersive.drawnMode
import app.winters.octo.player.immersive.drawnRange
import app.winters.octo.player.immersive.over
import app.winters.octo.subsonic.AnimatedArt
import app.winters.octo.ui.common.Feedback
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

// Everything the full player shows, and what its buttons do.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playback: PlaybackConnection,
    palette: ArtworkPalette,
    private val likes: LikeStore,
    catalog: CatalogDao,
    online: OnlineDao,
    settings: PlayerSettings,
    private val deviceVolume: DeviceVolume,
    private val sleepTimer: SleepTimer,
    private val lyricsRepository: LyricsRepository,
    private val lyricsChoices: LyricsChoices,
    private val networkWatch: NetworkWatch,
    private val editor: QueueEditor,
    private val queueStore: app.winters.octo.playback.QueueStore,
    private val playlists: PlaylistStore,
    private val feedback: Feedback,
    private val artwork: WashArtwork,
    animatedArts: AnimatedArts,
    // Finds a start plays before their rows are written.
    finds: FindMap,
) : ViewModel() {
    val now: StateFlow<NowPlaying> = playback.now
    val upNext: StateFlow<List<QueueEntry>> = playback.upNext
    val played: StateFlow<List<QueueEntry>> = playback.played

    // A play just asked for, shown at once until its first song plays.
    val starting: StateFlow<app.winters.octo.playback.StartingPlay?> = playback.starting

    // Lists still being written into the queue, each a quiet row in Up next.
    val building: StateFlow<List<app.winters.octo.playback.BuildingRow>> = playback.building

    // Up next past the songs the player holds, read from the queue a page
    // at a time as the list reaches them: the songs after its last one (the
    // tail, with how many there are and how long they play) and before its
    // first (the head). Read again whenever the player's window moves.
    private val _tail = MutableStateFlow<List<QueueEntry>>(emptyList())
    val tail: StateFlow<List<QueueEntry>> = _tail
    private val _head = MutableStateFlow<List<QueueEntry>>(emptyList())
    val head: StateFlow<List<QueueEntry>> = _head
    private val _tailCount = MutableStateFlow(0 to 0L)
    val tailCount: StateFlow<Pair<Int, Long>> = _tailCount
    private var tailDone = false
    private var headDone = false
    private var reading = false

    init {
        viewModelScope.launch {
            combine(upNext, played) { u, p -> u.lastOrNull()?.key to (p.firstOrNull() ?: u.firstOrNull())?.key }
                .distinctUntilChanged()
                .collectLatest { (last, first) ->
                    _tail.value = emptyList()
                    _head.value = emptyList()
                    tailDone = last == null
                    headDone = first == null
                    _tailCount.value = last?.let { playback.queueTail(it) } ?: (0 to 0L)
                    if (_tailCount.value.first == 0) tailDone = true
                }
        }
    }

    // The next page of the tail, once the list shows its end.
    fun moreTail() {
        if (tailDone || reading) return
        val from = (_tail.value.lastOrNull() ?: upNext.value.lastOrNull())?.key ?: return
        reading = true
        viewModelScope.launch {
            try {
                val page = playback.queueRows(from, forward = true, limit = QUEUE_SHEET_PAGE)
                val shown = upNext.value.mapTo(HashSet()) { it.key } + _tail.value.map { it.key }
                _tail.value = _tail.value + page.filterNot { it.key in shown }
                if (page.size < QUEUE_SHEET_PAGE) tailDone = true
            } finally {
                reading = false
            }
        }
    }

    // The page of the head before what the list shows, once it shows its top.
    fun moreHead() {
        if (headDone || reading) return
        val from = (_head.value.firstOrNull() ?: played.value.firstOrNull() ?: upNext.value.firstOrNull())?.key ?: return
        reading = true
        viewModelScope.launch {
            try {
                val page = playback.queueRows(from, forward = false, limit = QUEUE_SHEET_PAGE)
                val shown = played.value.mapTo(HashSet()) { it.key } + _head.value.map { it.key }
                _head.value = page.filterNot { it.key in shown }.reversed() + _head.value
                if (page.size < QUEUE_SHEET_PAGE) headDone = true
            } finally {
                reading = false
            }
        }
    }

    // A song past the window plays: the queue's window moves to it.
    fun playEntry(entry: QueueEntry) = playback.playEntry(entry.key)

    // The album name for the top of the player, or nothing for a single. A
    // song found online has no album in the library to count, so it shows none.
    val albumLabel: StateFlow<String?> = playback.now
        .map { Triple(it.albumId, it.album, it.title) }
        .distinctUntilChanged()
        .flatMapLatest { (albumId, album, title) ->
            if (albumId.isNullOrEmpty()) {
                flowOf(null)
            } else {
                catalog.album(albumId).map { albumLabel(album, title, it?.songCount ?: 0) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Whether the song on now is in Liked songs.
    val liked: StateFlow<Boolean> = playback.now
        .map { it.trackId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(false) else likes.likedFlow(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // A song found online has no heart (LikeStore.toggle does nothing for
    // one); a song the server owns that the phone lacks does.
    fun toggleLike() {
        playback.now.value.trackId?.let(likes::toggle)
    }

    // The song on now when it was found online, for the download button
    // that stands where the heart would be.
    val find: StateFlow<TrackEntity?> = playback.now
        .map { it.trackId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id != null && isFind(id)) online.songFlow(id).map { (it ?: finds[id])?.asTrack() } else flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val prefs: StateFlow<PlayerPrefs> = settings.prefs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerPrefs())

    // Colours follow the artwork, not the song, so an album plays through
    // without the background flickering. `content` is the colour for the
    // player's words and icons, decided from what the chosen background
    // draws (the prepared cover, shared with the background), and `show`
    // how far the background dims for them.
    // Under a tall loop in Full screen the background is drawn brighter
    // (FULL_SCREEN_BACKGROUND_GAIN), so `brightColors` decides the words'
    // color and dimming for that, the same way.
    private val drawn = combine(
        playback.now.map { it.artwork }.distinctUntilChanged(),
        settings.prefs.map { it.background to it.liveBackground }.distinctUntilChanged(),
    ) { ref, prefs -> ref to prefs }
        .mapLatest { (ref, prefs) ->
            val (background, live) = prefs
            val colors = palette.colorsFor(ref)
            val mode = drawnMode(background.mode, Build.VERSION.SDK_INT)
            val cover = if (mode == BackgroundMode.Default || mode == BackgroundMode.Artwork) {
                runCatching { artwork.prepare(ref, colors, background.tuning).range }.getOrNull()
            } else {
                null
            }
            colors to drawnRange(mode, colors, background, cover, live && LiveBackgroundSupported)
        }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val colors: StateFlow<PlayerColors> = drawn
        .map { (colors, range) -> colors.over(range) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerColors.Quiet)

    val brightColors: StateFlow<PlayerColors> = drawn
        .map { (colors, range) -> colors.over(range?.scaled(FULL_SCREEN_BACKGROUND_GAIN)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerColors.Quiet)

    // The animated art of the song's album, from its server, while the
    // setting has it on; none for a live station.
    val animatedArt: StateFlow<AnimatedArt?> = settings.prefs
        .map { it.animatedArt != AnimatedArtSetting.Off }
        .distinctUntilChanged()
        .flatMapLatest { on ->
            if (!on) {
                flowOf(null)
            } else {
                playback.now
                    .map { now -> now.trackId.takeIf { now.live == null } }
                    .distinctUntilChanged()
                    // The song's album, or null with no song on.
                    .mapLatest { id -> id?.let { AlbumOnServer(animatedArts.serverAlbumOf(it)) } }
                    .distinctUntilChanged()
                    .flatMapLatest { album -> if (album == null) flowOf(null) else animatedArts.artOf(album.id) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun positionMs() = playback.positionMs()
    fun seekTo(positionMs: Long) = playback.seekTo(positionMs)
    fun togglePlayPause() = playback.togglePlayPause()
    fun next() = playback.next()
    fun previous() = playback.previous()
    fun toggleShuffle() = playback.toggleShuffle()
    fun cycleRepeat() = playback.cycleRepeat()
    // The phone's volume, or while casting the TV or speaker's.
    val volume: StateFlow<Float> = combine(deviceVolume.level, playback.now) { phone, now -> now.deviceVolume ?: phone }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), deviceVolume.level.value)

    fun setVolume(fraction: Float) = if (playback.now.value.casting) playback.setDeviceVolume(fraction) else deviceVolume.set(fraction)
    fun moveInQueue(from: Int, to: Int) = playback.moveInQueue(from, to)
    fun playAt(index: Int) = playback.playAt(index)

    // Queue edits go by each song's key, so a quick second swipe never
    // takes out the wrong song. Those that take songs out can be undone.
    fun removeFromQueue(entry: QueueEntry) {
        // A song past the window leaves the rows read so far at once.
        if (_tail.value.any { it.key == entry.key }) {
            _tail.value = _tail.value.filterNot { it.key == entry.key }
            _tailCount.value = (_tailCount.value.first - 1).coerceAtLeast(0) to (_tailCount.value.second - entry.durationMs).coerceAtLeast(0)
        }
        editor.remove(entry.key) { undo -> feedback.undoable("Removed from the queue") { editor.undo(undo) } }
    }

    // Everything but the song that is on.
    fun clearQueue() {
        editor.clear { undo -> feedback.undoable("Queue cleared") { editor.undo(undo) } }
    }

    // Once its Undo is no longer offered, the played songs' rows go for good.
    fun removePlayed() {
        editor.removePlayed { undo ->
            feedback.undoable("Played songs removed") { editor.undo(undo) }
            val shown = feedback.message.value
            editor.expireFloorsWhen { feedback.message.first { it !== shown } }
        }
    }

    fun playNextInQueue(entry: QueueEntry) = editor.playNext(entry.key)

    // The listener's queue as a playlist: what played, the song on now and
    // what is still to come, without the songs Autoplay added, a page at a
    // time from the whole queue.
    fun saveQueueAsPlaylist(name: String) {
        viewModelScope.launch {
            playlists.createFrom(name) { emit -> queueStore.savePages(emit) }
            feedback.show("Saved as ${name.trim()}")
        }
    }

    val sleep: StateFlow<SleepState> = sleepTimer.state
    fun sleepIn(minutes: Int) = sleepTimer.start(minutes)
    fun sleepAtEndOfSong() = sleepTimer.startEndOfSong()
    fun sleepAfterSongs(count: Int) = sleepTimer.startAfterSongs(count)
    fun extendSleep(minutes: Int) = sleepTimer.extend(minutes)
    fun cancelSleep() = sleepTimer.cancel()

    // From a queue row's menu: the music stops once that song has played.
    fun sleepAfter(entry: QueueEntry) {
        sleepTimer.startAfter(entry.key, entry.title)
        feedback.undoable("Stops after ${entry.title}") { sleepTimer.cancel() }
    }

    // The rating of the song on now, 0 when it has none or was found online.
    val rating: StateFlow<Int> = playback.now
        .map { it.trackId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null || isFind(id)) flowOf(0) else catalog.trackFlow(id).map { it?.rating ?: 0 }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // Whether the lyrics show in place of the artwork. They are only looked
    // up while they show, and follow the song while they do.
    private val lyricsShown = MutableStateFlow(false)
    val lyricsOpen: StateFlow<Boolean> = lyricsShown
    fun toggleLyrics() {
        lyricsShown.value = !lyricsShown.value
    }

    // What the lyrics view shows. They follow the listener's choice for the
    // song too: picking other lyrics shows them at once, and hiding them
    // shows that they are hidden. A choice sent to the server, or a retry,
    // fetches them again at once. See lyricsStates for when they are
    // looked up.
    private val lyricsState = MutableStateFlow<LyricsState>(LyricsState.Hidden)
    val lyrics: StateFlow<LyricsState> = lyricsState.asStateFlow()

    // Whether the lyrics view is on screen: something shows this model's
    // lyrics. A short gap (turning the phone) does not count as leaving.
    private val lyricsWatched: Flow<Boolean> = lyricsState.subscriptionCount
        .map { it > 0 }
        .distinctUntilChanged()
        .transformLatest { on ->
            if (!on) delay(LYRICS_LEAVE_AFTER_MS)
            emit(on)
        }
        .onStart { emit(false) }
        .distinctUntilChanged()

    // Whether nothing shows the lyrics right now, for what a lookup saves.
    private fun lyricsAway() = lyricsState.subscriptionCount.value == 0

    // The song on now while lyrics are open.
    private val lyricsSong: Flow<String?> =
        combine(playback.now.map { it.trackId }.distinctUntilChanged(), lyricsShown) { id, open -> id.takeIf { open } }
            .distinctUntilChanged()

    init {
        viewModelScope.launch {
            val wanted = lyricsSong.flatMapLatest { id ->
                if (id == null) {
                    flowOf(null)
                } else {
                    combine(lyricsChoices.choiceFor(id), lyricsRepository.revisionOf(id)) { choice, revision ->
                        LyricsWanted(id, choice.hidden, choice.pick to revision)
                    }
                }
            }
            lyricsStates(wanted, lyricsWatched, ::lookUpLyrics, networkWatch::awaitChange).collect { lyricsState.value = it }
        }
    }

    private suspend fun lookUpLyrics(id: String, resumed: Boolean): LyricsAnswer {
        val now = playback.now.value.takeIf { it.trackId == id }
        val song = LyricsSong(id, now?.title.orEmpty(), now?.artist.orEmpty(), now?.album.orEmpty(), now?.durationMs ?: 0L)
        return lyricsRepository.answerFor(song, resumed, ::lyricsAway)
    }

    // While lyrics show on screen, the songs either side of this one have
    // theirs looked up once the song has settled, so skipping shows them at
    // once. A skip before then starts the wait again, so skipping quickly
    // through the queue asks for nothing.
    init {
        viewModelScope.launch {
            combine(lyricsSong, lyricsWatched) { id, on -> id.takeIf { on } }
                .distinctUntilChanged()
                .collectLatest { id ->
                    if (id == null) return@collectLatest
                    delay(LYRICS_PREFETCH_SETTLE_MS)
                    listOfNotNull(upNext.value.getOrNull(1), played.value.lastOrNull())
                        .filterNot { isRadio(it.trackId) || it.trackId == id }
                        .forEach { entry ->
                            // A neighbour's lyrics are only a head start; a lookup that
                            // fails saves nothing, and it is asked again when it plays.
                            lyricsRepository.answerFor(
                                LyricsSong(entry.trackId, entry.title, entry.artist, "", entry.durationMs),
                                away = ::lyricsAway,
                            )
                        }
                }
        }
    }
}

// A song's album by its id on the server; null when the server has none.
private data class AlbumOnServer(val id: String?)

// How long the lyrics view may be gone before it counts as off screen.
// How many of the queue's songs past the player's window Up next reads at a time.
private const val QUEUE_SHEET_PAGE = 50

private const val LYRICS_LEAVE_AFTER_MS = 5_000L

// How long a song must stay on before its neighbours' lyrics are looked up.
private const val LYRICS_PREFETCH_SETTLE_MS = 1_200L

// What the lyrics view shows.
sealed interface LyricsState {
    data object Hidden : LyricsState
    // The listener hid this song's lyrics.
    data object HiddenForSong : LyricsState
    data object Loading : LyricsState
    data object None : LyricsState
    // The lookup could not finish (no network, the server did not answer):
    // not "no lyrics", and worth another try.
    data class Failed(val trackId: String) : LyricsState
    data class Found(val trackId: String, val lyrics: Lyrics) : LyricsState
}
