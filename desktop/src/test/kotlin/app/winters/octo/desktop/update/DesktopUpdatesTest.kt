package app.winters.octo.desktop.update

import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.update.InstallWhen
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

    private fun updates(os: DesktopOs = DesktopOs.Windows, program: File? = null): DesktopUpdates {
        val client = OkHttpClient()
        val folder = File(temp.root, "updates")
        val updater = PlayerUpdater(
            PlayerApp.Desktop,
            PlayerVersion.parse("1.1.0")!!,
            ReleaseFeed(client, File(folder, "releases.json"), "Octo test", apiBase = github.api),
            client,
            folder,
            listOf(github.publicKey),
            pick = { installerFor(it, os, "x64", "deb") },
        )
        return DesktopUpdates(settings, UpdaterAvailability.On(PlayerVersion.parse("1.1.0")!!), os, folder, updater, start = { command ->
            started += command
            fakeProcess(if (launches) 0 else 1)
        }, program = program)
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
        )
        assertEquals("msi", installerFor(assets, DesktopOs.Windows, "x64", null)?.kind)
        assertEquals("dmg", installerFor(assets, DesktopOs.Mac, "arm64", null)?.kind)
        assertNull("no DMG for an Intel Mac", installerFor(assets, DesktopOs.Mac, "x64", null))
        assertEquals("rpm", installerFor(assets, DesktopOs.Linux, "x64", "rpm")?.kind)
        assertEquals("deb", installerFor(assets, DesktopOs.Linux, "x64", "deb")?.kind)
        assertNull(installerFor(assets, DesktopOs.Linux, "x64", null))
        assertNull(installerFor(assets, DesktopOs.Windows, "arm64", null))
    }

    @Test
    fun linuxSaysWhichPackagesItTakes() {
        assertEquals("deb", linuxPackageKind("ID=ubuntu\nID_LIKE=debian\n", false, false))
        assertEquals("deb", linuxPackageKind("ID=linuxmint\nID_LIKE=\"ubuntu debian\"\n", false, false))
        assertEquals("rpm", linuxPackageKind("ID=fedora\n", false, false))
        assertEquals("rpm", linuxPackageKind("ID=\"opensuse-tumbleweed\"\nID_LIKE=\"opensuse suse\"\n", false, false))
        assertEquals("rpm", linuxPackageKind("ID=rocky\nID_LIKE=\"rhel centos fedora\"\n", true, true))
        assertEquals("deb", linuxPackageKind(null, true, false))
        assertNull(linuxPackageKind("ID=arch\n", false, false))
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
