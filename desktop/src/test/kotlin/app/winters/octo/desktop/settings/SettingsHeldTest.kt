package app.winters.octo.desktop.settings

import app.winters.octo.desktop.system.SettingsHoldNotices
import app.winters.octo.desktop.ui.SETTINGS_HELD_KEPT
import app.winters.octo.desktop.ui.SETTINGS_HELD_UNKEPT
import app.winters.octo.desktop.ui.SettingsBanner
import app.winters.octo.desktop.ui.settingsBanners
import app.winters.octo.playback.BetweenSongs
import app.winters.octo.sound.SoundSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView

// A settings file another program holds. Held after the app opened (a
// write refused while the file stays readable, which a read cannot see):
// the whole state goes to the pending file at every failed write, the
// banner says so, and the next start uses it. The pending file is used only
// against the very file it began from. Held from the start: Octo never
// opens on the defaults, waiting through the constructor's clock; Open
// without my settings saves nothing. Every wait runs on a stepping clock,
// so none takes real time.
class SettingsHeldTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file() = File(folder.root, "octo/${SettingsStore.FILE_NAME}")

    private fun pending() = File(folder.root, "octo/settings.pending.json")

    private val home = SavedServer("https://music.test/", "winters", id = "h1", label = "Home")

    // A real file: a server, a song table's columns, an equalizer, searches,
    // pinned playlists, Home's order, lyrics timing and a Crossfade choice.
    private fun real(): AppSettings = AppSettings(
        server = home,
        servers = listOf(home),
        activeServer = "h1",
        tables = mapOf("songs" to TablePrefs(columns = listOf("title", "artist", "album"), widths = mapOf("title" to 300f))),
        sound = SoundPrefs(profiles = mapOf(SHARED_SOUND to SoundSettings(eqEnabled = true, graphicGains = List(10) { 0f }))),
        recentSearches = listOf("radiohead"),
        frame = FramePrefs(pinnedPlaylists = listOf("p1", "p2")),
        appearance = Appearance(homeSections = "charts,recentlyPlayed,-pinned"),
        playback = PlaybackPrefs(betweenSongs = BetweenSongs.Crossfade, crossfadeLength = 6),
        lyrics = LyricsPrefs(offsets = mapOf("s1" to 250L)),
    ).mirrored()

    // The real file on disk, written once (saveCount 1).
    private fun writeReal(): AppSettings {
        SettingsStore(file()).update { real() }
        return SettingsStore(file()).current
    }

    // The four changes made while the file is held.
    private fun change(store: SettingsStore) {
        store.update { it.copy(tables = it.tables + ("songs" to it.tables.getValue("songs").copy(widths = mapOf("title" to 420f)))) }
        store.update { s ->
            val shared = s.sound.profiles.getValue(SHARED_SOUND)
            s.copy(sound = s.sound.copy(profiles = s.sound.profiles + (SHARED_SOUND to shared.copy(graphicGains = shared.graphicGains.toMutableList().also { it[3] = 4f }))))
        }
        store.update { it.copy(recentSearches = listOf("massive attack") + it.recentSearches) }
        store.update { it.copy(frame = it.frame.copy(pinnedPlaylists = it.frame.pinnedPlaylists - "p1")) }
    }

    private fun countIn(f: File): Long = Json.parseToJsonElement(f.readText()).jsonObject.getValue("saveCount").jsonPrimitive.long

    private fun opened(disk: SettingsDisk, clock: SettingsClock = StepClock(), delay: Long = 0, writeRetryMs: Long = SettingsStore.WRITE_RETRY_MS): SettingsStore {
        val open = SettingsStore.openOn(disk, file(), writeDelayMs = delay, clock = clock, writeRetryMs = writeRetryMs)
        assertTrue("opened: $open", open is SettingsOpen.Opened)
        return (open as SettingsOpen.Opened).store
    }

    private fun aside(prefix: String) = file().parentFile.listFiles().orEmpty().filter { it.name.startsWith(prefix) }

    @Test
    fun aWriteRefusedWhileTheFileStaysReadableKeepsTheWholeStateBesideIt() {
        writeReal()
        val before = file().readBytes()
        assertEquals(1L, countIn(file()))
        val disk = HeldDisk().apply { refuseReplace += "settings.json" }
        val store = opened(disk)
        assertFalse(store.locked.value)
        change(store)
        assertArrayEquals("the file itself was never replaced", before, file().readBytes())
        assertTrue("held", store.locked.value)
        assertEquals(SettingsHold.Kept, store.hold.value)
        assertTrue(pending().exists())
        assertEquals("the pending file names the count it began from", 1L, countIn(pending()))
        assertEquals(listOf<SettingsBanner>(SettingsBanner.Held(SettingsHold.Kept)), settingsBanners(store.readOnly, store.hold.value, store.setAside.value))
        assertEquals(SETTINGS_HELD_KEPT, SettingsBanner.Held(SettingsHold.Kept).words)
        // The next start, with the file free: the real file and every change.
        val next = SettingsStore(file())
        assertEquals(store.current, next.current)
        assertEquals(420f, next.current.tables.getValue("songs").widths["title"])
        assertEquals(4f, next.current.sound.profiles.getValue(SHARED_SOUND).graphicGains[3])
        assertEquals(listOf("massive attack", "radiohead"), next.current.recentSearches)
        assertEquals(listOf("p2"), next.current.frame.pinnedPlaylists)
        assertEquals(listOf(home), next.current.servers)
        assertEquals("charts,recentlyPlayed,-pinned", next.current.appearance.homeSections)
        assertEquals(mapOf("s1" to 250L), next.current.lyrics.offsets)
        assertEquals(BetweenSongs.Crossfade, next.current.playback.betweenSongs)
        // Written as the file at once, and the pending file gone.
        assertEquals(2L, countIn(file()))
        assertFalse(pending().exists())
        assertEquals(store.current, SettingsStore(file()).current)
    }

    @Test
    fun tryAgainWritesTheFileOnceFreeAndTheBannerGoes() {
        writeReal()
        val disk = HeldDisk().apply { refuseReplace += "settings.json" }
        val store = opened(disk)
        store.update { it.copy(sidePanel = "lyrics") }
        store.tryAgain()
        assertTrue("still held", store.locked.value)
        disk.refuseReplace.clear()
        store.tryAgain()
        assertFalse(store.locked.value)
        assertEquals(SettingsHold.Free, store.hold.value)
        assertTrue(settingsBanners(store.readOnly, store.hold.value, store.setAside.value).isEmpty())
        assertFalse("the pending file is deleted once the file is written", pending().exists())
        assertEquals(2L, countIn(file()))
        assertEquals("lyrics", SettingsStore(file()).current.sidePanel)
    }

    // A store that never reaches its exit (a crash, a power cut) has every
    // change in the pending file already, written at the failed write. Its
    // retry waits a minute, so it never writes again inside the row; the
    // row ends with the hold lifted and what waits written, so neither that
    // retry nor the exit write finds anything left.
    @Test(timeout = 20_000)
    fun aStoreEndedUncleanlyMidHoldLosesNothing() {
        writeReal()
        val disk = HeldDisk().apply { refuseReplace += "settings.json" }
        val store = opened(disk, delay = 20, writeRetryMs = 60_000)
        try {
            change(store)
            // The writer's turn, waited on by its work, never a clock.
            fun keptAll() = pending().exists() && decodeSettings(pending().readText()).settings.frame.pinnedPlaylists == listOf("p2")
            val end = System.currentTimeMillis() + 10_000
            while (!keptAll() && System.currentTimeMillis() < end) Thread.sleep(10)
            assertTrue("every change is in the pending file", keptAll())
            // No flush, no exit: the store is just left.
            val next = SettingsStore(file())
            assertEquals(store.current, next.current)
            assertFalse(pending().exists())
        } finally {
            disk.refuseReplace.clear()
            store.flush()
        }
        assertEquals(SettingsHold.Free, store.hold.value)
    }

    // A pending file left by a failed delete, with later sessions writing
    // the main file since, or a main file replaced outside Octo: its count
    // is not the main file's, so it is set aside, never used.
    @Test
    fun aPendingFileOfAnotherCountIsSetAside() {
        writeReal()
        SettingsStore(file()).update { it.copy(sidePanel = "queue") }
        SettingsStore(file()).update { it.copy(sidePanel = "info") }
        assertEquals(3L, countIn(file()))
        // The very bytes, another count.
        pending().writeBytes(encodeSettings(real().copy(sidePanel = "lyrics", recentSearches = emptyList()), 2, settingsHash(file().readBytes())))
        val store = opened(HeldDisk())
        assertEquals("info", store.current.sidePanel)
        assertEquals(listOf("radiohead"), store.current.recentSearches)
        assertFalse(pending().exists())
        assertEquals(1, aside("settings.pending.older-").size)
        assertNull("not started fresh", store.setAside.value)
    }

    // No main file counts as 0, as does a main file without a count: a
    // pending file applies against either only from 0, and only against the
    // bytes it began from (none for no file).
    @Test
    fun noFileOrAFileWithoutACountIsZero() {
        file().parentFile.mkdirs()
        pending().writeBytes(encodeSettings(real(), 0, NO_MAIN_FILE))
        assertEquals("charts,recentlyPlayed,-pinned", opened(HeldDisk()).current.appearance.homeSections)
        assertEquals("written as the file", 1L, countIn(file()))
        assertFalse(pending().exists())

        file().delete()
        pending().writeBytes(encodeSettings(real(), 4, NO_MAIN_FILE))
        val fresh = opened(HeldDisk())
        assertEquals(AppSettings().settled(), fresh.current)
        assertEquals(1, aside("settings.pending.older-").size)

        // A file an older Octo wrote has no count.
        file().writeText("""{"sidePanel": "info"}""")
        pending().writeBytes(encodeSettings(real().copy(sidePanel = "lyrics"), 0, settingsHash(file().readBytes())))
        assertEquals("lyrics", opened(HeldDisk()).current.sidePanel)
    }

    // A pending file is used only against the very bytes it began from: an
    // older Octo writing the file since (no count, so 0 like the pending
    // file's base) leaves it older, so it is set aside as it was and the
    // file the older Octo wrote is used.
    @Test
    fun aPendingFileIsUsedOnlyAgainstTheBytesItBeganFrom() {
        file().parentFile.mkdirs()
        file().writeText("""{"sidePanel": "queue"}""")
        val olderFile = file().readBytes()
        val held = opened(HeldDisk().apply { refuseReplace += "settings.json" })
        held.update { it.copy(sidePanel = "lyrics") }
        val kept = pending().readBytes()
        assertEquals(0L, countIn(pending()))
        // Nothing wrote the file since: the pending file is the newer state.
        assertEquals("lyrics", opened(HeldDisk()).current.sidePanel)

        // An older Octo wrote the file in between.
        file().writeBytes(olderFile)
        pending().writeBytes(kept)
        file().writeText("""{"sidePanel": "info"}""")
        val store = opened(HeldDisk())
        assertEquals("info", store.current.sidePanel)
        assertFalse(pending().exists())
        assertArrayEquals("kept aside as it was", kept, aside("settings.pending.older-").single().readBytes())

        // Begun with no file at all, and an older Octo wrote one since.
        file().delete()
        val fresh = opened(HeldDisk().apply { refuseReplace += "settings.json" })
        fresh.update { it.copy(sidePanel = "lyrics") }
        file().writeText("""{"sidePanel": "info"}""")
        assertEquals("info", opened(HeldDisk(), StepClock(1_800_000_000_000)).current.sidePanel)
        assertEquals(2, aside("settings.pending.older-").size)
    }

    // A hold after this session wrote the file: the pending file names the
    // count and the bytes this session wrote, so the next start uses it.
    @Test
    fun aHoldAfterAWriteInTheSameSessionIsUsedNextTime() {
        writeReal()
        val disk = HeldDisk()
        val store = opened(disk)
        store.update { it.copy(sidePanel = "info") }
        disk.refuseReplace += "settings.json"
        store.update { it.copy(sidePanel = "lyrics") }
        assertEquals(SettingsHold.Kept, store.hold.value)
        assertEquals("lyrics", SettingsStore(file()).current.sidePanel)
        assertTrue(aside("settings.pending.older-").isEmpty())
    }

    // A pending file from before that cannot be moved aside is never
    // deleted, and never written over by a later hold (nothing is kept
    // beside the file then, and the banner says so); it is moved aside, as
    // it was, after the first write once it can be.
    @Test
    fun aPendingFileThatCannotBeMovedAsideIsNeverLost() {
        writeReal()
        SettingsStore(file()).update { it.copy(sidePanel = "info") }
        val older = encodeSettings(real().copy(sidePanel = "lyrics"), 1, NO_MAIN_FILE)
        pending().writeBytes(older)
        val disk = HeldDisk().apply { refuseMove += "settings.pending.json" }
        val store = opened(disk)
        assertEquals("info", store.current.sidePanel)
        store.update { it.copy(sidePanel = "queue") }
        assertEquals(SettingsHold.Free, store.hold.value)
        assertTrue("not deleted after a write", pending().exists())
        assertArrayEquals(older, pending().readBytes())
        store.flush()
        assertTrue("not deleted by the exit's flush", pending().exists())
        assertArrayEquals(older, pending().readBytes())
        disk.refuseReplace += "settings.json"
        store.update { it.copy(sidePanel = "info") }
        assertEquals("nothing kept beside the file", SettingsHold.Unkept, store.hold.value)
        assertArrayEquals("not written over", older, pending().readBytes())
        disk.refuseReplace.clear()
        disk.refuseMove.clear()
        store.tryAgain()
        assertEquals(SettingsHold.Free, store.hold.value)
        assertFalse(pending().exists())
        assertArrayEquals("moved aside as it was", older, aside("settings.pending.older-").single().readBytes())
        assertEquals("info", SettingsStore(file()).current.sidePanel)
    }

    // A main file that is not settings is set aside and the pending file
    // used: the app did not start fresh, so no banner says it did. With the
    // main file gone, counts start again.
    @Test
    fun anUnreadableMainFileWithAPendingFileOpensOnThePendingFile() {
        file().parentFile.mkdirs()
        file().writeText("{ half a fi")
        pending().writeBytes(encodeSettings(real(), 7, "0000"))
        val clock = StepClock()
        val store = opened(HeldDisk(), clock)
        assertEquals("charts,recentlyPlayed,-pinned", store.current.appearance.homeSections)
        assertEquals("{ half a fi", aside("settings.unreadable-").single().readText())
        assertNull(store.setAside.value)
        assertEquals(1L, countIn(file()))
        assertFalse(pending().exists())
    }

    // The main file set aside and the first write held: the pending file
    // names no main file, so the next start uses it.
    @Test
    fun aHeldWriteAfterTheMainFileWasSetAsideIsUsedNextTime() {
        file().parentFile.mkdirs()
        file().writeText("{ half a fi")
        pending().writeBytes(encodeSettings(real(), 7, "0000"))
        val store = opened(HeldDisk().apply { refuseReplace += "settings.json" })
        assertEquals(SettingsHold.Kept, store.hold.value)
        assertEquals("charts,recentlyPlayed,-pinned", opened(HeldDisk()).current.appearance.homeSections)
    }

    @Test
    fun anUnreadablePendingFileIsSetAside() {
        val read = writeReal()
        pending().writeText("not settings")
        val store = opened(HeldDisk())
        assertEquals(read, store.current)
        assertEquals("not settings", aside("settings.pending.unreadable-").single().readText())
        assertFalse(pending().exists())
    }

    // Held against reads from the start: the pending file is not used
    // without the main file it began from, so the wait is the same as with
    // none.
    @Test
    fun heldAgainstReadsWithAPendingFileWaitsAsWithNone() {
        writeReal()
        pending().writeBytes(encodeSettings(real().copy(sidePanel = "lyrics"), 1))
        val clock = StepClock()
        val disk = HeldDisk().apply { refuseReads += "settings.json" }
        val open = SettingsStore.openOn(disk, file(), clock = clock, waitMs = 3_000, retryMs = 100)
        assertTrue(open is SettingsOpen.Held)
        assertEquals(HeldAtStart.InUse, (open as SettingsOpen.Held).why)
        assertTrue(pending().exists())
    }

    // Each thing that holds Octo at the start is told apart, with the file
    // it is about: another program has it, or this account may not read it.
    @Test
    fun eachHoldAtTheStartSaysWhatHoldsOcto() {
        val read = writeReal()
        val disk = HeldDisk().apply { refuseReads += "settings.json" }
        val inUse = SettingsStore.openOn(disk, file(), clock = StepClock(), waitMs = 0) as SettingsOpen.Held
        assertEquals(HeldAtStart.InUse, inUse.why)
        assertEquals(file(), inUse.file)
        disk.refuseReads.clear()
        disk.denyReads += "settings.json"
        val denied = inUse.retry() as SettingsOpen.Held
        assertEquals(HeldAtStart.NoAccess, denied.why)
        assertEquals(file(), denied.file)
        disk.denyReads.clear()
        pending().writeBytes(encodeSettings(read, 1, settingsHash(file().readBytes())))
        disk.denyReads += "settings.pending.json"
        val pendingDenied = denied.retry() as SettingsOpen.Held
        assertEquals(HeldAtStart.NoAccess, pendingDenied.why)
        assertEquals("the file named is the one refused", pending(), pendingDenied.file)
        disk.denyReads.clear()
        assertTrue(pendingDenied.retry() is SettingsOpen.Opened)
    }

    // A damaged file Octo cannot move aside (a folder it may not change)
    // says so, rather than that another program has it; once it can be
    // moved, Octo starts fresh with it kept aside.
    @Test
    fun aDamagedFileThatCannotBeMovedAsideSaysSo() {
        file().parentFile.mkdirs()
        file().writeText("{ not settings")
        val disk = HeldDisk().apply { refuseMove += "settings.json" }
        val open = SettingsStore.openOn(disk, file(), clock = StepClock(), waitMs = 3_000, retryMs = 100)
        assertTrue(open is SettingsOpen.Held)
        assertEquals(HeldAtStart.Damaged, (open as SettingsOpen.Held).why)
        assertEquals(file(), open.file)
        assertEquals("never touched", "{ not settings", file().readText())
        disk.refuseMove.clear()
        val fresh = (open.retry() as SettingsOpen.Opened).store
        assertEquals(AppSettings().settled(), fresh.current)
        assertEquals("{ not settings", aside("settings.unreadable-").single().readText())
        assertTrue(fresh.setAside.value != null)
    }

    // Held from the start: no store on the defaults. The wait runs through
    // the constructor's clock (3 s of it, looked at every 100 ms, in no real
    // time); each later look is at once; the first that reads opens on the
    // real file, untouched by the looks before.
    @Test
    fun heldFromTheStartNeverOpensOnTheDefaults() {
        val read = writeReal()
        val before = file().readBytes()
        val clock = StepClock()
        val disk = HeldDisk().apply { refuseReads += "settings.json" }
        val began = System.nanoTime()
        val open = SettingsStore.openOn(disk, file(), clock = clock, waitMs = 3_000, retryMs = 100)
        assertTrue(open is SettingsOpen.Held)
        assertTrue("the whole wait on its clock: ${clock.at - 1_700_000_000_000} ms", clock.at - 1_700_000_000_000 >= 3_000)
        assertEquals("a look every 100 ms", 30, clock.sleeps)
        assertTrue("no real time", (System.nanoTime() - began) / 1_000_000 < 2_000)
        val still = (open as SettingsOpen.Held).retry()
        assertTrue(still is SettingsOpen.Held)
        assertEquals("a retry does not wait", 30, clock.sleeps)
        assertArrayEquals(before, file().readBytes())
        disk.refuseReads.clear()
        val freed = (still as SettingsOpen.Held).retry()
        assertTrue(freed is SettingsOpen.Opened)
        assertEquals("Home's order as the file has it", read, (freed as SettingsOpen.Opened).store.current)
    }

    // A file caught while another program writes it does not parse; it is
    // read again, inside the wait, and opens as it is.
    @Test
    fun aFileCaughtMidWriteIsReadAgainNotSetAside() {
        val read = writeReal()
        val disk = HeldDisk().apply { once["settings.json"] = ArrayDeque(listOf("""{"sidePanel": "qu""".toByteArray())) }
        val clock = StepClock()
        val store = opened(disk, clock)
        assertEquals(read, store.current)
        assertEquals(1, clock.sleeps)
        assertTrue(aside("settings.unreadable-").isEmpty())
        assertNull(store.setAside.value)
    }

    // The same unreadable bytes twice: the file is set aside and Octo starts
    // fresh, and says so until dismissed.
    @Test
    fun theSameUnreadableBytesTwiceAreSetAsideWithABanner() {
        file().parentFile.mkdirs()
        file().writeText("{ this is not json")
        val clock = StepClock()
        val store = opened(HeldDisk(), clock)
        assertEquals(AppSettings().settled(), store.current)
        val name = "settings.unreadable-${clock.at}.json"
        assertEquals(name, store.setAside.value)
        assertEquals("{ this is not json", File(file().parentFile, name).readText())
        val banner = settingsBanners(store.readOnly, store.hold.value, store.setAside.value).single()
        assertEquals("Octo couldn't read its settings file, so it started fresh. The old file is kept as $name", banner.words)
        store.dismissSetAside()
        assertNull(store.setAside.value)
    }

    // Open without my settings: memory only. Nothing is written, kept beside
    // the file or replayed, whatever changes.
    @Test
    fun openWithoutMySettingsSavesNothing() {
        writeReal()
        val before = file().readBytes()
        val files = file().parentFile.list().orEmpty().sorted()
        val store = SettingsStore.readOnly()
        assertTrue(store.readOnly)
        assertEquals(AppSettings().settled(), store.current)
        store.update { it.copy(recentSearches = listOf("massive attack"), frame = it.frame.copy(pinnedPlaylists = listOf("p9"))) }
        store.editBetweenSongs(BetweenSongsChange.Choose(BetweenSongs.Gapless))
        store.update { it.copy(sidePanel = "lyrics", appearance = it.appearance.copy(homeSections = "pinned")) }
        store.flush()
        store.tryAgain()
        assertEquals("lyrics", store.current.sidePanel)
        assertArrayEquals(before, file().readBytes())
        assertEquals("nothing beside it", files, file().parentFile.list().orEmpty().sorted())
        assertFalse(store.locked.value)
        assertEquals(listOf<SettingsBanner>(SettingsBanner.ReadOnly), settingsBanners(store.readOnly, store.hold.value, store.setAside.value))
        // The next start reads the real file, with nothing of that session.
        assertEquals("charts,recentlyPlayed,-pinned", SettingsStore(file()).current.appearance.homeSections)
    }

    // Both files refused: the banner says changes may not be kept.
    @Test
    fun bothFilesRefusedSayChangesMayNotBeKept() {
        writeReal()
        val disk = HeldDisk().apply { refuseReplace += setOf("settings.json", "settings.pending.json") }
        val store = opened(disk)
        store.update { it.copy(sidePanel = "lyrics") }
        assertEquals(SettingsHold.Unkept, store.hold.value)
        assertTrue(store.locked.value)
        assertEquals(SETTINGS_HELD_UNKEPT, settingsBanners(store.readOnly, store.hold.value, store.setAside.value).single().words)
        assertFalse(pending().exists())
    }

    // A pending file that will not go is deleted after a later write, or at
    // the exit's flush.
    @Test
    fun aPendingFileThatWillNotGoIsTriedAgain() {
        writeReal()
        val disk = HeldDisk().apply { refuseReplace += "settings.json" }
        val store = opened(disk)
        store.update { it.copy(sidePanel = "lyrics") }
        disk.refuseReplace.clear()
        disk.refuseDelete += "settings.pending.json"
        store.tryAgain()
        assertFalse(store.locked.value)
        assertTrue(pending().exists())
        disk.refuseDelete.clear()
        store.update { it.copy(sidePanel = "info") }
        assertFalse("deleted after the next write", pending().exists())
        disk.refuseDelete += "settings.pending.json"
        disk.refuseReplace += "settings.json"
        store.update { it.copy(sidePanel = "queue") }
        disk.refuseReplace.clear()
        store.tryAgain()
        assertTrue(pending().exists())
        disk.refuseDelete.clear()
        store.flush()
        assertFalse("deleted by the exit's flush", pending().exists())
    }

    // The system notice: once per hold, as it begins.
    @Test
    fun theSystemNoticeIsToldOncePerHold() {
        val notices = SettingsHoldNotices()
        assertNull(notices.noticeFor(SettingsHold.Free))
        assertEquals(SETTINGS_HELD_KEPT, notices.noticeFor(SettingsHold.Kept))
        assertNull(notices.noticeFor(SettingsHold.Kept))
        assertNull("the same hold, worse", notices.noticeFor(SettingsHold.Unkept))
        assertNull(notices.noticeFor(SettingsHold.Free))
        assertEquals(SETTINGS_HELD_UNKEPT, notices.noticeFor(SettingsHold.Unkept))
    }

    // Windows itself, as another program holds a file: a reader that does
    // not share delete leaves it readable and refuses its replace.
    @Test
    fun aRealReaderOnWindowsHoldsTheFileAndTryAgainWritesIt() {
        assumeTrue(currentOs() == DesktopOs.Windows)
        writeReal()
        val store = (SettingsStore.open(file(), clock = StepClock()) as SettingsOpen.Opened).store
        FileInputStream(file()).use { reader ->
            store.update { it.copy(sidePanel = "lyrics") }
            assertTrue("refused while open", store.locked.value)
            assertTrue(pending().exists())
            assertTrue("still readable", file().readBytes().isNotEmpty())
            reader.read()
        }
        store.tryAgain()
        assertFalse(store.locked.value)
        assertFalse(pending().exists())
        assertEquals("lyrics", SettingsStore(file()).current.sidePanel)
    }

    // Windows itself, as a lock refuses every read: held, then opened once
    // let go.
    @Test
    fun aRealLockOnWindowsWaitsThenOpens() {
        assumeTrue(currentOs() == DesktopOs.Windows)
        val read = writeReal()
        val held = RandomAccessFile(file(), "rw").use { raf ->
            raf.channel.lock().use { SettingsStore.open(file(), clock = StepClock()) }
        }
        assertTrue(held is SettingsOpen.Held)
        assertEquals(HeldAtStart.InUse, (held as SettingsOpen.Held).why)
        val freed = held.retry()
        assertEquals(read, (freed as SettingsOpen.Opened).store.current)
    }

    // Windows itself, as this account is denied reading the file: it says
    // so, rather than that another program has it.
    @Test
    fun aRealReadDenialOnWindowsSaysSo() {
        assumeTrue(currentOs() == DesktopOs.Windows)
        val read = writeReal()
        val held = denied(file(), AclEntryPermission.READ_DATA) { SettingsStore.open(file(), clock = StepClock(), waitMs = 0) }
        assertTrue("held: $held", held is SettingsOpen.Held)
        assertEquals(HeldAtStart.NoAccess, (held as SettingsOpen.Held).why)
        assertEquals(read, (held.retry() as SettingsOpen.Opened).store.current)
    }

    // Windows itself, as a damaged file sits in a folder Octo may not
    // change: it cannot be moved aside, and the window says so.
    @Test
    fun aRealDamagedFileInAFolderOctoCannotChangeSaysSo() {
        assumeTrue(currentOs() == DesktopOs.Windows)
        file().parentFile.mkdirs()
        file().writeText("{ not settings")
        val held = denied(file().parentFile, AclEntryPermission.ADD_FILE, AclEntryPermission.DELETE_CHILD) {
            denied(file(), AclEntryPermission.DELETE) { SettingsStore.open(file(), clock = StepClock(), waitMs = 3_000, retryMs = 100) }
        }
        assertTrue("held: $held", held is SettingsOpen.Held)
        assertEquals(HeldAtStart.Damaged, (held as SettingsOpen.Held).why)
        assertEquals("never touched", "{ not settings", file().readText())
        assertTrue(held.retry() is SettingsOpen.Opened)
        assertEquals("{ not settings", aside("settings.unreadable-").single().readText())
    }

    // Runs `block` while this account is denied `what` on `at`, then gives
    // it back.
    private fun <T> denied(at: File, vararg what: AclEntryPermission, block: () -> T): T {
        val view = Files.getFileAttributeView(at.toPath(), AclFileAttributeView::class.java)
        val before = view.acl
        view.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(view.owner).setPermissions(*what).build()) + before
        try {
            return block()
        } finally {
            view.acl = before
        }
    }
}
