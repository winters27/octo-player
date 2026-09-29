package app.winters.octo.desktop.system

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

// The files Octo keeps its own state in (the queue, live lists, the jump
// list, waiting plays, the play log): saved so that a crash at any point
// leaves either the old text or the new, and so that something else having
// a file open (a virus scanner, a backup, anything reading it) never loses
// a save in silence.
//
// A save writes X.tmp, then moves it to X.next in one step: from then on
// the new text is kept. X.next is then moved over X in one step. Windows
// refuses that while anything has X open, so it is tried again for a while;
// if X stays open, X.next stays beside it, and it is what a read finds
// until a later save gets it into place. An empty file counts as none.

private val filesLog: System.Logger = System.getLogger("octo.files")

// How long a file that something else has open is waited for.
private const val BUSY_FILE_WAIT_MS = 2_000L

// The points a save passes, for tests that stop it part way.
internal enum class SaveStep { Written, Kept, Placed }

private fun beside(file: File, suffix: String): Path = File(file.parentFile, file.name + suffix).toPath()

// Runs `step`, trying again for a while when Windows refuses it: it will
// not move over a file something else has open, nor remove one read through
// java.io, and may refuse to open one that is being removed. A missing file
// is an answer, not a refusal. Throws if it never goes through.
private fun <T> patiently(step: () -> T): T {
    val giveUpAt = System.nanoTime() + BUSY_FILE_WAIT_MS * 1_000_000
    while (true) {
        try {
            return step()
        } catch (e: IOException) {
            if (e is NoSuchFileException || System.nanoTime() > giveUpAt) throw e
            Thread.sleep(10)
        }
    }
}

// Where the newest text of `file` is now.
private fun newest(file: File): Path = beside(file, ".next").takeIf(Files::exists) ?: file.toPath()

// The text of `file`, or null when there is none. Read through NIO, so the
// file can still be moved or removed while it is read. Throws if it cannot
// be read even after waiting.
internal fun readWhole(file: File): String? {
    for (path in listOf(beside(file, ".next"), file.toPath())) {
        val bytes = try {
            patiently { Files.readAllBytes(path) }
        } catch (e: NoSuchFileException) {
            continue
        }
        return String(bytes, Charsets.UTF_8).takeIf { it.isNotEmpty() }
    }
    return null
}

// The same, as null when it cannot be read.
internal fun readSaved(file: File): String? = runCatching { readWhole(file) }.getOrNull()

// The same, for state that is later saved whole over the file: a file that
// cannot be read is logged, once until it saves, and answered as a failure,
// so the caller can leave it alone rather than save over it with nothing.
internal fun readForSaving(file: File): Result<String?> =
    try {
        Result.success(readWhole(file))
    } catch (e: IOException) {
        if (synchronized(failing) { failing.add(file.path) }) filesLog.log(System.Logger.Level.WARNING, "Could not read ${file.name}", e)
        Result.failure(e)
    }

// Puts `text` in `file`, or empties it when `text` is null. Throws if the
// new text could not be kept; once it returns, the new text is what a read
// finds, even after a crash.
internal fun writeWhole(file: File, text: String?, step: (SaveStep) -> Unit = {}) {
    val target = file.toPath()
    val next = beside(file, ".next")
    val temp = beside(file, ".tmp")
    if (text == null && !Files.exists(target) && !Files.exists(next)) {
        Files.deleteIfExists(beside(file, ".new"))
        return
    }
    file.parentFile?.mkdirs()
    patiently {
        FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val bytes = ByteBuffer.wrap(text.orEmpty().toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
    }
    step(SaveStep.Written)
    patiently { Files.move(temp, next, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
    step(SaveStep.Kept)
    // Left by Octo before these saves, which renamed X.new over X.
    runCatching { Files.deleteIfExists(beside(file, ".new")) }
    try {
        patiently {
            if (text == null) {
                Files.deleteIfExists(target)
                Files.delete(next)
            } else {
                Files.move(next, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        }
        step(SaveStep.Placed)
    } catch (e: IOException) {
        // Still open elsewhere: the new text stays kept beside it.
    }
}

// Adds `text` to the end of `file`, wherever its newest text is.
internal fun appendWhole(file: File, text: String) {
    file.parentFile?.mkdirs()
    patiently {
        Files.write(newest(file), text.toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}

// Files that could not be saved, so each is logged once until it saves.
private val failing = HashSet<String>()

// writeWhole for state that can wait for the next save: a save that fails
// is logged, once until that file saves again, and never thrown. Answers
// whether it was kept.
internal fun saveWhole(file: File, text: String?): Boolean =
    try {
        writeWhole(file, text)
        synchronized(failing) { failing.remove(file.path) }
        true
    } catch (e: IOException) {
        if (synchronized(failing) { failing.add(file.path) }) filesLog.log(System.Logger.Level.WARNING, "Could not save ${file.name}", e)
        false
    }
