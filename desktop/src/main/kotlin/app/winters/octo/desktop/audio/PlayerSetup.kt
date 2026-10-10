package app.winters.octo.desktop.audio

import app.winters.octo.desktop.player.DEFAULT_OUTPUT
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.playback.TransitionProfiles
import app.winters.octo.subsonic.SubsonicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// The player the app plays through, and why it makes no sound when the
// audio engine could not start.
class OpenedPlayer(val player: DesktopPlayer, val problem: String?)

// Starts the audio engine and its player, on the device chosen last time.
// A machine where the engine's library will not load (a system it was not
// built for, say) still gets a working app: the silent player keeps the
// queue and time, and one line says why there is no sound.
fun openPlayer(
    settings: SettingsStore,
    scope: CoroutineScope,
    client: () -> SubsonicClient?,
    headers: () -> Map<String, String> = { emptyMap() },
    // The library id of a song found online once fetched into the library.
    landed: (String) -> String? = { null },
    // Whether the server signed in now hands out transition profiles.
    transitions: () -> Boolean = { false },
    // The engine, opened beforehand off the window's thread (Startup), or
    // why it would not open.
    opened: Result<AudioEngine> = runCatching { NativeAudioEngine.open() },
): OpenedPlayer {
    val playback = settings.current.playback
    return try {
        val engine = opened.getOrThrow()
        keepTrust(engine, settings, scope)
        keepStartAfter(engine, settings, scope)
        val device = playback.outputDevice?.takeUnless { it == DEFAULT_OUTPUT }
        val profiles = EngineProfiles(scope, ServerTransitions(client, transitions)::current, { serverSongId(it, landed) }, engine::setSongProfile)
        val quality = { settings.current.playback.streamQuality }
        val player = EnginePlayer(engine, LocalOrServer(ServerSongs(headers, landed, quality, client)), volume = playback.volume, device = device, profiles = profiles)
        OpenedPlayer(player, null)
    } catch (e: Throwable) {
        if (e is VirtualMachineError) throw e
        OpenedPlayer(
            SilentPlayer(scope = scope, volume = playback.volume),
            "Octo couldn't start its sound engine (${e.message ?: e.javaClass.simpleName}), so songs play silently.",
        )
    }
}

// The kept transition profiles of the server signed in now, while it hands
// them out; kept anew when the server changes.
class ServerTransitions(private val client: () -> SubsonicClient?, private val offers: () -> Boolean) {
    private var forClient: SubsonicClient? = null
    private var kept: TransitionProfiles? = null

    fun current(): TransitionProfiles? = synchronized(this) {
        val server = client()?.takeIf { offers() } ?: return null
        if (server !== forClient) {
            forClient = server
            kept = TransitionProfiles(fetch = { id -> server.transitionProfile(id) })
        }
        kept
    }
}

// Tells the engine how much of a stream to have ready before a song
// starts, now and after every change.
fun keepStartAfter(engine: AudioEngine, settings: SettingsStore, scope: CoroutineScope): Job =
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        settings.state.map { it.playback.startAfter.ms }.distinctUntilChanged().collect { engine.setStartAfter(it) }
    }

// Gives the engine the certificates the listener trusted, now and after
// every change, since it fetches songs itself: a server whose own
// certificate was trusted at sign-in then plays as well as it browses.
fun keepTrust(engine: AudioEngine, settings: SettingsStore, scope: CoroutineScope): Job =
    // Undispatched, so the engine has them before the first song is asked for.
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        settings.state.map { it.trustedCertificates }.distinctUntilChanged().collect { engine.setTrustedCertificates(it) }
    }
