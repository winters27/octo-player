package app.winters.octo.output

import android.content.Context
import android.net.wifi.WifiManager
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.player.PlayerSettings
import app.winters.octo.ui.common.Feedback
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// How long the device list says it is still looking before it says what
// to check when nothing turned up.
private const val LOOKING_MS = 8_000L

// A device that has not answered a connection in this long never will.
private const val CONNECT_MS = 20_000L

// Where the music is playing.
sealed interface OutputChoice {
    data object Phone : OutputChoice

    data class Connecting(val device: OutputDevice) : OutputChoice

    data class Casting(val device: OutputDevice) : OutputChoice
}

// The playback service's side: it moves the music across as a device
// connects or goes.
interface OutputHost {
    // Plays on `output` from now on, carrying the queue over from wherever
    // it plays now. `playing`, when given, is whether it should play there.
    fun castTo(output: RemoteOutput, playing: Boolean?)

    // Brings the music back to the phone, playing or not as `playing`
    // decides from whether it was. Answers whether it was playing.
    fun backToPhone(playing: (wasPlaying: Boolean) -> Boolean): Boolean
}

// The one place that knows where music plays: the devices found, the one
// connected or connecting, and the choices made in the device list. The
// list asks it to look and to connect; the playback service does the
// moving. One quiet line reports a connection that failed or was lost.
@Singleton
class Outputs @Inject constructor(
    @ApplicationContext private val context: Context,
    finders: Set<@JvmSuppressWildcards OutputFinder>,
    settings: PlayerSettings,
    private val feedback: Feedback,
) : OutputFinder.Events {
    private val scope = MainScope()
    private val finders = finders.sortedBy { it.family.ordinal }
    private val prefs: StateFlow<PlayerPrefs> = settings.prefs.stateIn(scope, SharingStarted.Eagerly, PlayerPrefs())

    // The kinds of device this build can cast to.
    val families: Set<OutputFamily> = this.finders.mapTo(HashSet()) { it.family }

    // Every device found, the kinds switched off in settings left out.
    val devices: StateFlow<List<OutputDevice>> =
        combine(this.finders.map { it.devices }) { lists -> lists.flatMap { it } }
            .combine(prefs.map { it.castRenderers }.distinctUntilChanged()) { all, renderers ->
                all.filter { renderers || it.family != OutputFamily.Renderer }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _current = MutableStateFlow<OutputChoice>(OutputChoice.Phone)
    val current: StateFlow<OutputChoice> = _current

    // True for the first few seconds of looking.
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    private var host: OutputHost? = null
    private var output: RemoteOutput? = null
    private var lookers = 0
    private var searchTimer: Job? = null
    private var connectTimer: Job? = null

    // Whether the music was playing, held while it moves from one Cast
    // device to another (the old one lets go before the new one answers).
    private var carryPlaying: Boolean? = null

    // Answering devices on the Wi-Fi needs this while looking or casting.
    private val multicast: WifiManager.MulticastLock? = runCatching {
        context.getSystemService(WifiManager::class.java).createMulticastLock("octo:outputs").apply { setReferenceCounted(false) }
    }.getOrNull()

    init {
        this.finders.forEach { it.setEvents(this) }
        // Switching media renderers off stops looking for them at once.
        scope.launch {
            prefs.map { it.castRenderers }.distinctUntilChanged().collect { on ->
                if (lookers > 0) finders.filter { it.family == OutputFamily.Renderer }.forEach { if (on) it.startLooking() else it.stopLooking() }
            }
        }
    }

    // The playback service, once it runs.
    fun attach(host: OutputHost) {
        this.host = host
    }

    // The service is going: casting cannot go on without it.
    fun detach(host: OutputHost) {
        if (this.host !== host) return
        output?.let { gone ->
            output = null
            gone.close(stopPlaying = true)
        }
        this.host = null
        _current.value = OutputChoice.Phone
        holdMulticast()
    }

    // The device list is open: find devices until it closes.
    fun startLooking() {
        if (lookers++ > 0) return
        finders.filter(::enabled).forEach { it.startLooking() }
        searchTimer?.cancel()
        _searching.value = true
        searchTimer = scope.launch {
            delay(LOOKING_MS)
            _searching.value = false
        }
        holdMulticast()
    }

    fun stopLooking() {
        if (lookers == 0 || --lookers > 0) return
        finders.forEach { it.stopLooking() }
        searchTimer?.cancel()
        _searching.value = false
        holdMulticast()
    }

    // Plays on `device` instead.
    fun choose(device: OutputDevice) {
        when (val now = _current.value) {
            is OutputChoice.Casting -> if (now.device.id == device.id) return
            is OutputChoice.Connecting -> if (now.device.id == device.id) return
            OutputChoice.Phone -> Unit
        }
        val finder = finders.firstOrNull { it.family == device.family } ?: return
        _current.value = OutputChoice.Connecting(device)
        connectTimer?.cancel()
        connectTimer = scope.launch {
            delay(CONNECT_MS)
            if ((_current.value as? OutputChoice.Connecting)?.device?.id == device.id) settle(failedOn = device)
        }
        finder.connect(device)
    }

    // Plays on the phone again, the music carrying on as it was.
    fun choosePhone() = endCasting { was -> playsOnReturn(was, chosen = true, keepPlaying = false) }

    // Stops casting with the music stopped too, as the Close button does.
    fun stopCasting() = endCasting { false }

    private fun endCasting(playing: (Boolean) -> Boolean) {
        carryPlaying = null
        connectTimer?.cancel()
        val gone = output
        output = null
        _current.value = OutputChoice.Phone
        if (gone != null) {
            host?.backToPhone(playing)
            gone.close(stopPlaying = true)
        }
        holdMulticast()
    }

    // ---- What the finders report ----

    override fun connected(output: RemoteOutput) {
        val wanted = (_current.value as? OutputChoice.Connecting)?.device
        val host = host
        if (wanted?.id != output.device.id || host == null) {
            // Asked for, then given up on, or with nothing left to play from.
            output.close(stopPlaying = true)
            if (wanted?.id == output.device.id) settle(failedOn = wanted)
            return
        }
        connectTimer?.cancel()
        val old = this.output
        host.castTo(output, carryPlaying)
        carryPlaying = null
        this.output = output
        _current.value = OutputChoice.Casting(output.device)
        old?.close(stopPlaying = true)
        holdMulticast()
    }

    override fun failed(device: OutputDevice) {
        if ((_current.value as? OutputChoice.Connecting)?.device?.id != device.id) return
        settle(failedOn = device)
    }

    override fun ended(output: RemoteOutput, lost: Boolean) {
        if (output !== this.output) return
        this.output = null
        // Moving to another device: the music waits for it, paused.
        val moving = _current.value is OutputChoice.Connecting
        val was = host?.backToPhone { was ->
            if (moving) false else playsOnReturn(was, chosen = false, keepPlaying = prefs.value.castKeepPlaying)
        } ?: false
        if (moving) carryPlaying = was else _current.value = OutputChoice.Phone
        output.close(stopPlaying = false)
        if (lost) feedback.show("Lost the connection to ${output.device.name}")
        holdMulticast()
    }

    // Connecting failed: back to what was playing before.
    private fun settle(failedOn: OutputDevice) {
        connectTimer?.cancel()
        val now = output
        _current.value = if (now != null) OutputChoice.Casting(now.device) else OutputChoice.Phone
        carryPlaying = null
        feedback.show("Couldn't connect to ${failedOn.name}")
    }

    private fun enabled(finder: OutputFinder): Boolean = finder.family != OutputFamily.Renderer || prefs.value.castRenderers

    private fun holdMulticast() {
        val lock = multicast ?: return
        val wanted = lookers > 0 || _current.value !is OutputChoice.Phone
        runCatching { if (wanted && !lock.isHeld) lock.acquire() else if (!wanted && lock.isHeld) lock.release() }
    }
}
