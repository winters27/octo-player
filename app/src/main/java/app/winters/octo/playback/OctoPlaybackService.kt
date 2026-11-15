package app.winters.octo.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.winters.octo.MainActivity
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.UpgradeGuard
import app.winters.octo.catalog.UpgradeState
import app.winters.octo.catalog.isFind
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.data.accountId
import app.winters.octo.design.OctoIcons
import app.winters.octo.discovery.Downloads
import app.winters.octo.discovery.FindLengths
import app.winters.octo.discovery.OwnedFinds
import app.winters.octo.discovery.asTrack
import app.winters.octo.liveradio.LiveLookup
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.offline.Prefetcher
import app.winters.octo.output.Casting
import app.winters.octo.output.DeviceMedia
import app.winters.octo.output.OutputSwitch
import app.winters.octo.output.Outputs
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.player.PlayerSettings
import app.winters.octo.server.PHONE_LIBRARY
import app.winters.octo.server.QueueSync
import app.winters.octo.server.ServerSync
import app.winters.octo.sound.AlbumRun
import app.winters.octo.sound.AudioSession
import app.winters.octo.sound.DeckMixer
import app.winters.octo.sound.DeckMixers
import app.winters.octo.sound.DeckSound
import app.winters.octo.sound.OctoRenderersFactory
import app.winters.octo.sound.SoundEngine
import app.winters.octo.ui.common.Feedback
import app.winters.octo.widget.QuickPicks
import app.winters.octo.widget.WidgetRemote
import app.winters.octo.widget.WidgetUpdates
import app.winters.octo.widget.widgetCommand
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

// The like toggle offered in the notification and on the lock screen, the
// download that stands in for it on a song found online, and Close.
private val LIKE = SessionCommand("app.winters.octo.LIKE", Bundle.EMPTY)
private val DOWNLOAD = SessionCommand("app.winters.octo.DOWNLOAD", Bundle.EMPTY)
private val CLOSE = SessionCommand("app.winters.octo.CLOSE", Bundle.EMPTY)
private val STOP = SessionCommand("app.winters.octo.STOP", Bundle.EMPTY)

// A song's actions in a car: Start radio and Play next, by the song's id.
private val START_RADIO = SessionCommand(START_RADIO_ACTION, Bundle.EMPTY)
private val PLAY_NEXT_ITEM = SessionCommand(PLAY_NEXT_ITEM_ACTION, Bundle.EMPTY)

// Where a car's browse action reads the words it shows for its result.
private const val ACTION_MESSAGE = "androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_RESULT_MESSAGE"

// "Play X on Octo" from the app, with the words said and what the
// assistant picked out of them.
internal val PLAY_FROM_SEARCH = SessionCommand("app.winters.octo.PLAY_FROM_SEARCH", Bundle.EMPTY)
private const val ARG_QUERY = "query"
private const val ARG_EXTRAS = "extras"

// Whether the decks are set up for blending when the service starts, so
// the first song already feeds its live tap: whenever songs may blend.
internal fun blendsAtStart(prefs: PlayerPrefs): Boolean = prefs.crossfadeMs > 0

internal fun playFromSearchArgs(query: String, extras: Bundle?) = Bundle().apply {
    putString(ARG_QUERY, query)
    extras?.let { putBundle(ARG_EXTRAS, Bundle(it)) }
}

// Apps that drive playback from a car or by voice: Android Auto and the
// assistant. They get full control even when not system apps.
private val carApps = setOf(
    "com.google.android.projection.gearhead",
    "com.google.android.carassistant",
    "com.google.android.googlequicksearchbox",
)

// Apps that browse the music to show it in a car, and so ask the server for
// its shelves when they connect: the car apps and Bluetooth (a car stereo
// that browses over Bluetooth). Bluetooth gets no more control than Media3
// gives it.
private val carPackages = carApps + "com.android.bluetooth"

// Plays music with the screen off, and answers the lock screen, the
// notification, headphone buttons and the app.
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class OctoPlaybackService : MediaLibraryService() {
    @Inject lateinit var playable: PlayableSongs
    @Inject lateinit var streams: Streams
    @Inject lateinit var likes: LikeStore
    @Inject lateinit var queue: QueueStore
    @Inject lateinit var legacy: LegacyQueues
    @Inject lateinit var plays: PlayStore
    @Inject lateinit var sleep: SleepTimer
    @Inject lateinit var playerSettings: PlayerSettings
    @Inject lateinit var car: CarLibrary
    @Inject lateinit var sound: SoundEngine
    @Inject lateinit var audioSession: AudioSession
    @Inject lateinit var serverQueue: QueueSync
    @Inject lateinit var quickPicks: QuickPicks
    @Inject lateinit var prefetch: Prefetcher
    @Inject lateinit var coversAhead: CoversAhead
    @Inject lateinit var editor: QueueEditor
    @Inject lateinit var autoplay: Autoplay
    @Inject lateinit var stationRefill: StationRefill
    @Inject lateinit var downloads: Downloads
    @Inject lateinit var owned: OwnedFinds
    @Inject lateinit var online: OnlineDao
    @Inject lateinit var lengths: FindLengths
    @Inject lateinit var realLengths: RealLengths
    @Inject lateinit var outputs: Outputs
    @Inject lateinit var deviceMedia: DeviceMedia
    @Inject lateinit var feedback: Feedback
    @Inject lateinit var offlineDownloads: OfflineDownloads
    @Inject lateinit var switcher: ServerSwitch
    @Inject lateinit var sessions: SessionRepository
    @Inject lateinit var security: app.winters.octo.connection.ConnectionSecurity
    @Inject lateinit var liveLookup: LiveLookup
    @Inject lateinit var carShelves: CarShelves
    @Inject lateinit var carSearch: CarSearch
    @Inject lateinit var carVoice: CarVoice
    @Inject lateinit var upgradeGuard: UpgradeGuard
    @Inject lateinit var carResume: CarResume
    @Inject lateinit var serverSync: ServerSync
    @Inject lateinit var builds: QueueBuilds
    @Inject lateinit var starts: QueueStarts
    @Inject lateinit var startingPlays: StartingPlays
    @Inject lateinit var merge: app.winters.octo.catalog.CatalogMerge
    @Inject lateinit var finds: FindMap
    @Inject lateinit var drops: VersionDrops

    private val scope = MainScope()
    // What each car said it can show as tabs, kept from its root call.
    private val rootLimits = ConcurrentHashMap<MediaSession.ControllerInfo, Int>()

    // Tells cars of changes to the lists they have open; on the main thread.
    private val notifier = CarNotifier { id ->
        val open = session ?: return@CarNotifier false
        if (open.getSubscribedControllers(id).isEmpty()) return@CarNotifier false
        open.notifyChildrenChanged(id, Int.MAX_VALUE, null)
        true
    }

    // Drops a play that waited on the server once something newer replaced
    // the queue, or another account is in use.
    private val guard = PlayGuard({ player.replacements }, { sessions.state.value.accountId })
    // While the server in use changes, the emptied player is not saved over
    // the queue handed over.
    private var holding = false
    private val side = Side()
    // Whether crossfade is on, read by the decks' audio path.
    private val crossfadeOn = AtomicBoolean(false)
    // The phone's own player (two decks for crossfade), and the player the
    // session holds, which is the phone's or, while casting, the one that
    // drives the TV or speaker.
    private lateinit var local: OctoPlayer
    // The one mix output both decks play into, released after them.
    private var mix: DeckMixer? = null
    private lateinit var player: OutputSwitch
    // The queue, kept whole in Room while the player holds a window of it.
    private lateinit var window: QueueWindow
    private lateinit var casting: Casting
    private lateinit var tracker: PlayTracker
    // The server's transition profiles of songs, kept for the Crossfader.
    private lateinit var profiles: ServerProfiles
    // The queue entry last opened again for another file from the server.
    private var reopened: String? = null
    private lateinit var headsets: HeadsetResume
    private var session: MediaLibrarySession? = null
    private val currentId = MutableStateFlow<String?>(null)

    // Whether a station is on air, so the phone's media controls show Stop
    // where a song shows Pause.
    private val stationOnAir = MutableStateFlow(false)
    private val queueChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var positionSaver: Job? = null
    // Done once last session's queue is back, or there was none.
    private val restored = CompletableDeferred<Unit>()
    // Set by Close, until something plays again.
    private var closed = false
    // A song paused on a changed server certificate, until it is answered.
    private var waitingOnCertificate = false
    // The home screen widgets: what they show, and what their buttons do.
    private val widgets by lazy { WidgetUpdates(this, scope) }
    private val widgetRemote by lazy { WidgetRemote(this, player, quickPicks) { spec, shuffle -> window.play(PlayRequest(spec, null, shuffle)) } }

    // The account whose queue is in use: the signed-in server's, else the
    // phone library's.
    private fun accountInUse(): String = sessions.state.value.accountId ?: PHONE_LIBRARY

    @kotlin.OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        // Both decks shape their sound from the same settings, and share one
        // memory of the last album for Smart ReplayGain, since a crossfade
        // hands the next song to the other deck.
        val albums = AlbumRun()
        // Each deck's sound processor, read for what a song sounds like.
        val sounds = HashMap<ExoPlayer, DeckSound>()
        // Each deck's buffering, told how long a stream waits before it starts.
        val loads = ArrayList<OctoLoadControl>()
        // Both decks play into one mix output, the only audio track: it sums
        // them, shapes the blends on the frame and limits the sum. Made
        // before the decks, its events reach the player through `relay`.
        val relay = MixRelay()
        val mixer = DeckMixers.create(this, deckAudioAttributes(), audioSession.id, relay).also { mix = it }
        fun deck(slot: Int): ExoPlayer {
            val renderers = OctoRenderersFactory(this, { sound.playing.value }, albums, crossfadeOn::get, mixer, slot)
            val control = OctoLoadControl().also(loads::add)
            return buildDeck(this, streams.mediaSourceFactory(), renderers, audioSession.id, control)
                .also { deck -> renderers.sound?.let { sounds[deck] = it } }
        }
        val scout = Scout(streams.scoutSourceFactory(), streams.extractors())
        profiles = ServerProfiles(scope) { (sessions.state.value as? SessionState.SignedIn)?.session }
        local = OctoPlayer(this, deck(0), deck(1), sounds::get, scout, profiles, realLengths, mixer)
        relay.to = local.mixListener
        // A blend's start and end change what plays, for the version drops.
        local.onPlayingChanged = ::tellPlaying
        // A song whose version changed asks the server for its profile again.
        scope.launch { drops.dropped.collect { ids -> profiles.forget(ids) } }
        player = OutputSwitch(local) { if (::window.isInitialized) window else null }
        window = QueueWindow(
            player = player,
            inner = { player.active },
            store = queue,
            fill = builds,
            starts = starts,
            items = playable::itemsEach,
            account = ::accountInUse,
            guard = guard,
            scope = scope,
            say = { feedback.show(it) },
            startingPlays = startingPlays,
            finds = finds,
        )
        editor.attachWindow(window)
        // Each group of a save may have renamed songs the queue holds: those
        // rows follow at once, and the whole queue heals once the save ends.
        scope.launch { merge.published.collect { window.swapped(it) } }
        tracker = PlayTracker(plays::started, plays::record, isPlaying = { player.isPlaying })
        player.addListener(tracker)
        player.addListener(Watcher())
        player.addListener(RadioWatch(player, scope, liveLookup::known) { liveLookup.look(it) })
        sleep.attach(player)
        // Saving songs ahead is for the phone's own playing.
        prefetch.attach(local)
        // The covers of the song on and the next ones, wherever it plays.
        coversAhead.attach(player)
        editor.attach(player)
        stationRefill.attach(player, scope, window) { autoplay.check() }
        autoplay.attach(player, scope, window)
        // Casting to a TV or speaker swaps the session's player to one that
        // drives the device, and back.
        casting = Casting(this, player, local, deviceMedia, feedback, scope)
        outputs.attach(casting)

        session = MediaLibrarySession.Builder(this, player, Callback())
            .setBitmapLoader(CacheBitmapLoader(OctoArtLoader(this, DataSourceBitmapLoader.Builder(this).build())))
            .setCommandButtonsForMediaItems(carSongButtons())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

        scope.launch {
            try {
                // Whether songs blend decides how the decks' sound path is
                // set up, so it is known before anything is loaded.
                crossfadeOn.set(blendsAtStart(playerSettings.prefs.first()))
                restoreQueue()
            } finally {
                restored.complete(Unit)
            }
        }
        // The heart follows the song, the likes list, and for a song found
        // online, whether its download was asked for.
        scope.launch {
            currentId
                .flatMapLatest { id -> (if (id == null) flowOf(false) else likes.likedFlow(id)).map { id to it } }
                .combine(downloads.states) { pair, states -> pair to states }
                .combine(owned.ids) { (pair, states), mine -> notificationHeart(pair.first, pair.second, states, mine) }
                .combine(stationOnAir) { heart, onAir -> heart to onAir }
                .distinctUntilChanged()
                .collect { (heart, onAir) ->
                    mediaControlsFollow(onAir)
                    showButtons(heart, onAir)
                }
        }
        // Crossfade, speed and pitch, and skipping silence follow their
        // settings, each only when it changes, so a blend is not cut short.
        val prefs = playerSettings.prefs.stateIn(scope, SharingStarted.Eagerly, PlayerPrefs())
        // These belong to the phone's own player, and wait there while casting.
        scope.launch {
            prefs.map { it.automix }.distinctUntilChanged().collect {
                crossfadeOn.set(it.maxOverlapMs > 0)
                local.automix = it
            }
        }
        scope.launch {
            prefs.map { it.pace }.distinctUntilChanged().collect { local.setPlaybackParameters(PlaybackParameters(it.speed, it.pitch)) }
        }
        scope.launch { prefs.map { it.skipSilence }.distinctUntilChanged().collect { local.skipSilence = it } }
        scope.launch {
            playerSettings.streamPrefs.map { it.startAfter.ms }.distinctUntilChanged().collect { ms -> loads.forEach { it.startAfterMs = ms } }
        }
        // Headphones connecting can start the music again. With that on, the
        // service stays in the foreground for the whole 30 minutes it waits
        // after a pause, since Android may refuse to bring it back later.
        // It is the phone's player that headphones bring back, never a TV.
        headsets = HeadsetResume(this, local) { prefs.value }.also { it.start() }
        scope.launch {
            prefs.map { it.resumeWired || it.resumeBluetooth }.distinctUntilChanged().collect { on ->
                setForegroundServiceTimeoutMs(if (on) RESUME_WINDOW_MS else DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS)
            }
        }
        // Editing the queue saves it once things settle.
        scope.launch { queueChanged.debounce(500).collect { saveQueue() } }
        // A song that waited on a changed server certificate goes on once
        // it is trusted.
        scope.launch {
            security.trustedChanges.collect {
                if (!waitingOnCertificate) return@collect
                waitingOnCertificate = false
                player.prepare()
                player.play()
            }
        }
        // A list that joined is in the server's copy of the queue too, even
        // when the window around the song playing did not change.
        scope.launch { window.rejoined.collect { queueChangedForServer() } }
        // A car hears of changes to the lists it has open, each list of what
        // it reads; a change to the library alone waits for a copy to end.
        scope.launch { car.inputs.collect { notifier.changed(it) } }
        scope.launch {
            combine(serverSync.syncing, serverSync.copyRun, ::carHoldsLibrary).distinctUntilChanged().collect { notifier.syncing(it) }
        }
        car.listChanged = { id -> scope.launch { notifier.changedNode(id) } }
        // A switch of server stops the music and brings the other's queue.
        switcher.attach(side)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    // A widget button, once any saved queue is back.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        widgetCommand(intent)?.let { command ->
            // With no app connected yet, the session must be added by hand
            // for its notification to show once music plays.
            session?.let { if (!isSessionAdded(it)) addSession(it) }
            scope.launch {
                restored.await()
                widgetRemote.run(command)
            }
        }
        return result
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveQueue()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        switcher.detach(side)
        drops.nowPlaying(emptySet())
        saveQueue()
        widgets.clear()
        tracker.flush()
        headsets.stop()
        sleep.detach()
        prefetch.detach()
        coversAhead.detach()
        editor.detach()
        window.release()
        startingPlays.clear()
        autoplay.detach()
        stationRefill.detach()
        outputs.detach(casting)
        casting.release()
        session?.release()
        player.release()
        mix?.release()
        mix = null
        audioSession.close()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    // Previous, play and next keep their places. While a station is on air,
    // Stop comes first and the play button is taken away (a station has no
    // pause), so the phone's media controls show Stop beside previous and
    // next. Then the heart (or download), then Close. The phone's own media
    // controls only take extra buttons offered for the overflow slot, and
    // show the first two of those alongside previous, play and next. Apps
    // that know Media3's slots put Stop in the middle, the heart beside next
    // and Close beside previous.
    private fun showButtons(heart: NotificationHeart, onAir: Boolean) {
        val buttons = notificationButtons(heart, onAir).map { button ->
            when (button) {
                NotificationButton.Stop -> CommandButton.Builder(CommandButton.ICON_STOP)
                    .setDisplayName("Stop")
                    .setSessionCommand(STOP)
                    .setSlots(CommandButton.SLOT_CENTRAL, CommandButton.SLOT_OVERFLOW)
                    .build()
                NotificationButton.Like, NotificationButton.Unlike -> {
                    val liked = button == NotificationButton.Unlike
                    CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                        .setDisplayName(if (liked) "Unlike" else "Like")
                        .setSessionCommand(LIKE)
                        .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
                        .build()
                }
                NotificationButton.Download -> CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                    .setCustomIconResId(OctoIcons.AddToLibrary)
                    .setDisplayName("Add to your library")
                    .setSessionCommand(DOWNLOAD)
                    .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
                    .build()
                NotificationButton.Close -> CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                    .setCustomIconResId(OctoIcons.Close)
                    .setDisplayName("Close")
                    .setSessionCommand(CLOSE)
                    .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
                    .build()
            }
        }
        session?.setMediaButtonPreferences(buttons)
    }

    // The phone's media controls (the notification, the lock screen, a car
    // or headset over Bluetooth) speak through the media notification
    // controller. While a station is on air it has no play/pause, so they
    // draw Stop instead of Pause.
    private fun mediaControlsFollow(onAir: Boolean) {
        val session = session ?: return
        val controller = session.mediaNotificationControllerInfo ?: return
        val all = ConnectionResult.DEFAULT_PLAYER_COMMANDS
        val kept = mediaControlCommands((0 until all.size()).map(all::get).toSet(), onAir)
        session.setAvailableCommands(controller, sessionCommands(), Player.Commands.Builder().addAll(*kept.toIntArray()).build())
    }

    // A song's actions in a car that shows them, with the app's own icons.
    private fun carSongButtons(): List<CommandButton> {
        val look = CarLook(this)
        return listOf(
            CommandButton.Builder(CommandButton.ICON_RADIO).setDisplayName("Start radio").setSessionCommand(START_RADIO)
                .setIconUri(look.icon(OctoIcons.Radio)).build(),
            CommandButton.Builder(CommandButton.ICON_QUEUE_NEXT).setDisplayName("Play next").setSessionCommand(PLAY_NEXT_ITEM)
                .setIconUri(look.icon(OctoIcons.PlayNext)).build(),
        )
    }

    // "Start radio" on a song in a car: played only when songs like it are
    // found and nothing newer was chosen meanwhile. The words of a refusal
    // go back to the car to show.
    // The seed plays at once and the songs like it join after it.
    private suspend fun startRadio(id: String?): SessionResult {
        restored.await()
        val song = id?.let(::parseCarId) as? CarNode.Song ?: return actionWords(CAR_NOTHING_TO_PLAY)
        val title = withContext(Dispatchers.IO) { queue.db.dao().track(song.trackId)?.title } ?: return actionWords(CAR_NOTHING_TO_PLAY)
        val answer = window.play(PlayRequest(ListSpec.Radio(song.trackId, "$title radio")))
        answer.refusal?.let { return actionWords(it) }
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    // "Play next" on a song in a car: it goes right after the one playing.
    private suspend fun playNextFromCar(id: String?): SessionResult {
        val song = id?.let(::parseCarId) as? CarNode.Song ?: return actionWords(CAR_NOTHING_TO_PLAY)
        window.addList(ListSpec.Ids(listOf(song.trackId)), next = true, source = null) ?: return actionWords(CAR_NOTHING_TO_PLAY)
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    private fun actionWords(words: String) = SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putString(ACTION_MESSAGE, words) })

    private fun sessionCommands() = ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
        .add(LIKE).add(DOWNLOAD).add(CLOSE).add(STOP).add(PLAY_FROM_SEARCH).add(START_RADIO).add(PLAY_NEXT_ITEM)
        .add(PLAY_LIST).add(ADD_NEXT).add(ADD_LAST).add(PLAY_ENTRY).add(MOVE_ENTRY).add(REMOVE_ENTRIES).add(RESTART_FRESH)
        .build()

    private fun updateStationOnAir() {
        stationOnAir.value = stationOnAir(player.currentMediaItem?.mediaId, player.playWhenReady, player.playbackState)
    }

    // Close: the music stops, the queue is kept for next time, the
    // notification goes, and the service stops once the app lets go of it.
    private fun close() {
        // A play still on its way from the server no longer starts.
        player.markReplaced()
        saveQueue()
        sleep.cancel()
        // Casting ends too, with the music stopped.
        if (player.isRemote) outputs.stopCasting()
        closed = true
        // A stopped player normally keeps its notification; this one goes.
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        player.pause()
        player.stop()
        pauseAllPlayersAndStopSelf()
    }

    // A song can come with no length (many found online do), or with one
    // that is not its sound's (a find listed with another copy's length).
    // Once the phone's own player has opened it, its length is the one
    // plays are counted by, and a listing more than a second off is
    // corrected for the queue, the rows and, for a find, in storage. A TV
    // or speaker's idea of the length is not trusted for this.
    private fun learnLength() {
        if (player.isRemote || player.isCurrentMediaItemLive) return
        val item = player.currentMediaItem ?: return
        val listed = item.mediaMetadata.durationMs ?: 0
        val measured = player.duration.takeIf { it != C.TIME_UNSET }
        tracker.lengthFound(item.mediaId, playingLengthMs(realLengths.lengthMs(item.mediaId, listed), measured))
        if (measured == null) return
        scope.launch {
            try {
                lengths.learn(item.mediaId, listed, measured)
            } catch (e: Exception) {
                Log.w("Octo", "could not keep a song's length", e)
            }
        }
    }

    // Plays the entry on again from its start as a new play, once a version
    // was picked for its song; only while that entry is the one on. Its next
    // requests carry the next play token, the reopen mark and its kept
    // transition profile go, and on the phone its saved copies go once more
    // while nothing reads it (see OctoPlayer.restartFresh). On a TV or
    // speaker it stops, moves to its start while stopped, and loads again,
    // which signs its address anew.
    private fun restartFresh(entryId: String): Boolean {
        val item = player.currentMediaItem?.takeIf { it.entryId == entryId } ?: return false
        val ref = item.localConfiguration?.uri?.toString()?.let(::parseStreamUri) ?: return false
        streams.restarted(entryId)
        reopened = null
        profiles.forget(listOf(ref.serverId))
        val remote = player.remote
        if (remote != null) {
            remote.stop()
            remote.seekTo(0)
            remote.prepare()
            remote.play()
            return true
        }
        return local.restartFresh(entryId) { streams.dropSaved(ref) }
    }

    // Tells the version drops what the phone's player plays now: the song
    // on and the one a blend plays out.
    private fun tellPlaying() {
        val songs = local.playingItems().mapNotNullTo(HashSet()) { item ->
            item.localConfiguration?.uri?.toString()?.let(::parseStreamUri)?.let(streams::songOf)
        }
        drops.nowPlaying(songs)
    }

    // Asks the server to download the song found online that is on now.
    private fun downloadCurrent() {
        val id = currentId.value?.takeIf(::isFind) ?: return
        scope.launch {
            val track = (online.byIds(listOf(id)).firstOrNull() ?: finds[id])?.asTrack() ?: return@launch
            if (!downloads.request(track)) Log.w("Octo", "notification download: not asked")
        }
    }

    // Brings back the queue from last time, paused where it was left: the
    // window around the saved song and place, or the queue's first song
    // when that one is gone.
    private suspend fun restoreQueue() {
        val name = accountInUse()
        // The queue as the app kept it before an upgrade comes in first.
        legacy.importSources()
        legacy.bringParked(name)
        val saved = window.readSaved(name) ?: return
        if (!window.restore(saved, name)) return
        // A station waits unopened: Play joins it live.
        if (player.currentMediaItem?.mediaId?.let(::isRadio) != true) player.prepare()
        serverQueue.restored(window.serverView())
    }

    // Where playback is; the songs are saved as each edit happens.
    private fun saveQueue() {
        if (!::player.isInitialized || holding) return
        window.saveState()
    }

    // Tells QueueSync of the queue as the server sees it.
    private fun queueChangedForServer(paused: Boolean = false) {
        scope.launch {
            val view = window.serverView()
            if (paused) serverQueue.paused(view) else serverQueue.changed(view)
        }
    }

    // What a change of server asks of the player. Each runs on the main thread.
    private inner class Side : PlayerSide {
        override suspend fun leave(): LeftQueue = withContext(Dispatchers.Main) {
            window.saveState()
            holding = true
            val wasPlaying = player.isPlaying
            val left = window.serverView()
            window.leave()
            empty()
            LeftQueue(left.takeIf { it.trackIds.isNotEmpty() }, wasPlaying)
        }

        override suspend fun restore() = withContext(Dispatchers.Main) {
            try {
                empty()
                restoreQueue()
            } finally {
                holding = false
            }
            saveQueue()
        }

        override suspend fun keep() = withContext(Dispatchers.Main) {
            holding = false
        }

        // A car's open lists all changed with the library under them.
        override fun libraryChanged() {
            scope.launch { notifier.changedAll() }
        }

        // What plays stops and counts, casting ends, and the player is empty.
        private fun empty() {
            if (player.mediaItemCount == 0) return
            tracker.flush()
            positionSaver?.cancel()
            sleep.cancel()
            if (player.isRemote) outputs.stopCasting()
            player.pause()
            player.stop()
            player.clearMediaItems()
        }
    }

    private inner class Watcher : Player.Listener {
        // Songs that failed one after another, back to 0 once one plays.
        private var failedInARow = 0

        // The item that was on, and where, for a station that ends.
        private var lastId: String? = null
        private var lastIndex = C.INDEX_UNSET

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            reopened = null
            tellPlaying()
            val endedStation = lastId?.takeIf(::isRadio)
            val endedAt = lastIndex
            lastId = mediaItem?.mediaId
            lastIndex = player.currentMediaItemIndex
            // A station's stream that ended for good stops on that station
            // rather than moving on to the next one by itself.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && endedStation != null && mediaItem != null && isRadio(mediaItem.mediaId) &&
                endedAt in 0 until player.mediaItemCount
            ) {
                scope.launch {
                    player.seekToDefaultPosition(endedAt)
                    player.pause()
                    val name = radioOf(endedStation)?.second?.takeIf(String::isNotBlank) ?: "The station"
                    feedback.show("$name stopped: its stream ended")
                }
            }
            currentId.value = mediaItem?.mediaId
            updateStationOnAir()
            saveQueue()
            widgets.show(player)
            queueChangedForServer()
            stationRefill.check()
            autoplay.check()
            learnLength()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            stationRefill.check()
            autoplay.check()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            stationRefill.check()
            autoplay.check()
        }

        // While music plays, a song that cannot, like a stream with no
        // connection or a file that is gone, is skipped instead of stopping
        // the music, and one line says which and why. Once every song in the
        // queue has failed, it stops.
        override fun onPlayerError(error: PlaybackException) {
            // The server began sending another file for the song: it opens
            // again from where it is, from the new file's start, once each
            // time the song comes on.
            val entry = player.currentMediaItem?.let { it.entryId ?: it.mediaId }
            if (entry != null && streamChangedIn(error) && reopened != entry) {
                reopened = entry
                // On the phone it goes on by song time, nothing heard twice.
                scope.launch { if (player.isRemote) player.prepare() else local.reopen() }
                return
            }
            // A file kept before sign-in pages were refused can be one: it is
            // let go, so the song comes from the server next time.
            if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
                val item = player.currentMediaItem
                item?.localConfiguration?.uri?.toString()?.let { uri ->
                    scope.launch(Dispatchers.IO) {
                        if (!streams.dropStoredHtml(uri)) offlineDownloads.dropStoredHtml(uri, item.mediaId)
                    }
                }
            }
            if (!player.playWhenReady) return
            // A changed server certificate is being asked about
            // (CertificateChangeHost): the song waits for the answer
            // instead of the queue being skipped through, and plays once
            // the certificate is trusted.
            if (security.certificateChange.value != null && tlsFailureIn(error)) {
                waitingOnCertificate = true
                player.pause()
                return
            }
            failedInARow++
            val title = player.currentMediaItem?.mediaMetadata?.title?.toString()?.takeIf(String::isNotBlank) ?: "a song"
            val why = playFailureWords(error, player.currentMediaItem?.mediaId.orEmpty().let { isFind(it) && it !in owned })
            // A station that cannot play stops there: the next station is
            // the listener's to choose.
            val queued = maxOf(window.logicalCount.value, player.mediaItemCount)
            val atEnd = !player.hasNextMediaItem() && window.atLogicalEnd
            if (failedInARow >= queued || atEnd || isRadio(player.currentMediaItem?.mediaId.orEmpty())) {
                feedback.show(stoppedLine(title, why))
                return
            }
            feedback.show(skippedLine(title, why))
            scope.launch {
                player.seekToNextMediaItem()
                player.prepare()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateStationOnAir()
            if (playbackState == Player.STATE_READY) {
                failedInARow = 0
                learnLength()
            }
            // Equalizer apps let go once the music has stopped, not at a pause.
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED || player.isRemote) audioSession.close()
            // Playing again after Close: a stop keeps its notification again.
            if (closed && playbackState != Player.STATE_IDLE) {
                closed = false
                setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_AFTER_STOP_OR_ERROR)
            }
            stationRefill.check()
            autoplay.check()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            queueChanged.tryEmit(Unit)
            queueChangedForServer()
            stationRefill.check()
            autoplay.check()
            // The length is known once the song has opened.
            learnLength()
        }

        // A pause keeps the server's copy of the queue exact.
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) queueChangedForServer(paused = true)
            updateStationOnAir()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            positionSaver?.cancel()
            widgets.show(player)
            stationRefill.check()
            autoplay.check()
            if (isPlaying) {
                // Equalizer apps on the phone can attach to the music now,
                // unless it is playing on another device.
                if (!player.isRemote) audioSession.open()
                // Where in the song we are, every 15 seconds, in case the phone kills the app.
                positionSaver = scope.launch {
                    while (isActive) {
                        delay(15_000)
                        saveQueue()
                    }
                }
            } else {
                saveQueue()
            }
        }
    }

    // What a car or the app is told when a choice does not play, in the
    // phone's words: the app shows them itself; a car shows the session's
    // passing error, while the music goes on.
    private fun tell(controller: MediaSession.ControllerInfo, words: String) {
        if (controller.packageName == packageName) {
            feedback.show(words)
        } else {
            session?.sendError(SessionError(SessionError.ERROR_UNKNOWN, words))
        }
    }

    private fun refuse(controller: MediaSession.ControllerInfo, words: String): Nothing {
        tell(controller, words)
        throw CarPlayRefused(words)
    }

    // A spoken request from the app's own "play X on Octo": looked up, and
    // played only when something was found and nothing newer was chosen
    // meanwhile; otherwise the queue stays as it is. Answers whether it
    // played.
    private suspend fun playSpoken(controller: MediaSession.ControllerInfo, ask: VoiceAsk): Boolean {
        restored.await()
        val play = withContext(Dispatchers.Default) { carVoice.play(ask) }
        if (play == null) {
            tell(controller, nothingFoundLine(ask.words))
            return false
        }
        val answer = window.play(PlayRequest(play.spec, null, play.shuffle, word = play.source.encoded()))
        if (!answer.started) answer.refusal?.let { tell(controller, nothingFoundLine(ask.words)) }
        return answer.started
    }

    // A car's choice or a spoken request, as the queue plays a list (see
    // carStart): Media3 sets the items it answers, and the queue is taken
    // up when it does. Refused with its words, the queue as it was.
    private suspend fun carStarted(controller: MediaSession.ControllerInfo, play: CarPlay, mark: PlayMark, keepsShuffle: Boolean = true): MediaItemsWithStartPosition =
        when (val started = carStart(window, guard, play, mark, keepsShuffle)) {
            is CarStarted.Items -> MediaItemsWithStartPosition(started.items, started.start, started.positionMs)
            is CarStarted.Refused -> refuse(controller, started.words)
            CarStarted.Superseded -> throw CarPlayRefused("superseded")
        }

    // A car opened a list of its own accord: parts of the server's shelves
    // that failed are asked for again, and Home looks for a queue to pick
    // up.
    private fun carOpened(parentId: String) {
        when (parseCarId(parentId)) {
            CarNode.Home -> {
                carShelves.carOpened()
                carResume.homeOpened()
            }
            CarNode.Radio, CarNode.Playlists -> carShelves.carOpened()
            else -> Unit
        }
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<ConnectionResult> {
            // Apps we don't trust keep the read-only access Media3 gives them.
            // A car and the voice assistant need to press play too.
            if (!controller.isTrusted && controller.packageName !in carApps) return super.onConnectAsync(session, controller)
            // Controllers on the old interfaces are accepted at once: a car or
            // assistant on the old browser waits for this answer on the main thread.
            if (controller.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION) {
                return Futures.immediateFuture(accepted(session, controller))
            }
            // The app connects only once the saved queue is back, so it never
            // mistakes a player still restoring for an empty one.
            return scope.future {
                restored.await()
                accepted(session, controller)
            }
        }

        private fun accepted(session: MediaSession, controller: MediaSession.ControllerInfo): ConnectionResult =
            ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(sessionCommands())
                .apply {
                    if (session.isMediaNotificationController(controller) && stationOnAir.value) {
                        val all = ConnectionResult.DEFAULT_PLAYER_COMMANDS
                        val kept = mediaControlCommands((0 until all.size()).map(all::get).toSet(), true)
                        setAvailablePlayerCommands(Player.Commands.Builder().addAll(*kept.toIntArray()).build())
                    }
                }
                .build()

        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
            val key = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            if (key != null && stationOnAir.value && keyStopsStation(key.keyCode)) {
                if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) player.pause()
                return true
            }
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand) {
                LIKE -> currentId.value?.let(likes::toggle)
                DOWNLOAD -> downloadCurrent()
                CLOSE -> close()
                // A station stops, and lets its stream go.
                STOP -> player.pause()
                // A list played or added by what it is; answered once it plays
                // or was refused, or once the add is in.
                PLAY_LIST -> return scope.future {
                    restored.await()
                    val request = playRequestOf(args) ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    SessionResult(SessionResult.RESULT_SUCCESS, answerExtras(window.play(request)))
                }
                ADD_NEXT, ADD_LAST -> return scope.future {
                    restored.await()
                    val spec = args.getString(ARG_SPEC)?.let { runCatching { ListSpec.fromJson(it) }.getOrNull() }
                        ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    val added = window.addList(spec, next = customCommand == ADD_NEXT, args.getString(ARG_SOURCE), args.getBoolean(ARG_SHOWN_OVER))
                    SessionResult(SessionResult.RESULT_SUCCESS, addedExtras(added))
                }
                PLAY_ENTRY -> window.playEntry(args.getLong(ARG_ENTRY))
                MOVE_ENTRY -> window.moveEntry(args.getLong(ARG_ENTRY), args.getLong(ARG_BEFORE, 0).takeIf { it != 0L })
                REMOVE_ENTRIES -> {
                    val list = args.getLong(ARG_LIST, 0)
                    if (list != 0L) window.removeList(list) else args.getLongArray(ARG_ENTRIES)?.let { window.removeEntries(it.toList()) }
                }
                // A version picked in the app: only the app itself may ask.
                RESTART_FRESH -> {
                    if (controller.packageName != packageName) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    }
                    val entry = args.getString(ARG_ENTRY_KEY)
                    val done = entry != null && restartFresh(entry)
                    return Futures.immediateFuture(SessionResult(if (done) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE))
                }
                START_RADIO -> return scope.future { startRadio(args.getString(MediaConstants.EXTRA_KEY_MEDIA_ID)) }
                PLAY_NEXT_ITEM -> return scope.future { playNextFromCar(args.getString(MediaConstants.EXTRA_KEY_MEDIA_ID)) }
                // Answered once it is known whether something plays.
                PLAY_FROM_SEARCH -> {
                    val ask = voiceAskOf(args.getString(ARG_QUERY), args.getBundle(ARG_EXTRAS))
                    return scope.future {
                        SessionResult(if (playSpoken(controller, ask)) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE)
                    }
                }
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            restored.await()
            // A list chosen in a car is added by what it is, at the end of
            // the listener's songs, a page at a time when it is long; a song
            // chosen in one adds only itself. Songs added are the listener's
            // own, for the queue's headings.
            val ids = mediaItems.flatMap { item ->
                val node = parseCarId(item.mediaId) ?: return@flatMap listOf(item.mediaId)
                if (node is CarNode.Song) return@flatMap listOf(node.trackId)
                when (val answer = withContext(Dispatchers.Default) { car.plays.playFor(item.mediaId) }) {
                    is CarPlayAnswer.Play -> {
                        val added = window.addList(answer.play.spec, next = false, source = null, shownOverWindow = answer.play.shownOverWindow)
                        if (added == null) tell(controller, answer.play.emptyWords)
                        emptyList()
                    }
                    is CarPlayAnswer.Refused -> {
                        tell(controller, answer.words)
                        emptyList()
                    }
                    null -> listOf(item.mediaId)
                }
            }
            // An id a car kept from before a save plays as the song it became.
            withContext(Dispatchers.IO) { playable.items(renamedSongs(queue.db, ids)) }.map { it.withSource(QueueSource.You) }.toMutableList()
        }

        // The futures of plays run on the main thread and end there, with
        // nothing waiting between the check of a play that waited on the
        // server and Media3 setting its songs.
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            restored.await()
            val mark = guard.mark()
            val single = mediaItems.singleOrNull()
            // Something said out loud in a car, like "play Drake on Octo".
            single?.requestMetadata?.searchQuery?.let { query ->
                val ask = voiceAskOf(query, single.requestMetadata.extras)
                val play = withContext(Dispatchers.Default) { carVoice.play(ask) } ?: refuse(controller, nothingFoundLine(ask.words))
                val spoken = CarPlay(play.spec, null, play.source, network = true, shuffle = play.shuffle, emptyWords = nothingFoundLine(ask.words))
                // Its shuffle comes with its songs, not on the queue they
                // replace.
                return@future carStarted(controller, spoken, mark, keepsShuffle = false)
            }
            // A place or a song chosen in a car.
            when (val answer = single?.let { withContext(Dispatchers.Default) { car.plays.playFor(it.mediaId) } }) {
                is CarPlayAnswer.Refused -> refuse(controller, answer.words)
                is CarPlayAnswer.Play -> return@future carStarted(controller, answer.play, mark)
                null -> Unit
            }
            // Ids a car or a widget kept from before a save play as the songs
            // they became.
            val each = playable.itemsEach(renamedSongs(queue.db, mediaItems.map { it.mediaId }))
            // The list's name, when the app gave one, goes on every song.
            val source = mediaItems.firstOrNull()?.requestedSource
            val resolved = each.filterNotNull().map { if (source != null) it.withSource(source) else it }
            // Keep starting on the chosen song even if some before it are gone.
            val index = if (startIndex == C.INDEX_UNSET) C.INDEX_UNSET else chosenStart(each, startIndex)
            MediaItemsWithStartPosition(resolved, index, startPositionMs)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            // The library cannot open until its upgrade has room on disk.
            if (upgradeGuard.state.value != UpgradeState.Clear) throw UnsupportedOperationException("Nothing to resume")
            restored.await()
            // The queue is back already: it plays as it is.
            if (player.mediaItemCount > 0) {
                val items = List(player.mediaItemCount, player::getMediaItemAt)
                val index = player.currentMediaItemIndex.coerceAtLeast(0)
                return@future if (isForPlayback) {
                    MediaItemsWithStartPosition(items, index, player.currentPosition)
                } else {
                    MediaItemsWithStartPosition(listOf(items[index]), 0, player.currentPosition)
                }
            }
            legacy.importSources()
            legacy.bringParked(accountInUse())
            val saved = window.readSaved(accountInUse()) ?: throw UnsupportedOperationException("Nothing to resume")
            // The saved window, the saved song where it was left, or the
            // queue's first song from its start.
            val byQk = saved.rows.sortedWith(compareBy({ it.qk }, { it.entry }))
            val items = byQk.mapNotNull { saved.items[it.entry] }
            val index = byQk.indexOfFirst { it.entry == saved.start }.coerceAtLeast(0)
            if (isForPlayback) {
                MediaItemsWithStartPosition(items, index, saved.positionMs)
            } else {
                // The media controls only need the song to show.
                MediaItemsWithStartPosition(listOf(items[index]), 0, saved.positionMs)
            }
        }

        // Answered at once: the old browser waits for the root on the main
        // thread too. What the car says it can show is kept for its tabs, and
        // a car connecting asks the server for its shelves and for a queue to
        // pick up, without waiting.
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            rootLimits[browser] = params?.extras?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 0) ?: 0
            if (browser.packageName in carPackages) {
                carShelves.carConnected()
                carResume.carConnected()
            }
            return Futures.immediateFuture(LibraryResult.ofItem(car.root(), params))
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            rootLimits.remove(controller)
        }

        // Browsing from a car: its tabs and every list under them. A list the
        // server could not give says why, in the phone's words.
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val read = notifier.readStarted(parentId)
            if (read.ownAccord && browser.packageName in carPackages) carOpened(parentId)
            try {
                val (none, shown) = withContext(Dispatchers.Default) {
                    val items = car.children(parentId, rootLimits[browser]?.takeIf { it > 0 }, read.ownAccord)
                    val shown = pageOf(items, page, pageSize)
                    car.grantArtwork(browser.packageName, shown)
                    items.isEmpty() to shown
                }
                if (read.ownAccord && none && parseCarId(parentId) == CarNode.Radio && browser.packageName in carPackages) {
                    tell(browser, CAR_EMPTY_RADIO)
                }
                LibraryResult.ofItemList(shown, params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val words = car.browseWords(parentId, e)
                tell(browser, words)
                LibraryResult.ofError(SessionError(SessionError.ERROR_IO, words))
            } finally {
                notifier.readEnded(read)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val item = withContext(Dispatchers.Default) {
                car.lookup(mediaId, rootLimits[browser]?.takeIf { it > 0 })?.also { car.grantArtwork(browser.packageName, listOf(it)) }
            } ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            LibraryResult.ofItem(item, null)
        }

        // Any list a car can open is taken at once, without asking anything
        // about it, so it hears of changes while open.
        override fun onSubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            val node = parseCarId(parentId)
            if (node == null || !node.isBrowsable) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            notifier.opened(parentId)
            // The old browser loads what it subscribed to by itself; a Media3
            // browser is told to.
            if (browser.controllerVersion != MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION) {
                session.notifyChildrenChanged(browser, parentId, Int.MAX_VALUE, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onUnsubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
        ): ListenableFuture<LibraryResult<Void>> {
            if (session.getSubscribedControllers(parentId).none { it != browser }) notifier.closed(parentId)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        // A car gets one answer for each search, sent when it is told the
        // search ended, so it is always told, whatever happened.
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            var count = 0
            try {
                val answer = carSearch.answer(query)
                count = withContext(Dispatchers.Default) { car.searchItems(answer).size }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Octo", "car search failed: ${e.javaClass.simpleName}")
            } finally {
                if (isActive) session.notifySearchResultChanged(browser, query, count, params)
            }
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val items = try {
                val answer = carSearch.latest(query) ?: carSearch.answer(query)
                withContext(Dispatchers.Default) { car.searchItems(answer) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Octo", "car search answer failed: ${e.javaClass.simpleName}")
                emptyList()
            }
            val shown = withContext(Dispatchers.Default) { pageOf(items, page, pageSize).also { car.grantArtwork(browser.packageName, it) } }
            LibraryResult.ofItemList(shown, params)
        }
    }
}

// A choice that does not play: its future fails, so nothing changes.
private class CarPlayRefused(words: String) : Exception(words)

// A spoken request as the assistant sends it: the words, and its extras.
private fun voiceAskOf(query: String?, extras: Bundle?): VoiceAsk = voiceAsk(
    query,
    extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS),
    extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
    extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
    extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
)
