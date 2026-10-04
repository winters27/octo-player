package app.winters.octo.desktop

import app.winters.octo.health.FixOutcome
import app.winters.octo.health.FixStep
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.deletedLine
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Library health's fixes and deleting a song from disk. Each run says how
// it went in the notice line, with Undo when something changed, and reads
// the library again: once at once, and twice more after the server's scan
// has had time to see the change (it asks for one a few seconds after).

// How long after a change the library is read again, in milliseconds.
internal val CatchUpReads = listOf(15_000L, 45_000L)

// Runs fix steps through the server; `line` words the notice from how it
// went. Undo runs the steps that put it all back.
fun AppState.runFix(label: String, steps: List<FixStep>, settle: HealthCheck? = null, line: (FixOutcome) -> String = { it.summary() }) {
    health.run(label, steps, settle) { outcome ->
        val words = line(outcome)
        notice = words
        noticeDetail = null
        val undo = outcome.undo
        noticeAction = if (undo.isEmpty()) null else NoticeAction(words, "Undo") {
            noticeAction = null
            runFix("Putting it back", undo)
        }
        if (outcome.done.isNotEmpty()) catchUp()
    }
}

// Takes songs off the server's disk, into its trash. Asked first by the
// caller; see askToDelete in the menus.
fun AppState.deleteFromDisk(songs: List<Song>) {
    val steps = songs.map { FixStep.Remove(it.id, it.title) }
    runFix("Deleting", steps) { outcome ->
        if (outcome.failed.isEmpty() && !outcome.rehearsed && !outcome.stopped) deletedLine(outcome.done.size, songs.singleOrNull()?.title)
        else outcome.summary()
    }
}

// Reads the library now and again once the server has scanned.
private fun AppState.catchUp() {
    library?.load()
    scope.launch {
        var waited = 0L
        for (at in CatchUpReads) {
            delay(at - waited)
            waited = at
            library?.load()
        }
    }
}
