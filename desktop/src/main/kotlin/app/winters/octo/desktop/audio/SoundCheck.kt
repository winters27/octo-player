package app.winters.octo.desktop.audio

import app.winters.octo.desktop.AppState
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

// Set to a WAV path, the app plays that file through the real player once
// its window is up, prints what the engine says, and closes: a check that
// sound comes out on this machine, with a tone instead of anyone's music.
// `./gradlew :desktop:run -Pocto.checkPlay=build/check/tone.wav` makes the
// tone there first if it is missing.
const val CHECK_PLAY = "octo.checkPlay"

private const val CHECK_SECONDS = 4

suspend fun checkSound(app: AppState, file: File, close: () -> Unit) {
    if (!file.exists()) withContext(Dispatchers.IO) { writeSine(file, seconds = CHECK_SECONDS + 2) }
    val player = app.player
    val engine = (player as? EnginePlayer)?.engine
    println("check: playing ${file.absolutePath} on ${if (engine == null) "the silent player" else "the audio engine"}")
    player.play(listOf(Song(LOCAL_PREFIX + file.absolutePath, "Test tone", duration = CHECK_SECONDS + 2)))
    var first: Long? = null
    var last = 0L
    repeat(CHECK_SECONDS * 4) {
        delay(250)
        last = player.positionMs()
        if (first == null && last > 0) first = last
        val state = player.state.value
        println("check: engine ${engine?.state()} playing=${state.playing} position=${last} ms on ${state.playingOn?.name ?: "?"}")
    }
    val start = first
    val moved = engine != null && start != null && last - start >= 1_000
    println(if (moved) "check: sound is playing, the clock moved ${last - start} ms" else "check: FAILED, the engine did not play")
    player.pause()
    close()
}
