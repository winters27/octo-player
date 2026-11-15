package app.winters.octo.offline

import app.winters.octo.playback.StreamRequest
import java.io.File
import java.io.IOException

// What a download that has arrived is, by its bytes: the type it is named
// and labeled as, whether it is smaller than the server's file, and whether
// its AAC is put into an M4A first (`rewrap`). `label` is what it is once
// finished.
data class Arrival(val label: String?, val madeSmaller: Boolean, val rewrap: Boolean)

// Decides what an arrived download is. A file asked for as it is keeps the
// type its listing names when its bytes are that kind (as every download
// did), and is a smaller copy when its bytes are another kind (a family
// limit had the server make one). A file asked for smaller is named by its
// bytes, else by what the server said, else by what was asked. Any ADTS
// AAC is put into an M4A, so it has its real length.
fun arrivalOf(head: ByteArray, count: Int, answeredType: String?, listed: String?, request: StreamRequest): Arrival {
    val sniffed = sniffAudio(head, count)
    val answered = answeredType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
    if (request.maxKbps == null && (sniffed == null || sameAudioKind(listed, sniffed))) {
        if (sniffed == "audio/aac") return Arrival("audio/mp4", madeSmaller = false, rewrap = true)
        return Arrival(listed ?: sniffed ?: answered, madeSmaller = false, rewrap = false)
    }
    val label = sniffed ?: answered ?: request.mimeType(listed)
    val rewrap = label == "audio/aac"
    return Arrival(if (rewrap) "audio/mp4" else label, madeSmaller = true, rewrap = rewrap)
}

// Turns the arrived `part` into the finished `target`: the AAC put into an
// M4A first when `rewrap`, then renamed. `stillWanted` is asked after the
// last slow step and before the rename, so a download removed meanwhile
// leaves no file behind. False when it was removed. Every part goes either
// way.
suspend fun finishArrival(part: File, target: File, rewrap: Boolean, stillWanted: suspend () -> Boolean): Boolean {
    val made = File(target.parentFile, target.name + ".part")
    try {
        val ready = if (rewrap) {
            if (!AdtsToM4a.rewrap(part, made)) throw IOException("Could not save the file")
            made
        } else {
            part
        }
        if (!stillWanted()) return false
        if (target.exists()) target.delete()
        if (!ready.renameTo(target)) throw IOException("Could not save the file")
        return true
    } finally {
        made.delete()
        part.delete()
    }
}

// The pieces of downloads left in the downloads folder by a download the
// app closed on: every `.part` file at its top. Folders (the animated art,
// the removed files waiting out their undo) are never looked in.
fun leftoverParts(dir: File): List<File> = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".part") }.orEmpty()
