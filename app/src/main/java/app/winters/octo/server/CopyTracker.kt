package app.winters.octo.server

import androidx.annotation.VisibleForTesting
import app.winters.octo.catalog.ResumeReport
import app.winters.octo.catalog.ResumedCopy
import app.winters.octo.catalog.SaveResumes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

// Where one pass of a copy says how far it is. Every call does nothing
// unless a tracker bound it to its pass.
interface CopyReport {
    // At `step`, `done` of `of` (null while unknown); `artwork` the cover of
    // an album that just came, null to keep the one shown.
    fun at(step: CopyStep, done: Long, of: Long?, artwork: String? = null) {}

    // The copy is built: its songs and albums.
    fun staged(songs: Int, albums: Int) {}

    // The copy is in the library.
    fun swapped() {}

    companion object {
        val None: CopyReport = object : CopyReport {}
    }
}

// A save of a server's copy that stopped part way, as the copy's surfaces
// show its resume: a run at the copy's Save step from how much was in
// (CopyTracker.beginResume), how much of the save is in after each group,
// and once it is in, `finish` with the run's token, pass and report, which
// finishes the copy as a copy is and says how that ended. A resume that
// stops again says why (`failed`) and its run ends.
class CopyResumes(
    private val tracker: CopyTracker,
    private val nameOf: (account: String) -> String,
    private val began: () -> Unit,
    private val failed: (words: String) -> Unit,
    private val finish: (copy: ResumedCopy, token: Long, pass: Int, report: CopyReport) -> Unit,
) : SaveResumes {
    override fun resuming(copy: ResumedCopy): ResumeReport {
        val (token, pass) = tracker.beginResume(copy.account, nameOf(copy.account), arriving = copy.firstCopy, saved = copy.saved)
        began()
        val report = tracker.report(token, pass)
        return object : ResumeReport {
            override fun saved(part: Float) = report.at(CopyStep.Save, savedParts(part), SAVE_PARTS)

            override fun ended(failure: Throwable?) {
                if (failure == null) {
                    finish(copy, token, pass, report)
                } else {
                    failed(copyProblem(failure))
                    tracker.passFailed(token, pass, copyProblem(failure))
                    tracker.end(token)
                }
            }
        }
    }
}

// The one writer of how far the copy running is (`run`) and how the last
// first copy ended (`outcome`). A run is one sync job; each copy in it is a
// numbered pass. An update for another run or pass, for a step before the
// one shown, or after the run ended is dropped, so a copy that was stopped
// and replaced never writes over its replacement. Within a pass the
// fraction and each step's count only go forward. Two parts of a copy
// report at once (the album list and the song pages), so every write
// compares and sets.
@Singleton
class CopyTracker @Inject constructor() {
    private val _run = MutableStateFlow<CopyRunState?>(null)
    val run: StateFlow<CopyRunState?> = _run

    private val _outcome = MutableStateFlow<CopyOutcome?>(null)
    val outcome: StateFlow<CopyOutcome?> = _outcome

    private val tokens = AtomicLong(0)

    // Sees every run written, for tests.
    @VisibleForTesting
    internal var seen: (CopyRunState?) -> Unit = {}

    // A job starts: its run takes the place of any other in one write, with
    // what its first copy will likely be (`arriving`, `whole`) until a pass
    // says. Answers its token.
    fun begin(account: String, server: String, arriving: Boolean, whole: Boolean): Long {
        val token = tokens.incrementAndGet()
        val state = CopyRunState(
            token = token,
            pass = 0,
            account = account,
            server = server,
            arriving = arriving,
            whole = whole,
            step = CopyStep.Connect,
            done = 0,
            of = null,
            fraction = null,
            artwork = null,
            songs = 0,
            albums = 0,
            dismissed = false,
        )
        _run.value = state
        seen(state)
        return token
    }

    // A run for a whole copy whose save stopped part way and is resumed:
    // its one pass is at the Save step from its first write, with `saved` of
    // the save in already (0 to 1), so its bar goes on from where the stop
    // left it; `arriving` for the account's first copy. Answers its token and
    // pass.
    fun beginResume(account: String, server: String, arriving: Boolean, saved: Float): Pair<Long, Int> {
        val token = tokens.incrementAndGet()
        val done = savedParts(saved)
        val state = CopyRunState(
            token = token,
            pass = 1,
            account = account,
            server = server,
            arriving = arriving,
            whole = true,
            step = CopyStep.Save,
            done = done,
            of = SAVE_PARTS,
            fraction = copyFraction(CopyStep.Save, done, SAVE_PARTS),
            artwork = null,
            songs = 0,
            albums = 0,
            dismissed = false,
        )
        if (arriving) clearOutcome()
        _run.value = state
        seen(state)
        return token to 1
    }

    // The job's next copy starts at Connect, `whole` a guess until it is
    // planned. Answers its pass, or null when the run is another's now.
    fun nextPass(token: Long, arriving: Boolean, whole: Boolean): Int? {
        var pass: Int? = null
        change { run ->
            if (run.token != token) return@change null
            run.copy(
                pass = run.pass + 1,
                arriving = arriving,
                whole = whole,
                step = CopyStep.Connect,
                done = 0,
                of = null,
                fraction = null,
                artwork = null,
                songs = 0,
                albums = 0,
                dismissed = false,
            ).also { pass = it.pass }
        }
        // A first copy starting makes the last one's card old news.
        if (pass != null && arriving) clearOutcome()
        return pass
    }

    // The pass knows what it copies: the whole library, or only the steps
    // after a copy.
    fun planned(token: Long, pass: Int, arriving: Boolean, whole: Boolean) {
        change { run ->
            if (!current(run, token, pass)) return@change null
            run.copy(arriving = arriving, whole = whole, step = if (whole) run.step else CopyStep.Settle)
        }
        if (arriving) clearOutcome()
    }

    // Binds a report to one pass.
    fun report(token: Long, pass: Int): CopyReport = object : CopyReport {
        override fun at(step: CopyStep, done: Long, of: Long?, artwork: String?) = progress(token, pass, step, done, of, artwork)

        override fun staged(songs: Int, albums: Int) {
            change { run -> if (current(run, token, pass)) run.copy(songs = songs, albums = albums) else null }
        }

        override fun swapped() = progress(token, pass, CopyStep.Settle, 0, null, null)
    }

    @VisibleForTesting
    internal fun progress(token: Long, pass: Int, step: CopyStep, done: Long, of: Long?, artwork: String?) {
        change { run ->
            if (!current(run, token, pass) || step < run.step || !run.whole) return@change null
            val same = step == run.step
            val count = if (same) maxOf(run.done, done) else done
            val total = if (same) of ?: run.of else of
            val fraction = copyFraction(step, count, total)
            run.copy(
                step = step,
                done = count,
                of = total,
                fraction = if (fraction == null) run.fraction else maxOf(run.fraction ?: 0f, fraction),
                artwork = artwork ?: run.artwork,
            )
        }
    }

    // The pass's last step ended: a first copy says its music is here. The
    // run is read under the outcome's lock, so a done card put away meanwhile
    // stays away (dismiss marks the run before it takes the lock).
    fun passDone(token: Long, pass: Int) = synchronized(outcomeLock) {
        val run = _run.value ?: return@synchronized
        if (!current(run, token, pass) || !run.arriving || !run.swapped || run.dismissed) return@synchronized
        _outcome.value = CopyOutcome.Arrived(token, run.account, run.server, run.songs, run.albums, stepsProblem = null)
    }

    // The pass failed with `reason`. A first copy says so; once its music is
    // in, only that a step after it failed.
    fun passFailed(token: Long, pass: Int, reason: String) = synchronized(outcomeLock) {
        val run = _run.value ?: return@synchronized
        if (!current(run, token, pass) || !run.arriving) return@synchronized
        if (run.swapped) {
            if (run.dismissed) return@synchronized
            _outcome.value = CopyOutcome.Arrived(token, run.account, run.server, run.songs, run.albums, stepsProblem = reason)
        } else {
            _outcome.value = CopyOutcome.Failed(token, run.account, run.server, reason, run.step, run.done)
        }
    }

    // The job ended, however it ended.
    fun end(token: Long) {
        while (true) {
            val run = _run.value ?: return
            if (run.token != token) return
            if (_run.compareAndSet(run, null)) {
                seen(null)
                return
            }
        }
    }

    // The done card was put away: while the steps after the copy run, or
    // after.
    fun dismiss(token: Long) {
        change { run -> if (run.token == token) run.copy(dismissed = true) else null }
        clearArrived(token)
    }

    // The done card was seen and left.
    fun clearArrived(token: Long) = synchronized(outcomeLock) {
        val outcome = _outcome.value
        if (outcome is CopyOutcome.Arrived && outcome.token == token) _outcome.value = null
    }

    // Try again was tapped, or the server in use changed.
    fun clearOutcome() = synchronized(outcomeLock) {
        _outcome.value = null
    }

    // Held by every write of `outcome`.
    private val outcomeLock = Any()

    private fun current(run: CopyRunState, token: Long, pass: Int) = run.token == token && run.pass == pass

    // Writes what `next` makes of the run, unless it answers null, comparing
    // and setting so a write that raced another is made again on its result.
    private inline fun change(next: (CopyRunState) -> CopyRunState?) {
        while (true) {
            val run = _run.value ?: return
            val changed = next(run) ?: return
            if (_run.compareAndSet(run, changed)) {
                seen(changed)
                return
            }
        }
    }
}
