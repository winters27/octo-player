package app.winters.octo.desktop.audio

import app.winters.octo.desktop.player.DEFAULT_OUTPUT
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.SubsonicClient
import kotlinx.coroutines.CoroutineScope

// The player the app plays through, and why it makes no sound when the
// audio engine could not start.
class OpenedPlayer(val player: DesktopPlayer, val problem: String?)

// Starts the audio engine and its player, on the device chosen last time.
// A machine where the engine's library will not load (a system it was not
// built for, say) still gets a working app: the silent player keeps the
// queue and time, and one line says why there is no sound.
fun openPlayer(settings: SettingsStore, scope: CoroutineScope, client: () -> SubsonicClient?): OpenedPlayer {
    val playback = settings.current.playback
    return try {
        val engine = NativeAudioEngine.open()
        val device = playback.outputDevice?.takeUnless { it == DEFAULT_OUTPUT }
        OpenedPlayer(EnginePlayer(engine, LocalOrServer(ServerSongs(client)), volume = playback.volume, device = device), null)
    } catch (e: Throwable) {
        if (e is VirtualMachineError) throw e
        OpenedPlayer(
            SilentPlayer(scope = scope, volume = playback.volume),
            "Octo couldn't start its sound engine (${e.message ?: e.javaClass.simpleName}), so songs play silently.",
        )
    }
}
