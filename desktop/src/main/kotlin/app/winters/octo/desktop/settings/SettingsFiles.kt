package app.winters.octo.desktop.settings

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

// The time the settings store reads, and its waiting; tests make both
// instant.
interface SettingsClock {
    fun now(): Long

    fun sleep(ms: Long)

    // The computer's own clock.
    object Real : SettingsClock {
        override fun now(): Long = System.currentTimeMillis()

        override fun sleep(ms: Long) = Thread.sleep(ms)
    }
}

// How the settings file stands: written (Free); held by another program,
// every change kept beside it in the pending file (Kept); or held with the
// pending file refused too, so changes made now may be lost (Unkept).
enum class SettingsHold { Free, Kept, Unkept }

// What keeps Octo from its settings at the start: another program has the
// file open (InUse); this account is not allowed to read it (NoAccess); or
// it is damaged and Octo cannot move it aside to start fresh (Damaged).
enum class HeldAtStart { InUse, NoAccess, Damaged }

// What opening the settings file gave: the store, or a file that holds Octo
// (which file, and why), looked at once more by `retry`.
sealed interface SettingsOpen {
    class Opened(val store: SettingsStore) : SettingsOpen

    class Held(val why: HeldAtStart, val file: File, val retry: () -> SettingsOpen) : SettingsOpen
}

// The disk as the settings store uses it. The tests stand in for it to hold
// a file the way another program can: refusing reads, or letting it be read
// while refusing to replace it.
internal interface SettingsDisk {
    // False only when the file is surely not there.
    fun exists(file: File): Boolean

    // The file's bytes; AccessDeniedException when this account is not
    // allowed to read it, another IOException while another program holds it.
    fun read(file: File): ByteArray

    // The bytes to a temporary file beside `file`, flushed to the disk, then
    // moved over `file` in one step, so neither a crash nor a power cut
    // leaves half a file. IOException when any step fails.
    fun replace(file: File, bytes: ByteArray)

    // True once the file is gone.
    fun delete(file: File): Boolean

    // True once `file` is at `to`.
    fun move(file: File, to: File): Boolean
}

internal object RealSettingsDisk : SettingsDisk {
    // A file the account cannot even look at counts as there, so its read
    // says why it cannot be had.
    override fun exists(file: File): Boolean = !Files.notExists(file.toPath())

    override fun read(file: File): ByteArray = Files.readAllBytes(file.toPath())

    // A move that is refused, for any reason, leaves the old file as it
    // was and no temporary file beside it.
    override fun replace(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(temp.toPath()) }
            throw e
        }
    }

    override fun delete(file: File): Boolean = file.delete() || !file.exists()

    override fun move(file: File, to: File): Boolean = runCatching { Files.move(file.toPath(), to.toPath()) }.isSuccess
}

// The file a held settings file's whole state is kept in until it can be
// written: settings.pending.json beside settings.json.
internal fun pendingFileFor(file: File): File = File(file.parentFile, file.nameWithoutExtension + ".pending.json")

// The file beside settings.json with the Between songs settings as Octo
// last wrote them there: settings.between-songs.json.
internal fun betweenSongsFileFor(file: File): File = File(file.parentFile, file.nameWithoutExtension + ".between-songs.json")

// The main file's bytes as a pending file names the file it began from.
internal fun settingsHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

// What a pending file names when it began with no main file there.
internal const val NO_MAIN_FILE = ""

// Why a file is set aside: it is not settings, or it is a pending file older
// than the main file.
internal const val SET_ASIDE_UNREADABLE = "unreadable"
internal const val SET_ASIDE_OLDER = "older"

// Moves `at` aside as <name>.<why>-<time>.json, never deleting it; the name
// it now has, or null when it could not be moved.
internal fun moveAside(disk: SettingsDisk, clock: SettingsClock, at: File, why: String): String? {
    val aside = File(at.parentFile, at.nameWithoutExtension + "." + why + "-" + clock.now() + ".json")
    return if (disk.move(at, aside)) aside.name else null
}

// What a store starts from: its settings; the main file on disk as it
// stands, its saveCount (which the next write raises) and the hash of its
// bytes, 0 and NO_MAIN_FILE when there is none; whether to write them at
// once (they came from the pending file, or an older file that chose its
// Between songs setting); whether a pending file is left to delete once the
// main file is written; why a pending file that could not be moved aside is
// still to go (it is moved aside later, never deleted or written over); and
// the name an unreadable file was set aside under when the store starts
// fresh because of it.
internal class SettingsStart(
    val settings: AppSettings,
    val saveCount: Long,
    val mainHash: String = NO_MAIN_FILE,
    val write: Boolean,
    val pendingLeft: Boolean,
    val stuckPending: String? = null,
    val setAside: String?,
)

// Opening the settings file: settings.json and the pending file beside it,
// looked at together, again every `retryMs` while either is held, for
// `waitMs` at most. The app never opens on the defaults while a settings
// file it cannot read is there.
//
// The pending file is the newer state only when it began from the very file
// read: its saveCount is the main file's (no file and a file without a
// count are 0) and it names the hash of the main file's bytes. One that does
// not match (its delete failed and later sessions wrote, or the main file
// was written outside Octo, an older Octo among them) is set aside. A main
// file that is not settings is set aside and the pending file used. A file
// that does not parse is read again: it is set aside only when two reads
// give the same bytes, so a file caught while another program writes it is
// never taken for a broken one.
internal class SettingsOpening(
    private val file: File,
    private val writeDelayMs: Long,
    private val clock: SettingsClock,
    private val waitMs: Long,
    private val retryMs: Long,
    private val writeRetryMs: Long,
    private val disk: SettingsDisk,
) {
    private val pending = pendingFileFor(file)
    private val betweenSongs = betweenSongsFileFor(file)

    // The bytes of the last read of each file that did not parse.
    private var badMain: ByteArray? = null
    private var badPending: ByteArray? = null

    // What a look at one file found.
    private sealed interface Seen {
        data object Absent : Seen

        // Not to be had yet, and why: being written (bytes that did not
        // parse, read again), held or refused.
        class Busy(val why: HeldAtStart, val file: File) : Seen

        data object Unreadable : Seen

        class Good(val read: ReadSettings, val hash: String) : Seen
    }

    // What a look at both files gave: the start, or the file that holds
    // Octo and why.
    sealed interface Look {
        class Ready(val start: SettingsStart) : Look

        class Waiting(val why: HeldAtStart, val file: File) : Look
    }

    // The store, or the file held once the wait is over.
    fun open(): SettingsOpen = opened(waitForLook())

    // What the files give within the wait, or null while still held.
    fun waitForStart(): SettingsStart? = (waitForLook() as? Look.Ready)?.start

    private fun waitForLook(): Look {
        val until = clock.now() + waitMs
        while (true) {
            val seen = look()
            if (seen is Look.Ready || clock.now() >= until) return seen
            clock.sleep(retryMs)
        }
    }

    private fun opened(seen: Look): SettingsOpen = when (seen) {
        is Look.Ready -> SettingsOpen.Opened(SettingsStore(file, writeDelayMs, writeRetryMs, disk, clock, seen.start))
        is Look.Waiting -> SettingsOpen.Held(seen.why, seen.file) { opened(look()) }
    }

    // One look at both files.
    @Synchronized
    fun look(): Look {
        val main = see(file, badMain, betweenSongs) { badMain = it }
        if (main is Seen.Busy) return Look.Waiting(main.why, main.file)
        val kept = see(pending, badPending, null) { badPending = it }
        if (kept is Seen.Busy) return Look.Waiting(kept.why, kept.file)
        val aside = if (main == Seen.Unreadable) moveAside(disk, clock, file, SET_ASIDE_UNREADABLE) ?: return Look.Waiting(HeldAtStart.Damaged, file) else null
        // The main file on disk now: none once it is set aside.
        val good = main as? Seen.Good
        val count = good?.read?.saveCount ?: 0L
        val hash = good?.hash ?: NO_MAIN_FILE
        var stuck: String? = null
        val used = when (kept) {
            is Seen.Good -> if (main == Seen.Unreadable || kept.read.beganFrom(count, hash)) {
                kept.read
            } else {
                if (moveAside(disk, clock, pending, SET_ASIDE_OLDER) == null) stuck = SET_ASIDE_OLDER
                null
            }
            Seen.Unreadable -> {
                if (moveAside(disk, clock, pending, SET_ASIDE_UNREADABLE) == null) stuck = SET_ASIDE_UNREADABLE
                null
            }
            else -> null
        }
        val read = used ?: good?.read
        return Look.Ready(
            SettingsStart(
                settings = read?.settings ?: AppSettings().settled(),
                saveCount = count,
                mainHash = hash,
                write = used != null || read?.migrated?.untouched == false,
                pendingLeft = used != null,
                stuckPending = stuck,
                setAside = aside.takeIf { used == null },
            ),
        )
    }

    // One file: its settings, read with the Between songs file `choice` when
    // it is an older Octo's.
    private fun see(at: File, bad: ByteArray?, choice: File?, remember: (ByteArray?) -> Unit): Seen {
        val bytes = try {
            if (!disk.exists(at)) return Seen.Absent
            disk.read(at)
        } catch (e: IOException) {
            return Seen.Busy(heldBy(e), at)
        }
        val read = try {
            decodeSettings(bytes.toString(Charsets.UTF_8)) { choice?.let(::readBetweenSongsFile) }
        } catch (e: IOException) {
            return Seen.Busy(heldBy(e), choice ?: at)
        } catch (e: IllegalArgumentException) {
            // A SerializationException is one too.
            return unreadable(bytes, bad, remember, at)
        }
        remember(null)
        return Seen.Good(read, settingsHash(bytes))
    }

    // The Between songs file, or null when there is none or it is not one.
    private fun readBetweenSongsFile(at: File): BetweenSongsFile? = if (disk.exists(at)) decodeBetweenSongsFile(disk.read(at)) else null

    private fun heldBy(e: IOException): HeldAtStart = if (e is AccessDeniedException) HeldAtStart.NoAccess else HeldAtStart.InUse

    private fun unreadable(bytes: ByteArray, bad: ByteArray?, remember: (ByteArray?) -> Unit, at: File): Seen {
        if (bad != null && bad.contentEquals(bytes)) return Seen.Unreadable
        remember(bytes)
        return Seen.Busy(HeldAtStart.InUse, at)
    }
}
