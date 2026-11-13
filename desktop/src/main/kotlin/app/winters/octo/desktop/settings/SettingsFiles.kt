package app.winters.octo.desktop.settings

import kotlinx.serialization.SerializationException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

// What opening the settings file gave: the store, or a file another program
// holds, looked at once more by `retry`.
sealed interface SettingsOpen {
    class Opened(val store: SettingsStore) : SettingsOpen

    class Held(val retry: () -> SettingsOpen) : SettingsOpen
}

// The disk as the settings store uses it. The tests stand in for it to hold
// a file the way another program can: refusing reads, or letting it be read
// while refusing to replace it.
internal interface SettingsDisk {
    fun exists(file: File): Boolean

    // The file's bytes; IOException while another program holds it.
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
    override fun exists(file: File): Boolean = file.exists()

    override fun read(file: File): ByteArray = file.readBytes()

    override fun replace(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun delete(file: File): Boolean = file.delete() || !file.exists()

    override fun move(file: File, to: File): Boolean = runCatching { Files.move(file.toPath(), to.toPath()) }.isSuccess
}

// The file a held settings file's whole state is kept in until it can be
// written: settings.pending.json beside settings.json.
internal fun pendingFileFor(file: File): File = File(file.parentFile, file.nameWithoutExtension + ".pending.json")

// What a store starts from: its settings; the saveCount of the file they
// were read against, which the next write raises; whether to write them at
// once (they came from the pending file, or an older file that chose its
// Between songs setting); whether a pending file is left to delete once the
// main file is written; and the name an unreadable file was set aside
// under when the store starts fresh because of it.
internal class SettingsStart(
    val settings: AppSettings,
    val saveCount: Long,
    val write: Boolean,
    val pendingLeft: Boolean,
    val setAside: String?,
)

// Opening the settings file: settings.json and the pending file beside it,
// looked at together, again every `retryMs` while either is held, for
// `waitMs` at most. The app never opens on the defaults while a settings
// file it cannot read is there.
//
// The pending file is the newer state only when it began from the very file
// read (its saveCount is the main file's; no file and a file without a
// count are 0); one that does not match (its delete failed and later
// sessions wrote, or the main file was replaced outside Octo) is set aside.
// A main file that is not settings is set aside and the pending file used.
// A file that does not parse is read again: it is set aside only when two
// reads give the same bytes, so a file caught while another program writes
// it is never taken for a broken one.
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

    // The bytes of the last read of each file that did not parse.
    private var badMain: ByteArray? = null
    private var badPending: ByteArray? = null

    // What a look at one file found.
    private sealed interface Seen {
        data object Absent : Seen

        data object Busy : Seen

        data object Unreadable : Seen

        class Good(val read: ReadSettings) : Seen
    }

    // The store, or the file held once the wait is over.
    fun open(): SettingsOpen = waitForStart()?.let { SettingsOpen.Opened(store(it)) } ?: held()

    // What the files give within the wait, or null while still held.
    fun waitForStart(): SettingsStart? {
        val until = clock.now() + waitMs
        while (true) {
            look()?.let { return it }
            if (clock.now() >= until) return null
            clock.sleep(retryMs)
        }
    }

    private fun held(): SettingsOpen = SettingsOpen.Held { look()?.let { SettingsOpen.Opened(store(it)) } ?: held() }

    private fun store(start: SettingsStart) = SettingsStore(file, writeDelayMs, writeRetryMs, disk, start)

    // One look at both files: what they give, or null while either is held.
    @Synchronized
    fun look(): SettingsStart? {
        val main = see(file, badMain) { badMain = it }
        if (main == Seen.Busy) return null
        val kept = see(pending, badPending) { badPending = it }
        if (kept == Seen.Busy) return null
        val aside = if (main == Seen.Unreadable) setAside(file, UNREADABLE) ?: return null else null
        val count = (main as? Seen.Good)?.read?.saveCount ?: 0L
        // A pending file that could not be moved aside is deleted after the
        // next write instead.
        var stuck = false
        val used = when (kept) {
            is Seen.Good -> if (main == Seen.Unreadable || kept.read.saveCount == count) {
                kept.read
            } else {
                stuck = setAside(pending, OLDER) == null
                null
            }
            Seen.Unreadable -> {
                stuck = setAside(pending, UNREADABLE) == null
                null
            }
            else -> null
        }
        val read = used ?: (main as? Seen.Good)?.read
        return SettingsStart(
            settings = read?.settings ?: AppSettings().settled(),
            saveCount = used?.saveCount ?: count,
            write = used != null || read?.migrated?.untouched == false,
            pendingLeft = used != null || stuck,
            setAside = aside.takeIf { used == null },
        )
    }

    private fun see(at: File, bad: ByteArray?, remember: (ByteArray?) -> Unit): Seen {
        if (!disk.exists(at)) return Seen.Absent
        val bytes = try {
            disk.read(at)
        } catch (e: IOException) {
            return Seen.Busy
        }
        return try {
            Seen.Good(decodeSettings(bytes.toString(Charsets.UTF_8))).also { remember(null) }
        } catch (e: SerializationException) {
            unreadable(bytes, bad, remember)
        } catch (e: IllegalArgumentException) {
            unreadable(bytes, bad, remember)
        }
    }

    private fun unreadable(bytes: ByteArray, bad: ByteArray?, remember: (ByteArray?) -> Unit): Seen {
        if (bad != null && bad.contentEquals(bytes)) return Seen.Unreadable
        remember(bytes)
        return Seen.Busy
    }

    // Moves `at` aside, never deleting it; the name it now has, or null.
    private fun setAside(at: File, why: String): String? {
        val aside = File(at.parentFile, at.nameWithoutExtension + "." + why + "-" + clock.now() + ".json")
        return if (disk.move(at, aside)) aside.name else null
    }

    private companion object {
        const val UNREADABLE = "unreadable"
        const val OLDER = "older"
    }
}
