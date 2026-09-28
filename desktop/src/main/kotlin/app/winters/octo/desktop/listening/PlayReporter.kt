package app.winters.octo.desktop.listening

import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.server.Connection
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.system.isOpenedFile
import app.winters.octo.listening.PendingPlay
import app.winters.octo.listening.worthRetrying
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

// How often plays that could not be sent are tried again.
private const val RETRY_EVERY_MS = 5 * 60_000L

// Tells the signed-in server what is played here, as the phone does: the
// song playing now as soon as it is heard, and each play that counted.
// Every counted play also goes in this computer's own play log. A play the
// server cannot take right now waits on disk and is sent later, oldest
// first. Nothing here ever holds up playback or shows an error.
class PlayReporter(
    private val player: DesktopPlayer,
    private val settings: SettingsStore,
    private val connection: () -> Connection?,
    // The settings folder; each account's listening goes in its own folder
    // there. Null keeps nothing.
    private val root: File?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    wallClock: () -> Long = System::currentTimeMillis,
) {
    private val counter = PlayCounter(::started, ::counted, clock, wallClock)
    private val sending = Mutex()

    // Guards the waiting-plays file: a play finishing while an older one is
    // being taken off must not lose either.
    private val pendingFile = Any()

    // While quitting, a counted play is written at once rather than later.
    private var quitting = false

    // Follows the player, and tries waiting plays again now and then.
    fun start(): Job = scope.launch {
        launch { player.state.collect(counter::update) }
        while (isActive) {
            delay(RETRY_EVERY_MS)
            sendWaiting()
        }
    }

    // A server was signed in to: plays from before may be waiting for it.
    fun signedIn() = sendWaiting()

    // Octo is quitting or signing out: the song playing counts now, if it
    // was heard long enough. Written before this returns.
    fun flush() {
        quitting = true
        try {
            counter.flush()
        } finally {
            quitting = false
        }
    }

    // The play log of the signed-in account, if there is one.
    fun log(): PlayLog? = folder()?.let { PlayLog(File(it, "plays.jsonl")) }

    private fun folder(): File? = root?.let { dir -> connection()?.let { listeningFolder(dir, it.client.username, it.server.address) } }

    private fun reports(): Boolean = settings.current.listening.reportPlays

    private fun started(song: Song) {
        val client = connection()?.client ?: return
        if (!reports() || isOpenedFile(song.id)) return
        scope.launch(io) {
            try {
                client.scrobble(song.id, System.currentTimeMillis(), submission = false)
            } catch (e: SubsonicException) {
                // Only "playing now"; nothing to keep.
            }
        }
    }

    private fun counted(play: HeardPlay) {
        val folder = folder() ?: return
        val send = reports() && !isOpenedFile(play.song.id)
        val write = {
            PlayLog(File(folder, "plays.jsonl")).add(LoggedPlay(play.startedAt, play.heardMs, play.song.logged()))
            if (send) synchronized(pendingFile) { PendingPlays(File(folder, "pending.txt")).add(PendingPlay(play.song.id, play.startedAt)) }
        }
        if (quitting) {
            runCatching(write)
            return
        }
        scope.launch(io) {
            runCatching(write)
            if (send) sendWaiting()
        }
    }

    // Sends the waiting plays oldest first, and stops at the first one the
    // server cannot take right now. A play the server refused outright is
    // dropped, since it would refuse it again.
    private fun sendWaiting() = scope.launch(io) {
        if (!reports()) return@launch
        val client = connection()?.client ?: return@launch
        val folder = folder() ?: return@launch
        val pending = PendingPlays(File(folder, "pending.txt"))
        sending.withLock {
            for (play in synchronized(pendingFile) { pending.all() }) {
                val failure = try {
                    client.scrobble(play.serverId, play.startedAt, submission = true)
                    null
                } catch (e: SubsonicException) {
                    e
                }
                if (failure != null && worthRetrying(failure)) break
                synchronized(pendingFile) { pending.remove(play) }
            }
        }
    }
}
