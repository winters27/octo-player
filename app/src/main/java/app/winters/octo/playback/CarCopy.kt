package app.winters.octo.playback

import androidx.media3.common.MediaItem
import app.winters.octo.server.CopyRunState
import app.winters.octo.server.CopyWait
import app.winters.octo.server.percent

// The id of the first copy's status row. No list or song has it, so the
// row neither opens nor plays.
const val COPY_STATUS_ID = "copy"

// What a car's open lists hear of a first copy: a change of step or of
// whole percent while the server's music is still to come (about a hundred
// a copy), and its end.
internal fun carCopyKey(wait: CopyWait?, run: CopyRunState?): List<Any?>? {
    if (wait?.awaiting != true) return null
    val shown = run?.takeIf { it.whole } ?: return emptyList()
    return listOf(shown.step, shown.fraction?.let(::percent))
}

// What an empty library list of the car answers while the server's music
// is still to come: the copy's status row (`status`, null when nothing is
// to come); a list with items, or one the copy does not fill, answers its own.
internal fun carCopyAnswer(node: CarNode, count: Int, status: () -> MediaItem?): MediaItem? =
    if (count == 0 && CarInput.Copy in carInputsOf(node)) status() else null

// While a copy runs, a car hears of library changes only once it ends; a
// first copy's music is told as soon as it is in the library.
internal fun carHoldsLibrary(syncing: Boolean, run: CopyRunState?): Boolean =
    syncing && !(run != null && run.arriving && run.swapped)
