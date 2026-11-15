package app.winters.octo.desktop.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SettingsWriteTest {
    @get:Rule val folder = TemporaryFolder()

    private val dir get() = File(folder.root, "octo")
    private fun file() = File(dir, SettingsStore.FILE_NAME)
    private val kept = SavedServer("https://music.test/", "winters", id = "w1")
    private val user: String = System.getProperty("user.name")

    // A start on the files as they are now: each change is written at once,
    // with no writer thread and no exit write.
    private fun started(): SettingsStore = (SettingsStore.openOn(HeldDisk(), file()) as SettingsOpen.Opened).store

    private fun seeded(): SettingsStore = started().also { it.update { s -> s.copy(servers = listOf(kept), activeServer = "w1") } }

    // A row over a store whose timed write and retry both wait a minute, so
    // only flushNow and flush write the files. Whatever the body does, every
    // refusal is lifted and what waits is written after it, so the store's
    // later write and its exit write find nothing to do.
    private fun row(body: (HeldDisk, SettingsStore) -> Unit) {
        val disk = HeldDisk()
        val store = (SettingsStore.openOn(disk, file(), writeDelayMs = 60_000, writeRetryMs = 60_000) as SettingsOpen.Opened).store
        try {
            body(disk, store)
        } finally {
            disk.refuseReplace.clear()
            store.flush()
        }
        assertEquals(SettingsHold.Free, store.hold.value)
    }

    private fun icacls(target: File, vararg args: String) {
        val process = ProcessBuilder(listOf("icacls", target.absolutePath) + args).redirectErrorStream(true).start()
        val said = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "icacls ${args.joinToString(" ")}: $said" }
    }

    // Red when flushNow leaves the change to the timed write, or says the
    // file does not hold what it just wrote.
    @Test
    fun flushNowWritesWhatWaitsAtOnce() = row { _, store ->
        store.update { it.copy(servers = listOf(kept)) }
        assertFalse("the timed write is a minute away", file().exists())
        assertTrue(store.flushNow())
        assertEquals(SettingsHold.Free, store.hold.value)
        assertEquals(listOf(kept), started().current.servers)
        assertTrue("nothing waits and the file holds it", store.flushNow())
    }

    // The settings file held: the pending file beside it holds the change,
    // so the next start reads it. Red when flushNow is false here.
    @Test
    fun withTheFileHeldFlushNowIsTrueOnceThePendingFileHoldsIt() {
        seeded()
        row { disk, store ->
            disk.refuseReplace += SettingsStore.FILE_NAME
            store.update { it.copy(songSort = "b") }
            assertTrue(store.flushNow())
            assertEquals(SettingsHold.Kept, store.hold.value)
            assertEquals("b", started().current.songSort)
        }
    }

    // Red when flushNow is true while neither file holds the change.
    @Test
    fun withBothFilesRefusedFlushNowIsFalse() {
        seeded()
        row { disk, store ->
            disk.refuseReplace += setOf(SettingsStore.FILE_NAME, pendingFileFor(file()).name)
            store.update { it.copy(songSort = "b") }
            assertFalse(store.flushNow())
            assertEquals(SettingsHold.Unkept, store.hold.value)
        }
    }

    // A pending file from before that could not be moved aside is never
    // written over, so with the settings file held too nothing holds the
    // change, and the next start reads the file as it was. Red when
    // flushNow is true here.
    @Test
    fun withAnOlderPendingFileThatCannotBeMovedFlushNowIsFalse() {
        seeded()
        val older = encodeSettings(AppSettings(), 0, NO_MAIN_FILE)
        pendingFileFor(file()).writeBytes(older)
        val disk = HeldDisk().apply { refuseMove += pendingFileFor(file()).name }
        val store = (SettingsStore.openOn(disk, file(), writeDelayMs = 60_000, writeRetryMs = 60_000) as SettingsOpen.Opened).store
        try {
            disk.refuseReplace += SettingsStore.FILE_NAME
            store.update { it.copy(songSort = "b") }
            assertFalse(store.flushNow())
            assertEquals(SettingsHold.Unkept, store.hold.value)
            assertArrayEquals("never written over", older, pendingFileFor(file()).readBytes())
            val next = started().current
            assertEquals("the next start reads the file as it was", listOf(kept), next.servers)
            assertNull(next.songSort)
        } finally {
            disk.refuseReplace.clear()
            disk.refuseMove.clear()
            store.flush()
        }
        assertEquals(SettingsHold.Free, store.hold.value)
    }

    // Open without my settings: nothing is ever written, so flushNow is
    // never true.
    @Test
    fun aReadOnlyRunsFlushNowIsFalse() {
        val store = SettingsStore.readOnly()
        assertFalse(store.flushNow())
        store.update { it.copy(songSort = "a") }
        assertFalse(store.flushNow())
    }

    // A file from before certificates reads with none.
    @Test
    fun anOlderFileReadsWithNoCertificate() {
        dir.mkdirs()
        file().writeText("""{"servers":[{"address":"https://music.test/","username":"winters","id":"w1"}],"activeServer":"w1"}""")
        val read = started().current.servers.single()
        assertNull(read.certificate)
        assertEquals("w1", read.id)
    }

    @Test
    fun aCertificateFileSurvivesARestartAndAnUnknownFieldBesideItIsIgnored() {
        val withFile = kept.copy(certificate = CertFile("C:/certs/k.p12", "k.p12"))
        started().update { it.copy(servers = listOf(withFile)) }
        assertEquals(withFile, started().current.servers.single())
        val text = file().readText().replace("\"name\": \"k.p12\"", "\"name\": \"k.p12\", \"later\": 1")
        assertTrue("the field went in", text.contains("\"later\": 1"))
        file().writeText(text)
        assertEquals(CertFile("C:/certs/k.p12", "k.p12"), started().current.servers.single().certificate)
    }

    // Windows: a settings folder whose entries can't be looked at. The file
    // counts as there, so the start waits for it, saying this account may
    // not read it, instead of opening on the defaults over it, and opens on
    // it once the folder can be read again. Red when exists reports such a
    // file missing, or when the wait says another program has the file.
    @Test
    fun aFolderThatCannotBeLookedIntoIsWaitedForNeverTakenForMissing() {
        assumeTrue(currentOs() == DesktopOs.Windows)
        seeded()
        val (there, open) = try {
            icacls(dir, "/deny", "$user:(OI)(CI)(R)")
            RealSettingsDisk.exists(file()) to SettingsStore.open(file(), waitMs = 0)
        } finally {
            icacls(dir, "/reset", "/T")
        }
        assertTrue("there, though it can't be looked at", there)
        assertTrue("held: $open", open is SettingsOpen.Held)
        assertEquals(HeldAtStart.NoAccess, (open as SettingsOpen.Held).why)
        val freed = open.retry()
        assertTrue("opened: $freed", freed is SettingsOpen.Opened)
        assertEquals(listOf(kept), (freed as SettingsOpen.Opened).store.current.servers)
    }

    // Windows: a folder that refuses the move over the file. The replace
    // fails, the file keeps its old bytes, and no temporary file is left.
    // Red when a refused move falls back to a move that deletes the file
    // first.
    @Test
    fun aRefusedMoveLeavesTheOldFileAndNoTemporaryOne() {
        assumeTrue(currentOs() == DesktopOs.Windows)
        seeded()
        val before = file().readBytes()
        val thrown = try {
            icacls(dir, "/deny", "$user:(R)")
            runCatching { RealSettingsDisk.replace(file(), encodeSettings(AppSettings(), 9)) }.exceptionOrNull()
        } finally {
            icacls(dir, "/reset", "/T")
        }
        assertTrue("refused: $thrown", thrown is IOException)
        assertTrue("the old file is still there", file().exists())
        assertArrayEquals(before, file().readBytes())
        assertFalse(File(dir, SettingsStore.FILE_NAME + ".tmp").exists())
    }
}
