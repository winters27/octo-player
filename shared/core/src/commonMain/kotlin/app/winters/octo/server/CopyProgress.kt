package app.winters.octo.server

import app.winters.octo.subsonic.LIBRARY_PAGE

// How a copy of a server's library goes, in order: reaching the server,
// reading its songs, building albums from them, adding them to the library
// (saving is the end of adding, when the library takes the copy in one go),
// and the steps after a copy (likes, playlists, downloads).
enum class CopyStep { Connect, Read, Build, Add, Save, Settle }

// How far one copy is. `token` is the job, `pass` the copy within it (a job
// copies again when a whole copy was asked for while a lighter one ran).
// `arriving`: no whole copy of this address was ever taken in, so none of
// its music is here yet. `whole`: the pass reads the library (Read to Save),
// not only the steps after a copy. `done` of `of` count the step's own
// units (songs; albums while building), `of` null while unknown.
// `fraction` is of the whole copy, null while it cannot be told. `artwork`
// is the cover of an album that just came. `songs` and `albums` are the
// copy's counts once it is built. `dismissed`: the done card was put away
// while the steps after the copy ran.
data class CopyRunState(
    val token: Long,
    val pass: Int,
    val account: String,
    val server: String,
    val arriving: Boolean,
    val whole: Boolean,
    val step: CopyStep,
    val done: Long,
    val of: Long?,
    val fraction: Float?,
    val artwork: String?,
    val songs: Int,
    val albums: Int,
    val dismissed: Boolean,
) {
    // The total as shown: never below what is done, so a server that lists
    // fewer songs than it gives never reads "195,000 of 190,000".
    val shownOf: Long? get() = of?.let { maxOf(it, done) }

    // The whole copy is in the library; only the steps after it run.
    val swapped: Boolean get() = whole && step == CopyStep.Settle
}

// How the last first copy of a server ended, for the card that says so.
sealed interface CopyOutcome {
    val token: Long
    val account: String
    val server: String

    // The music is in the library. `stepsProblem` says why a step after the
    // copy failed, which leaves the music in place.
    data class Arrived(
        override val token: Long,
        override val account: String,
        override val server: String,
        val songs: Int,
        val albums: Int,
        val stepsProblem: String?,
    ) : CopyOutcome

    // The copy stopped before its music was in: why, and where.
    data class Failed(
        override val token: Long,
        override val account: String,
        override val server: String,
        val reason: String,
        val step: CopyStep,
        val done: Long,
    ) : CopyOutcome
}

// How much of a whole copy each step takes, Read to Add; saving takes the
// rest. Each step starts where the one before it ends. Measured on a copy
// of 200,000 songs from a server on the same computer: read 0.23, build
// 0.22, add 0.38, save 0.17.
const val READ_SHARE = 0.23f
const val BUILD_SHARE = 0.22f
const val ADD_SHARE = 0.38f
const val SAVE_SHARE = 1f - READ_SHARE - BUILD_SHARE - ADD_SHARE

// How a Save step counts how far it is: `done` of SAVE_PARTS of the save,
// once its groups are planned. A save stopped part way and resumed goes on
// from what was in, so its count never moves back.
const val SAVE_PARTS = 1_000_000L

// How far a whole copy is at `done` of `of` in `step`, or null while it
// cannot be told (reaching the server, reading before the total is known).
// Saving holds at its start until its groups are planned, then moves
// through its share as they go in.
fun copyFraction(step: CopyStep, done: Long, of: Long?): Float? = when (step) {
    CopyStep.Connect -> null
    CopyStep.Read -> of?.let { READ_SHARE * readFraction(done, it) }
    CopyStep.Build -> READ_SHARE + BUILD_SHARE * part(done, of)
    CopyStep.Add -> READ_SHARE + BUILD_SHARE + ADD_SHARE * part(done, of)
    CopyStep.Save -> READ_SHARE + BUILD_SHARE + ADD_SHARE + SAVE_SHARE * part(done, of)
    CopyStep.Settle -> 1f
}

// How far reading is: songs read of the listed albums' songs, leaving room
// for one more page, so it moves on every page and reaches its end only
// when the read ends, even when the server lists fewer songs than it gives.
fun readFraction(done: Long, of: Long): Float = done.toFloat() / maxOf(of, done + LIBRARY_PAGE)

private fun part(done: Long, of: Long?): Float = if (of == null || of <= 0) 0f else (done.toFloat() / of).coerceIn(0f, 1f)

// The steps a phone app shows, in order; saving shows as the end of adding.
val COPY_STEPS = listOf(CopyStep.Connect, CopyStep.Read, CopyStep.Build, CopyStep.Add)

// The steps an app that reads the library into memory shows (the desktop
// app): building the library is one step.
val READ_STEPS = listOf(CopyStep.Connect, CopyStep.Read, CopyStep.Build)

// How much of such a read the reading takes; building holds after it.
const val READ_INTO_MEMORY_SHARE = 0.9f

// How far a read into memory is, or null while it cannot be told.
fun readIntoMemoryFraction(step: CopyStep, done: Long, of: Long?): Float? = when (step) {
    CopyStep.Connect -> null
    CopyStep.Read -> of?.let { READ_INTO_MEMORY_SHARE * readFraction(done, it) }
    CopyStep.Build, CopyStep.Add, CopyStep.Save -> READ_INTO_MEMORY_SHARE
    CopyStep.Settle -> 1f
}

// Where a shown step stands while the copy is at `at`.
enum class StepState { Done, Now, Later }

fun stepState(shown: CopyStep, at: CopyStep): StepState {
    val now = if (at == CopyStep.Save) CopyStep.Add else at
    return when {
        shown == now -> StepState.Now
        shown < now -> StepState.Done
        else -> StepState.Later
    }
}
