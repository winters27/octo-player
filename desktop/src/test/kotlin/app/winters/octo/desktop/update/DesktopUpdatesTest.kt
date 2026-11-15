package app.winters.octo.desktop.update

import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.system.RunningAppImage
import app.winters.octo.update.InstallWhen
import app.winters.octo.update.UpdateCheck
import app.winters.octo.update.ManifestAsset
import app.winters.octo.update.PlayerApp
import app.winters.octo.update.PlayerUpdater
import app.winters.octo.update.PlayerVersion
import app.winters.octo.update.ReleaseFeed
import app.winters.octo.update.UpdatePrefs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

class DesktopUpdatesTest {
    @get:Rule val temp = TemporaryFolder()

    private val github = FakeReleases()
    private val files get() = github.files
    private var releases: String
        get() = github.releases
        set(value) { github.releases = value }
    private val msi get() = github.msi

    @After
    fun stop() = github.close()

    private fun publish(version: String) = github.publish(version)

    private val settings by lazy { SettingsStore(File(temp.root, "settings.json")) }
    private val started = mutableListOf<List<String>>()
    private var launches = true

    private fun fakeProcess(exit: Int) = object : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = exit
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = exit
        override fun destroy() {}
    }

    private fun updates(
        os: DesktopOs = DesktopOs.Windows,
        program: File? = null,
        appImage: RunningAppImage? = null,
        linuxKind: String = if (appImage != null) APPIMAGE_KIND else "deb",
    ): DesktopUpdates {
        val client = OkHttpClient()
        val folder = File(temp.root, "updates")
        val updater = PlayerUpdater(
            PlayerApp.Desktop,
            PlayerVersion.parse("1.1.0")!!,
            ReleaseFeed(client, File(folder, "releases.json"), "Octo test", apiBase = github.api),
            client,
            folder,
            listOf(github.publicKey),
            pick = { installerFor(it, os, "x64", linuxKind) },
        )
        return DesktopUpdates(settings, UpdaterAvailability.On(PlayerVersion.parse("1.1.0")!!), os, folder, updater, start = { command ->
            if (!launches) throw java.io.IOException("no such program")
            started += command
            fakeProcess(0)
        }, program = program, appImage = appImage, pid = 4242)
    }

    private val onWindows = System.getProperty("os.name").lowercase().startsWith("windows")

    // An AppImage of 1.1.0 in a folder of the listener's own.
    private fun oldAppImage(unpacked: Boolean = false): RunningAppImage {
        val file = File(temp.newFolder("My Apps"), "Octo.AppImage")
        file.writeBytes(ByteArray(1_000) { 1 })
        file.setExecutable(true)
        return RunningAppImage(file, unpacked)
    }

    @Test
    fun aCheckFindsDownloadsAndHoldsTheUpdate() = runBlocking {
        publish("1.2.0")
        val updates = updates()
        updates.check()
        val ready = updates.ready!!
        assertEquals("1.2.0", ready.version.toString())
        assertEquals("- Octo updates itself.", ready.notes)
        assertTrue(ready.file.readBytes().contentEquals(msi))
        assertNull(updates.lastLine)
    }

    @Test
    fun restartToUpdateStartsTheInstallerThenQuitsTheUsualWay() = runBlocking {
        publish("1.2.0")
        val updates = updates()
        updates.check()
        var quit = 0
        assertTrue(updates.install(quit = { quit++ }))
        assertEquals(1, quit)
        assertEquals("powershell.exe", started.single().first())
        // Asked again (by quitting), nothing starts twice.
        updates.onQuit()
        assertEquals(1, started.size)
    }

    @Test
    fun anInstallerThatCannotStartLeavesOctoOpen() = runBlocking {
        publish("1.2.0")
        launches = false
        val updates = updates()
        updates.check()
        var quit = 0
        assertFalse(updates.install(quit = { quit++ }))
        assertEquals(0, quit)
        assertNotNull(updates.ready)
        assertTrue(updates.lastLine!!.contains("couldn't start"))
    }

    @Test
    fun quittingInstallsOnlyWhenAskedTo() = runBlocking {
        publish("1.2.0")
        val updates = updates(program = File("C:\\Users\\b\\AppData\\Local\\Octo\\Octo.exe"))
        updates.check()
        updates.onQuit()
        assertTrue("Ask me waits for the button", started.isEmpty())
        settings.update { it.copy(updates = it.updates.copy(install = InstallWhen.OnQuit)) }
        updates.onQuit()
        assertEquals(1, started.size)
        // It does not open Octo again: the listener quit. Start with
        // Windows still follows a moved Octo.
        val script = installScriptOf(started.single())
        assertFalse(script.contains("Start-Process -FilePath \$octo"))
        assertTrue(script.contains("\$was = '\"C:\\Users\\b\\AppData\\Local\\Octo\\Octo.exe\"'"))
    }

    @Test
    fun onAMacTheDiskImageOpensAndOctoStays() = runBlocking {
        publish("1.2.0")
        val updates = updates(DesktopOs.Mac)
        updates.check()
        // No DMG in this release: nothing for this Mac, and nothing to open.
        assertNull(updates.ready)
        assertTrue(updates.lastLine!!.contains("not yet for this computer"))
    }

    // An AppImage takes the release's AppImage, puts it where it was opened
    // from, starts the waiter that opens it once this Octo is gone, then
    // quits the usual way.
    @Test
    fun anAppImageReplacesItselfAndOpensAgain() = runBlocking {
        publish("1.2.0")
        val image = oldAppImage()
        val updates = updates(DesktopOs.Linux, appImage = image)
        updates.check()
        assertEquals("Octo-1.2.0-linux-x64.AppImage", updates.ready!!.asset.name)
        assertEquals("Restart to update", updates.installAction)
        assertTrue(updates.selfInstalls)
        assertTrue(updates.installHelp(updates.ready!!).contains("new AppImage in place of this one"))
        var quit = 0
        assertTrue(updates.install(quit = { quit++ }))
        assertEquals(1, quit)
        assertTrue("the file it was opened from is the new one", image.file.readBytes().contentEquals(github.appImage))
        assertTrue(onWindows || image.file.canExecute())
        assertEquals("nothing left beside it", listOf("Octo.AppImage"), image.file.parentFile.list()!!.toList())
        val waiter = started.single()
        assertEquals(appImageRelaunch(4242, image.file, unpacked = false), waiter)
        assertEquals(listOf("sh", "-c"), waiter.take(2))
        assertEquals(listOf("octo-relaunch", "4242", image.file.absolutePath, "0"), waiter.drop(3))
        // Asked again (by quitting), nothing is done twice.
        settings.update { it.copy(updates = it.updates.copy(install = InstallWhen.OnQuit)) }
        updates.onQuit()
        assertEquals(1, started.size)
    }

    @Test
    fun anUnpackedAppImageOpensUnpackedAgain() = runBlocking {
        publish("1.2.0")
        val image = oldAppImage(unpacked = true)
        val updates = updates(DesktopOs.Linux, appImage = image)
        updates.check()
        assertTrue(updates.install(quit = {}))
        assertEquals("1", started.single().last())
    }

    // With When I quit, the new AppImage goes in as Octo quits, and Octo
    // stays closed: nothing opens it again.
    @Test
    fun anAppImageUpdatesAsOctoQuitsWhenAskedTo() = runBlocking {
        publish("1.2.0")
        val image = oldAppImage()
        val updates = updates(DesktopOs.Linux, appImage = image)
        updates.check()
        updates.onQuit()
        assertFalse("Ask me waits for the button", image.file.readBytes().contentEquals(github.appImage))
        settings.update { it.copy(updates = it.updates.copy(install = InstallWhen.OnQuit)) }
        updates.onQuit()
        assertTrue(image.file.readBytes().contentEquals(github.appImage))
        assertTrue(started.isEmpty())
    }

    // A download changed after its check never goes in: the AppImage stays
    // whole and as it was, Octo stays open, and the row says why.
    @Test
    fun aChangedDownloadNeverReplacesTheAppImage() = runBlocking {
        publish("1.2.0")
        val image = oldAppImage()
        val before = image.file.readBytes()
        val updates = updates(DesktopOs.Linux, appImage = image)
        updates.check()
        updates.ready!!.file.writeBytes(github.appImage.copyOf().also { it[7] = 99 })
        var quit = 0
        assertFalse(updates.install(quit = { quit++ }))
        assertEquals(0, quit)
        assertTrue(image.file.readBytes().contentEquals(before))
        assertEquals(listOf("Octo.AppImage"), image.file.parentFile.list()!!.toList())
        assertTrue(updates.lastLine!!.contains("didn't match its release"))
        assertTrue(started.isEmpty())
    }

    // When the waiter can't start, the new version is already in place and
    // Octo stays open, saying it starts next time.
    @Test
    fun anAppImageThatCannotOpenAgainStaysOpenWithTheNewOneInPlace() = runBlocking {
        publish("1.2.0")
        val image = oldAppImage()
        val updates = updates(DesktopOs.Linux, appImage = image)
        updates.check()
        launches = false
        var quit = 0
        assertFalse(updates.install(quit = { quit++ }))
        assertEquals(0, quit)
        assertTrue(image.file.readBytes().contentEquals(github.appImage))
        assertNull(updates.ready)
        assertTrue(updates.lastLine!!.contains("starts the next time you open Octo"))
    }

    @Test
    fun replacingAnAppImageKeepsItWholeAndRunnable() {
        val folder = temp.newFolder("apps")
        val target = File(folder, "Octo.AppImage").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val download = File(temp.newFolder("cache"), "new.AppImage").apply { writeBytes(ByteArray(5_000) { 4 }) }
        assertEquals("a wrong checksum", "The new AppImage didn't match its release, so this one was kept.", replaceAppImage(download, target, "0".repeat(64)))
        assertTrue(target.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(listOf("Octo.AppImage"), folder.list()!!.toList())
        assertNull(replaceAppImage(download, target, github.sha256(download.readBytes())))
        assertTrue(target.readBytes().contentEquals(download.readBytes()))
        assertTrue(onWindows || target.canExecute())
        assertEquals(listOf("Octo.AppImage"), folder.list()!!.toList())
        // A folder that isn't there: nothing is made, and it says so.
        val gone = File(temp.root, "gone/Octo.AppImage")
        assertTrue(replaceAppImage(download, gone, github.sha256(download.readBytes()))!!.contains("couldn't put the new AppImage"))
        assertFalse(gone.parentFile.exists())
    }

    // The waiter itself, on a system with sh: it waits out the process,
    // then opens the AppImage with the extract-and-run flag when asked.
    @Test
    fun theWaiterOpensTheAppImageOnceOctoIsGone() {
        assumeTrue(!onWindows)
        val marker = File(temp.root, "opened.txt")
        val fake = File(temp.root, "Octo.AppImage").apply {
            writeText("#!/bin/sh\necho \"${'$'}{APPIMAGE_EXTRACT_AND_RUN:-mounted}\" > '${marker.absolutePath}'\n")
            setExecutable(true)
        }
        val octo = ProcessBuilder("sleep", "1").start()
        val waiter = ProcessBuilder(appImageRelaunch(octo.pid(), fake, unpacked = true)).start()
        assertTrue(waiter.waitFor(10, TimeUnit.SECONDS))
        assertFalse("it waited for Octo", octo.isAlive)
        assertEquals("1", marker.readText().trim())
    }

    @Test
    fun aTamperedDownloadIsDeletedAndSaidQuietly() = runBlocking {
        publish("1.2.0")
        files[files.keys.first { it.endsWith(".msi") }] = msi.copyOf().also { it[5] = 99 }
        val updates = updates()
        updates.check()
        assertNull(updates.ready)
        assertTrue(updates.lastLine!!.contains("safety check"))
        assertFalse(File(temp.root, "updates/desktop-v1.2.0").exists())
    }

    @Test
    fun upToDateForgetsOldDownloads() = runBlocking {
        publish("1.2.0")
        val updates = updates()
        updates.check()
        releases = "[]"
        updates.check()
        assertNull(updates.ready)
        assertEquals("Octo is up to date.", updates.lastLine)
        assertFalse(File(temp.root, "updates/desktop-v1.2.0").exists())
    }

    // The launcher: the command line has no quotes or spaces in any one
    // argument, and the script inside waits for Octo, runs the MSI quietly
    // with its path quoted, finds where Octo is now, and opens it again.
    @Test
    fun theWindowsLauncherWaitsInstallsFindsAndReopens() {
        // Its paths are Windows paths, which only split into folders there.
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val msi = File("C:\\Users\\O'Brien\\AppData\\Local\\Temp\\Octo\\updates\\desktop-v1.2.0\\Octo-1.2.0-windows-x64.msi")
        val octo = File("C:\\Users\\O'Brien\\AppData\\Local\\Octo\\Octo.exe")
        val moved = File("C:\\Users\\O'Brien\\AppData\\Local\\OctoPlayer\\Octo.exe")
        val command = WindowsInstall.command(msi, listOf(111, 222), File(msi.parentFile, "install.log"), octo, relaunch = true, fallback = moved)
        assertEquals(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-EncodedCommand"), command.dropLast(1))
        assertTrue(command.none { ' ' in it || '"' in it })
        val outer = decode(command.last())
        // Started through WMI, outside Octo's job, hidden.
        assertTrue(outer.contains("Invoke-CimMethod -ClassName Win32_Process -MethodName Create"))
        assertTrue(outer.contains("ShowWindow = [uint16]0"))
        assertTrue(outer.contains("exit [int]\$made.ReturnValue"))
        val script = installScriptOf(command)
        assertTrue(script.contains("foreach (\$id in @(111, 222)) { Wait-Process -Id \$id -Timeout 300 }"))
        // The apostrophe is doubled inside PowerShell's single quotes, and
        // msiexec gets the path in double quotes.
        assertTrue(script, script.contains("Start-Process -FilePath 'msiexec.exe' -ArgumentList '/i \"C:\\Users\\O''Brien\\AppData\\Local\\Temp\\Octo\\updates\\desktop-v1.2.0\\Octo-1.2.0-windows-x64.msi\" /passive /norestart /l*v \"C:\\Users\\O''Brien\\AppData\\Local\\Temp\\Octo\\updates\\desktop-v1.2.0\\install.log\"' -Wait"))
        // Windows Installer is asked where Octo's upgrade code put it, then
        // the old program is tried (a failed MSI leaves it), then the new default.
        assertTrue(script, script.contains("'RelatedProducts' @('{4F7B3C1E-8A52-4D6B-9E0F-2C8D1A7B5E93}')"))
        assertTrue(script.contains("'ProductInfo' @(\$code, 'InstallLocation')"))
        assertTrue(script, script.contains("foreach (\$exe in @('C:\\Users\\O''Brien\\AppData\\Local\\Octo\\Octo.exe', 'C:\\Users\\O''Brien\\AppData\\Local\\OctoPlayer\\Octo.exe'))"))
        // A moved Octo: Start with Windows and octo:// links follow it.
        assertTrue(script.contains("\$was = '\"C:\\Users\\O''Brien\\AppData\\Local\\Octo\\Octo.exe\"'"))
        assertTrue(script.contains("@('HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run', 'Octo')"))
        assertTrue(script.contains("@('HKCU:\\Software\\Classes\\octo\\shell\\open\\command', '(default)')"))
        assertTrue(script.contains("@('HKCU:\\Software\\Classes\\octo\\DefaultIcon', '(default)')"))
        assertTrue(script.contains("if (\$octo) { Start-Process -FilePath \$octo }"))
        // The MSI runs only after the wait, and Octo is looked for after it.
        assertTrue(script.indexOf("Wait-Process") < script.indexOf("msiexec"))
        assertTrue(script.indexOf("msiexec") < script.indexOf("RelatedProducts"))
        assertTrue(script.indexOf("\$was") < script.indexOf("Start-Process -FilePath \$octo"))
    }

    @Test
    fun withoutAProgramToReopenTheScriptOnlyInstalls() {
        val script = WindowsInstall.installScript(File("C:\\u\\Octo.msi"), emptyList(), File("C:\\u\\install.log"), null, relaunch = true, fallback = File("C:\\u\\OctoPlayer\\Octo.exe"))
        assertFalse(script.contains("Wait-Process"))
        assertFalse(script.contains("RelatedProducts"))
        assertEquals(1, Regex("Start-Process").findAll(script).count())
    }

    // The part of the script after the MSI, run for real: Octo is found
    // where it is, and only values naming the old program by its quoted
    // path are changed. A made-up upgrade code finds no product here, so
    // the files decide; the values sit under a key of the test's own.
    @Test
    fun theScriptFindsTheMovedOctoAndRepointsOnlyItsOwnValues() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val old = File(temp.newFolder("Octo"), "Octo.exe")
        val moved = File(temp.newFolder("OctoPlayer"), "Octo.exe")
        val key = "HKCU:\\Software\\octo-test-" + System.nanoTime()
        val values = listOf(key to "Run", "$key\\command" to "(default)", key to "Other")
        fun run(): String {
            val script = buildString {
                appendLine("New-Item -Path '$key\\command' -Force | Out-Null")
                appendLine("Set-ItemProperty -LiteralPath '$key' -Name 'Run' -Value ${WindowsInstall.quote("\"" + old.path.uppercase() + "\" --tray")}")
                appendLine("Set-ItemProperty -LiteralPath '$key\\command' -Name '(default)' -Value ${WindowsInstall.quote("\"" + old.path + "\" \"%1\"")}")
                appendLine("Set-ItemProperty -LiteralPath '$key' -Name 'Other' -Value '\"C:\\Elsewhere\\Octo.exe\"'")
                append(WindowsInstall.findProgram(old, moved, upgradeCode = "00000000-0000-0000-0000-00000000c0de"))
                append(WindowsInstall.pointAtProgram(old, values))
                appendLine("'octo=' + \$octo")
                appendLine("'run=' + (Get-ItemProperty -LiteralPath '$key').Run")
                appendLine("'link=' + (Get-ItemProperty -LiteralPath '$key\\command').'(default)'")
                appendLine("'other=' + (Get-ItemProperty -LiteralPath '$key').Other")
                appendLine("Remove-Item -LiteralPath '$key' -Recurse -Force")
            }
            val process = ProcessBuilder(WindowsInstall.powershell(script).filter { it != "-WindowStyle" && it != "Hidden" }).redirectErrorStream(true).start()
            val said = process.inputStream.bufferedReader().readText()
            assertTrue(said, process.waitFor(60, TimeUnit.SECONDS))
            return said
        }
        fun File.touch() = apply { writeText("") }
        fun File.gone() = apply { delete() }

        // The update went in at the new place: everything follows it.
        old.gone(); moved.touch()
        var said = run()
        assertTrue(said, said.contains("octo=${moved.path}"))
        assertTrue(said, said.contains("run=\"${moved.path}\" --tray"))
        assertTrue(said, said.contains("link=\"${moved.path}\" \"%1\""))
        assertTrue(said, said.contains("other=\"C:\\Elsewhere\\Octo.exe\""))

        // The update failed and the old Octo is still there: nothing changes.
        old.touch()
        said = run()
        assertTrue(said, said.contains("octo=${old.path}"))
        assertTrue(said, said.contains("run=\"${old.path.uppercase()}\" --tray"))

        // No Octo anywhere: nothing opens and nothing changes.
        old.gone(); moved.gone()
        said = run()
        assertTrue(said, said.lines().any { it.trim() == "octo=" })
        assertTrue(said, said.contains("link=\"${old.path}\" \"%1\""))
    }

    // Downloads wait outside the folder a new version replaces.
    @Test
    fun updatesWaitOutsideTheProgramFolder() {
        val home = File("/Users/b")
        val macCache = File("/Users/b/Library/Caches/Octo")
        val macTemp = File("/var/folders/x/T")
        val mac = File("/Applications/Octo.app/Contents/MacOS/Octo")
        // The user's own temp folder; a run with a folder of its own keeps its cache.
        assertEquals(File("/var/folders/x/T/Octo/updates"), updatesFolder(macCache, mac, DesktopOs.Mac, macTemp, separate = false, home = home))
        assertEquals(File("/Users/b/Library/Caches/Octo/updates"), updatesFolder(macCache, mac, DesktopOs.Mac, macTemp, separate = true, home = home))
        assertEquals(File("/var/folders/x/T/Octo/updates"), updatesFolder(macCache, null, DesktopOs.Mac, macTemp, separate = false, home = home))
        // A temp folder inside the program's folder is passed over, and so
        // is a cache there; the home folder is the last place.
        assertEquals(File("/Users/b/Library/Caches/Octo/updates"), updatesFolder(macCache, mac, DesktopOs.Mac, File("/Applications/Octo.app/tmp"), separate = false, home = home))
        assertEquals(File("/Users/b/.octo-updates"), updatesFolder(File("/Applications/Octo.app/cache"), mac, DesktopOs.Mac, File("/Applications/Octo.app/tmp"), separate = false, home = home))
        // Linux shares /tmp between users: the cache, never /tmp.
        val linux = File("/opt/octo/bin/Octo")
        assertEquals(File("/home/b/.cache/octo/updates"), updatesFolder(File("/home/b/.cache/octo"), linux, DesktopOs.Linux, File("/tmp"), separate = false, home = File("/home/b")))
        assertEquals(File("/home/b/.octo-updates"), updatesFolder(File("/opt/octo/cache"), linux, DesktopOs.Linux, File("/tmp"), separate = false, home = File("/home/b")))
        // What a new version replaces on each system.
        assertEquals(File("/opt/octo").absoluteFile, programFolder(File("/opt/octo/bin/Octo"), DesktopOs.Linux))
        assertEquals(File("/Applications/Octo.app").absoluteFile, programFolder(File("/Applications/Octo.app/Contents/MacOS/Octo"), DesktopOs.Mac))
        assertTrue(isInside(File("/Applications/Octo.app/Contents/app/x"), File("/Applications/Octo.app"), DesktopOs.Mac))
        assertFalse(isInside(File("/Applications/Octo.apps"), File("/Applications/Octo.app"), DesktopOs.Mac))
    }

    // The bug this guards against: installed in %LOCALAPPDATA%\Octo, with the
    // cache in %LOCALAPPDATA%\Octo\Cache, a new version emptied the cache
    // and the running installer with it.
    @Test
    fun onWindowsUpdatesNeverWaitInTheInstallFolder() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val temp = File("C:\\Users\\b\\AppData\\Local\\Temp")
        val cache = File("C:\\Users\\b\\AppData\\Local\\Octo\\Cache")
        val home = File("C:\\Users\\b")
        val before = File("C:\\Users\\b\\AppData\\Local\\Octo\\Octo.exe")
        val now = File("C:\\Users\\b\\AppData\\Local\\OctoPlayer\\Octo.exe")
        assertTrue(isInside(File(cache, "updates"), programFolder(before, DesktopOs.Windows), DesktopOs.Windows))
        assertEquals(File("C:\\Users\\b\\AppData\\Local\\Temp\\Octo\\updates"), updatesFolder(cache, before, DesktopOs.Windows, temp, separate = false, home = home))
        assertEquals(File("C:\\Users\\b\\AppData\\Local\\Temp\\Octo\\updates"), updatesFolder(cache, now, DesktopOs.Windows, temp, separate = false, home = home))
        // A run with a folder of its own, from the old place: its cache is
        // inside, so the temp folder.
        assertEquals(File("C:\\Users\\b\\AppData\\Local\\Temp\\Octo\\updates"), updatesFolder(cache, before, DesktopOs.Windows, temp, separate = true, home = home))
        // Windows ignores case.
        assertTrue(isInside(File("c:\\users\\B\\appdata\\local\\octo\\cache"), File("C:\\Users\\b\\AppData\\Local\\Octo"), DesktopOs.Windows))
        assertFalse(isInside(File("C:\\Users\\b\\AppData\\Local\\OctoCache"), File("C:\\Users\\b\\AppData\\Local\\Octo"), DesktopOs.Windows))
        // The new default place, from %LOCALAPPDATA%.
        assertEquals(now, defaultWindowsProgram(mapOf("LOCALAPPDATA" to "C:\\Users\\b\\AppData\\Local")::get, "C:\\Users\\b"))
        assertEquals(now, defaultWindowsProgram({ null }, "C:\\Users\\b"))
    }

    // The app and the installer must agree on the folder and the upgrade code.
    @Test
    fun theBuildInstallsWhereTheAppLooks() {
        val build = File("build.gradle.kts").readText()
        assertTrue(build.contains("installationPath = \"$WINDOWS_INSTALL_FOLDER\""))
        assertTrue(build.contains("upgradeUuid = \"$WINDOWS_UPGRADE_CODE\""))
    }

    @Test
    fun aDownloadClearedAwayIsFetchedAgainNotRun() = runBlocking {
        publish("1.2.0")
        val updates = updates()
        updates.check()
        updates.ready!!.file.delete()
        assertFalse(updates.install(quit = {}))
        assertTrue(started.isEmpty())
        assertNull(updates.ready)
        updates.check()
        assertTrue(updates.ready!!.file.isFile)
    }

    @Test
    fun thisProcessIsWaitedFor() {
        assertEquals(ProcessHandle.current().pid(), octoProcesses().first())
    }

    private fun decode(encoded: String) = String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE)

    // The script the WMI-started process runs, from the outer command.
    private fun installScriptOf(command: List<String>): String {
        val outer = decode(command.last())
        val line = Regex("\\\$line = '([^']*)'").find(outer)!!.groupValues[1]
        return decode(line.substringAfterLast(' '))
    }

    @Test
    fun onlyAnInstalledOctoWithAVersionAndAKeyUpdates() {
        assertTrue(updaterAvailability("1.2.0", installed = true, portable = false, forced = false, hasKeys = true) is UpdaterAvailability.On)
        // From source, the portable zip, a build with no key, or not installed.
        assertTrue(updaterAvailability(null, installed = true, portable = false, forced = false, hasKeys = true) is UpdaterAvailability.Off)
        assertTrue(updaterAvailability("1.2.0", installed = true, portable = true, forced = false, hasKeys = true) is UpdaterAvailability.Off)
        assertTrue(updaterAvailability("1.2.0", installed = true, portable = false, forced = false, hasKeys = false) is UpdaterAvailability.Off)
        assertTrue(updaterAvailability("1.2.0", installed = false, portable = false, forced = false, hasKeys = true) is UpdaterAvailability.Off)
        // A build can be made to try it, but never without a version or key.
        assertTrue(updaterAvailability("1.2.0", installed = false, portable = true, forced = true, hasKeys = true) is UpdaterAvailability.On)
        assertTrue(updaterAvailability(null, installed = false, portable = false, forced = true, hasKeys = true) is UpdaterAvailability.Off)
    }

    // Inside a Flatpak, Octo's program looks installed (jpackage names it),
    // but it can't run an installer: Flatpak updates it, and it says so,
    // even when forced.
    @Test
    fun theFlatpakCopySaysFlatpakUpdatesIt() {
        assertEquals(UpdaterAvailability.Off(FLATPAK_UPDATES), updaterAvailability("1.12.0", installed = true, portable = false, forced = false, hasKeys = true, flatpak = true))
        assertEquals(UpdaterAvailability.Off(FLATPAK_UPDATES), updaterAvailability("1.12.0", installed = true, portable = false, forced = true, hasKeys = true, flatpak = true))
        assertTrue(FLATPAK_UPDATES.none { it == '—' || it == '–' })
    }

    @Test
    fun theDevelopmentBuildNeverUpdates() {
        // This test runs from Gradle: Java, not Octo's program, no stamped version.
        assertTrue(updaterAvailability() is UpdaterAvailability.Off)
        val off = DesktopUpdates.forThisApp(settings, temp.root, DesktopOs.Windows)
        assertFalse(off.enabled)
    }

    @Test
    fun eachSystemGetsItsOwnInstaller() {
        fun asset(os: String, arch: String, kind: String) = ManifestAsset("Octo-$os-$arch.$kind", "a".repeat(64), 1, os, arch, kind)
        val assets = listOf(
            asset("windows", "x64", "zip"), asset("windows", "x64", "msi"), asset("macos", "arm64", "dmg"),
            asset("linux", "x64", "deb"), asset("linux", "x64", "rpm"), asset("linux", "x64", "zip"),
            asset("linux", "x64", "flatpak"), asset("linux", "x64", APPIMAGE_KIND), asset("linux", "x64", PACMAN_KIND),
        )
        assertEquals("msi", installerFor(assets, DesktopOs.Windows, "x64", null)?.kind)
        assertEquals("dmg", installerFor(assets, DesktopOs.Mac, "arm64", null)?.kind)
        assertNull("no DMG for an Intel Mac", installerFor(assets, DesktopOs.Mac, "x64", null))
        assertEquals("rpm", installerFor(assets, DesktopOs.Linux, "x64", "rpm")?.kind)
        assertEquals("deb", installerFor(assets, DesktopOs.Linux, "x64", "deb")?.kind)
        assertEquals("appimage", installerFor(assets, DesktopOs.Linux, "x64", APPIMAGE_KIND)?.kind)
        assertEquals("pkg.tar.zst", installerFor(assets, DesktopOs.Linux, "x64", PACMAN_KIND)?.kind)
        assertNull(installerFor(assets, DesktopOs.Linux, "x64", null))
        assertNull(installerFor(assets, DesktopOs.Windows, "arm64", null))
    }

    // Each Mac takes the DMG made for its chip, and an Intel copy running on
    // Apple silicon (through Rosetta) moves to the Apple silicon one.
    @Test
    fun eachMacTakesTheDiskImageForItsChip() {
        fun dmg(arch: String) = ManifestAsset("Octo-1.13.0-macos-$arch.dmg", "a".repeat(64), 1, "macos", arch, "dmg")
        val assets = listOf(dmg("arm64"), dmg("x64"), ManifestAsset("Octo-1.13.0-windows-x64.msi", "a".repeat(64), 1, "windows", "x64", "msi"))
        fun picked(jvmArch: String, appleSilicon: Boolean?) = installerFor(assets, DesktopOs.Mac, macArch(jvmArch) { appleSilicon }, null)?.name
        assertEquals("Octo-1.13.0-macos-arm64.dmg", picked("aarch64", true))
        assertEquals("Octo-1.13.0-macos-x64.dmg", picked("x86_64", false))
        assertEquals("Octo-1.13.0-macos-arm64.dmg", picked("x86_64", true))
        assertEquals("the system can't say: Java's word stands", "Octo-1.13.0-macos-x64.dmg", picked("x86_64", null))
        assertEquals("arm64", macArch("arm64") { null })
    }

    // An AppImage opened from its file updates itself; one unpacked by
    // hand, or in a folder Octo can't write to, says why it doesn't. The
    // Flatpak and a build with no key still win.
    @Test
    fun anAppImageUpdatesItselfWhereItCan() {
        val image = RunningAppImage(File(temp.root, "Octo.AppImage").apply { writeText("x") }, unpacked = false)
        fun on(appImage: RunningAppImage? = image, unpacked: Boolean = false, writable: Boolean = true, flatpak: Boolean = false, keys: Boolean = true) =
            updaterAvailability("1.13.0", installed = true, portable = false, forced = false, hasKeys = keys, flatpak = flatpak,
                appImage = appImage, unpackedAppImage = unpacked, replaceable = { writable })
        assertTrue(on() is UpdaterAvailability.On)
        assertEquals(UpdaterAvailability.Off(UNPACKED_APPIMAGE_UPDATES), on(appImage = null, unpacked = true))
        val locked = on(writable = false) as UpdaterAvailability.Off
        assertEquals(lockedAppImageUpdates(image.file), locked.why)
        assertTrue(locked.why.contains(temp.root.absolutePath) && locked.why.contains("~/Applications"))
        assertEquals(UpdaterAvailability.Off(FLATPAK_UPDATES), on(flatpak = true))
        assertTrue(on(keys = false) is UpdaterAvailability.Off)
        assertTrue(canReplaceAppImage(image.file))
        assertFalse(canReplaceAppImage(File(temp.root, "missing.AppImage")))
        for (words in listOf(UNPACKED_APPIMAGE_UPDATES, lockedAppImageUpdates(image.file))) {
            assertTrue(words.none { it == Char(0x2014) || it == Char(0x2013) })
        }
    }

    // A package install keeps its own words: pacman's command on Arch, and
    // the folder to show, with no self-install choice.
    @Test
    fun aPacmanInstallSaysHowToInstallIt() {
        val name = "Octo-1.2.0-linux-x64.pkg.tar.zst"
        val asset = ManifestAsset(name, "a".repeat(64), 1, "linux", "x64", PACMAN_KIND)
        val ready = UpdateCheck.Ready(PlayerVersion.parse("1.2.0")!!, "desktop-v1.2.0", "", File(temp.root, name), asset)
        val updates = updates(DesktopOs.Linux, linuxKind = PACMAN_KIND)
        assertEquals("Install it with: sudo pacman -U ./Octo-1.2.0-linux-x64.pkg.tar.zst", updates.installHelp(ready))
        assertEquals("Show package", updates.installAction)
        assertFalse(updates.selfInstalls)
    }

    @Test
    fun linuxSaysWhichPackagesItTakes() {
        assertEquals("deb", linuxPackageKind("ID=ubuntu\nID_LIKE=debian\n", false, false))
        assertEquals("deb", linuxPackageKind("ID=linuxmint\nID_LIKE=\"ubuntu debian\"\n", false, false))
        assertEquals("rpm", linuxPackageKind("ID=fedora\n", false, false))
        assertEquals("rpm", linuxPackageKind("ID=\"opensuse-tumbleweed\"\nID_LIKE=\"opensuse suse\"\n", false, false))
        assertEquals("rpm", linuxPackageKind("ID=rocky\nID_LIKE=\"rhel centos fedora\"\n", true, true))
        assertEquals("deb", linuxPackageKind(null, true, false))
        assertEquals(PACMAN_KIND, linuxPackageKind("ID=arch\n", false, false))
        assertEquals(PACMAN_KIND, linuxPackageKind("ID=manjaro\nID_LIKE=arch\n", true, true))
        assertEquals(PACMAN_KIND, linuxPackageKind("ID=endeavouros\nID_LIKE=arch\n", false, false))
        assertEquals(PACMAN_KIND, linuxPackageKind("ID=cachyos\nID_LIKE=arch\n", false, false))
        assertNull(linuxPackageKind("ID=alpine\n", false, false))
        assertEquals("x64", archName("amd64"))
        assertEquals("arm64", archName("aarch64"))
    }

    @Test
    fun theUpdateSettingsSurviveARestartAndOldFilesReadAsDefaults() {
        val file = File(temp.root, "octo/settings.json")
        val changed = UpdatePrefs(checkAutomatically = false, install = InstallWhen.OnQuit, earlyVersions = true)
        SettingsStore(file).update { it.copy(updates = changed) }
        assertEquals(changed, SettingsStore(file).current.updates)
        // A file from before updates were a setting.
        val older = File(temp.root, "old/settings.json").apply {
            parentFile.mkdirs()
            writeText(Json.encodeToString(AppSettings.serializer(), AppSettings()).replace(Regex(",?\\s*\"updates\"\\s*:\\s*\\{[^}]*\\}"), ""))
        }
        assertFalse(older.readText().contains("updates"))
        assertEquals(UpdatePrefs(), SettingsStore(older).current.updates)
    }
}
