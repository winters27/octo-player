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

    private fun updates(os: DesktopOs = DesktopOs.Windows): DesktopUpdates {
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
        })
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
        val updates = updates()
        updates.check()
        updates.onQuit()
        assertTrue("Ask me waits for the button", started.isEmpty())
        settings.update { it.copy(updates = it.updates.copy(install = InstallWhen.OnQuit)) }
        updates.onQuit()
        assertEquals(1, started.size)
        // It does not open Octo again: the listener quit.
        assertFalse(installScriptOf(started.single()).contains("Start-Process -FilePath 'C:"))
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
    // with its path quoted, and opens Octo again.
    @Test
    fun theWindowsLauncherWaitsInstallsAndReopens() {
        val msi = File("C:\\Users\\O'Brien\\AppData\\Local\\Octo\\Cache\\updates\\desktop-v1.2.0\\Octo-1.2.0-windows-x64.msi")
        val octo = File("C:\\Users\\O'Brien\\AppData\\Local\\Octo\\Octo.exe")
        val command = WindowsInstall.command(msi, listOf(111, 222), octo, File(msi.parentFile, "install.log"))
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
        assertTrue(script, script.contains("Start-Process -FilePath 'msiexec.exe' -ArgumentList '/i \"C:\\Users\\O''Brien\\AppData\\Local\\Octo\\Cache\\updates\\desktop-v1.2.0\\Octo-1.2.0-windows-x64.msi\" /passive /norestart /l*v \"C:\\Users\\O''Brien\\AppData\\Local\\Octo\\Cache\\updates\\desktop-v1.2.0\\install.log\"' -Wait"))
        assertTrue(script.contains("Start-Process -FilePath 'C:\\Users\\O''Brien\\AppData\\Local\\Octo\\Octo.exe'"))
        // The MSI runs only after the wait.
        assertTrue(script.indexOf("Wait-Process") < script.indexOf("msiexec"))
    }

    @Test
    fun withoutAProgramToReopenTheScriptOnlyInstalls() {
        val script = WindowsInstall.installScript(File("C:\\u\\Octo.msi"), emptyList(), null, File("C:\\u\\install.log"))
        assertFalse(script.contains("Wait-Process"))
        assertEquals(1, Regex("Start-Process").findAll(script).count())
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
