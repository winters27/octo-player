package app.winters.octo.server

import java.util.Locale

// The words every app says about a copy of a server's library.

// The card's and the notice's title while a copy runs.
fun copyTitle(arriving: Boolean): String = if (arriving) "Bringing in your music" else "Updating your library"

fun copyHeroText(server: String): String = "From $server. Keep using Octo while it works."

const val COPY_SAVING_TITLE = "Almost done"
const val COPY_SAVING_TEXT = "Saving your library. Your music shows up in a moment."

const val COPY_DONE_TITLE = "Your music is here"
const val COPY_FAILED_TITLE = "Couldn't bring in your music"

// Said in empty lists and the car while a first copy is due.
const val COPY_ON_ITS_WAY = "Your music is on its way"
const val COPY_LIKES_ON_ITS_WAY = "Your likes arrive with your music"

// A launcher shortcut tapped while a first copy is due.
const val COPY_STILL_ON_ITS_WAY = "Your music is still on its way"

// The copy notice, cut off by the system's time limit.
const val COPY_OPEN_TO_FINISH = "Open Octo to finish bringing in your music."

// Asking to allow notifications, once, beside the copy.
const val COPY_NOTICE_ASK = "Get a notification while it copies"

// A step as a row in the list of steps.
fun copyStepLabel(step: CopyStep, server: String): String = when (step) {
    CopyStep.Connect -> "Connect to $server"
    CopyStep.Read -> "Read your songs"
    CopyStep.Build -> "Build albums"
    CopyStep.Add, CopyStep.Save -> "Add to your library"
    CopyStep.Settle -> "Sync likes and playlists"
}

// The desktop app reads the whole library on every launch, so it says so
// plainly, never that music is arriving.
const val READ_TITLE = "Reading your library"
const val READ_FAILED_TITLE = "Couldn't read your library"

// A step of a read into memory as a row: building is one step.
fun readStepLabel(step: CopyStep, server: String): String = when (step) {
    CopyStep.Build, CopyStep.Add, CopyStep.Save -> "Build your library"
    else -> copyStepLabel(step, server)
}

// What a read into memory is doing now, as one line.
fun readDoing(step: CopyStep, server: String): String = when (step) {
    CopyStep.Build, CopyStep.Add, CopyStep.Save -> "Building your library"
    else -> copyDoing(step, server)
}

// What the copy is doing now, as one line.
fun copyDoing(step: CopyStep, server: String): String = when (step) {
    CopyStep.Connect -> "Connecting to $server"
    CopyStep.Read -> "Reading your songs"
    CopyStep.Build -> "Building albums"
    CopyStep.Add -> "Adding to your library"
    CopyStep.Save -> "Saving your library"
    CopyStep.Settle -> "Syncing likes and playlists"
}

// The step's count: "48,210 of 200,000" once the total is known, "48,210
// so far" before; null for steps without one.
fun copyCount(step: CopyStep, done: Long, shownOf: Long?): String? = when (step) {
    CopyStep.Read, CopyStep.Build, CopyStep.Add ->
        if (shownOf != null) "${grouped(done)} of ${grouped(shownOf)}" else "${grouped(done)} so far"
    else -> null
}

fun copyCount(run: CopyRunState): String? = copyCount(run.step, run.done, run.shownOf)

// The copy in one line, for TalkBack, the notice and the car: "Reading your
// songs, 48,210 of 200,000".
fun copyDescription(run: CopyRunState): String {
    val doing = copyDoing(run.step, run.server)
    return copyCount(run)?.let { "$doing, $it" } ?: doing
}

// The done card's line.
fun copyDoneText(songs: Int, albums: Int, server: String): String =
    "${counted(songs.toLong(), "song")} and ${counted(albums.toLong(), "album")} from $server."

// Where a failed copy stopped, or null when the reason says it all.
fun copyStoppedAt(step: CopyStep, done: Long): String? = when (step) {
    CopyStep.Read -> if (done > 0) "Stopped after reading ${counted(done, "song")}." else null
    CopyStep.Build -> "Stopped after building ${counted(done, "album")}."
    CopyStep.Add -> "Stopped while adding to your library."
    CopyStep.Save -> "Stopped while saving your library."
    CopyStep.Connect, CopyStep.Settle -> null
}

// The settings index line while a copy runs: "Syncing, 15%" while a whole
// copy can tell how far it is, "Syncing" otherwise.
fun copyStatus(run: CopyRunState): String {
    val fraction = run.fraction
    return if (run.whole && run.step != CopyStep.Settle && fraction != null) "Syncing, ${percent(fraction)}%" else "Syncing"
}

// A fraction as a whole percent, rounded down (a hair added so 0.29 is 29,
// not the 28.999998 a float makes of it).
fun percent(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100f + 0.0001f).toInt().coerceAtMost(100)

private fun grouped(n: Long): String = "%,d".format(Locale.ENGLISH, n)

private fun counted(n: Long, one: String): String = "${grouped(n)} ${if (n == 1L) one else one + "s"}"
