package app.winters.octo.desktop.audio

import app.winters.octo.desktop.player.DEFAULT_OUTPUT
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.settings.SettingsStore
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
    // The engine, opened beforehand off the window's thread (Startup), or
    // why it would not open.
    opened: Result<AudioEngine> = runCatching { NativeAudioEngine.open() },
): OpenedPlayer {
    val playback = settings.current.playback
    return try {
        val engine = opened.getOrThrow()
        keepTrust(engine, settings, scope)
        val device = playback.outputDevice?.takeUnless { it == DEFAULT_OUTPUT }
        OpenedPlayer(EnginePlayer(engine, LocalOrServer(ServerSongs(headers, landed, client)), volume = playback.volume, device = device), null)
    } catch (e: Throwable) {
        if (e is VirtualMachineError) throw e
        OpenedPlayer(
            SilentPlayer(scope = scope, volume = playback.volume),
            "Octo couldn't start its sound engine (${e.message ?: e.javaClass.simpleName}), so songs play silently.",
        )
    }
}

// Gives the engine the certificates the listener trusted, now and after
// every change, since it fetches songs itself: a server whose own
// certificate was trusted at sign-in then plays as well as it browses.
fun keepTrust(engine: AudioEngine, settings: SettingsStore, scope: CoroutineScope): Job =
    // Undispatched, so the engine has them before the first song is asked for.
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        settings.state.map { it.trustedCertificates }.distinctUntilChanged().collect { engine.setTrustedCertificates(it) }
    }
