package app.winters.octo.desktop.update

import app.winters.octo.desktop.system.LINK_KEY
import app.winters.octo.desktop.system.RUN_KEY
import app.winters.octo.desktop.system.RUN_VALUE
import java.io.File
import java.util.Base64

// Where the MSI puts Octo for this user, under %LOCALAPPDATA% (installationPath
// in desktop/build.gradle.kts), apart from its settings and cache. Earlier
// builds went into %LOCALAPPDATA%\Octo, the cache's folder.
const val WINDOWS_INSTALL_FOLDER = "OctoPlayer"

// The MSI's upgrade code (upgradeUuid in desktop/build.gradle.kts), which
// Windows Installer files the installed Octo under.
const val WINDOWS_UPGRADE_CODE = "4f7b3c1e-8a52-4d6b-9e0f-2c8d1a7b5e93"

// Where a new version of Octo goes for this user when nobody picks a folder.
fun defaultWindowsProgram(
    env: (String) -> String? = System::getenv,
    home: String = System.getProperty("user.home").orEmpty(),
): File {
    val local = env("LOCALAPPDATA")?.takeIf(String::isNotBlank) ?: "$home\\AppData\\Local"
    return File("$local\\$WINDOWS_INSTALL_FOLDER\\Octo.exe")
}

// The places that name Octo's program to start it: Start with Windows, and
// octo:// links with their icon. A moved Octo changes them.
val PROGRAM_VALUES: List<Pair<String, String>> = listOf(
    "HKCU:\\$RUN_KEY" to RUN_VALUE,
    "HKCU:\\$LINK_KEY\\shell\\open\\command" to "(default)",
    "HKCU:\\$LINK_KEY\\DefaultIcon" to "(default)",
)

// Puts a downloaded update in. On Windows, a small script runs the MSI
// once Octo has quit, then (for "Restart to update") opens the new Octo.
// The same upgrade code makes the MSI replace the installed Octo, and a
// new version may put it in another folder, so the script asks Windows
// Installer where Octo is afterwards.
//
// Octo's Windows launcher keeps the app in a job object that ends every
// process in it when Octo ends, so a script started straight from here
// would die with Octo, before the MSI could run. The first script only
// asks Windows (WMI's Win32_Process.Create) to start the second outside
// the job, hidden, and returns; the second waits for Octo to exit.
object WindowsInstall {
    // The command that starts it all, for ProcessBuilder: no argument holds
    // a quote or a space, since each script travels base64 encoded (Java's
    // quoting on Windows garbles quotes inside arguments).
    fun command(msi: File, waitFor: List<Long>, log: File, program: File?, relaunch: Boolean, fallback: File? = defaultWindowsProgram()): List<String> =
        powershell(launchOutsideJob(installScript(msi, waitFor, log, program, relaunch, fallback)))

    // Waits for each of Octo's processes to end (the launcher and the
    // Java one inside it), runs the MSI with its progress bar and no
    // questions, finds Octo where it is now, points Start with Windows and
    // octo:// links at it if it moved, then starts it again if asked. A
    // failed MSI changes nothing, and the Octo that was there opens again.
    // `program` is the Octo running now, null for a build.
    fun installScript(
        msi: File,
        waitFor: List<Long>,
        log: File,
        program: File?,
        relaunch: Boolean,
        fallback: File?,
        upgradeCode: String = WINDOWS_UPGRADE_CODE,
        values: List<Pair<String, String>> = PROGRAM_VALUES,
    ): String = buildString {
        appendLine("\$ErrorActionPreference = 'SilentlyContinue'")
        if (waitFor.isNotEmpty()) appendLine("foreach (\$id in @(${waitFor.joinToString(", ")})) { Wait-Process -Id \$id -Timeout 300 }")
        val arguments = "/i \"${msi.absolutePath}\" /passive /norestart /l*v \"${log.absolutePath}\""
        appendLine("Start-Process -FilePath 'msiexec.exe' -ArgumentList ${quote(arguments)} -Wait")
        if (program == null) return@buildString
        append(findProgram(program, fallback, upgradeCode))
        append(pointAtProgram(program, values))
        if (relaunch) appendLine("if (\$octo) { Start-Process -FilePath \$octo }")
    }

    // Sets $octo to the installed program: in the folder Windows Installer
    // has for Octo's upgrade code, else the old program if it is still
    // there (the MSI failed), else the new default. Null when none is there.
    fun findProgram(program: File, fallback: File?, upgradeCode: String = WINDOWS_UPGRADE_CODE): String = buildString {
        appendLine("\$octo = \$null")
        appendLine("function Get-Msi(\$on, \$name, \$with) { \$on.GetType().InvokeMember(\$name, [Reflection.BindingFlags]::GetProperty, \$null, \$on, \$with) }")
        appendLine("try {")
        appendLine("    \$installer = New-Object -ComObject WindowsInstaller.Installer")
        appendLine("    foreach (\$code in @(Get-Msi \$installer 'RelatedProducts' @(${quote("{" + upgradeCode.uppercase() + "}")}))) {")
        appendLine("        \$folder = Get-Msi \$installer 'ProductInfo' @(\$code, 'InstallLocation')")
        appendLine("        if (-not \$octo -and \$folder -and (Test-Path -LiteralPath (Join-Path \$folder 'Octo.exe'))) { \$octo = Join-Path \$folder 'Octo.exe' }")
        appendLine("    }")
        appendLine("} catch {}")
        val places = listOfNotNull(program, fallback).joinToString(", ") { quote(it.path) }
        appendLine("foreach (\$exe in @($places)) { if (-not \$octo -and (Test-Path -LiteralPath \$exe)) { \$octo = \$exe } }")
    }

    // When $octo is not the old program, each value that starts it by its
    // quoted path ("C:\...\Octo.exe" --tray) names the new one instead.
    fun pointAtProgram(program: File, values: List<Pair<String, String>>): String = buildString {
        appendLine("if (\$octo -and \$octo -ne ${quote(program.path)}) {")
        appendLine("    \$was = ${quote("\"" + program.path + "\"")}")
        appendLine("    \$now = '\"' + \$octo + '\"'")
        appendLine("    foreach (\$place in @(${values.joinToString(", ") { (key, name) -> "@(${quote(key)}, ${quote(name)})" }})) {")
        appendLine("        \$line = (Get-ItemProperty -LiteralPath \$place[0]).(\$place[1])")
        appendLine("        if (\$line -and \$line.StartsWith(\$was, [StringComparison]::OrdinalIgnoreCase)) { Set-ItemProperty -LiteralPath \$place[0] -Name \$place[1] -Value (\$now + \$line.Substring(\$was.Length)) }")
        appendLine("    }")
        appendLine("}")
    }

    // Starts a script as a hidden process of its own, outside Octo's job,
    // and ends with what Windows answered: 0 when it started.
    fun launchOutsideJob(script: String): String = buildString {
        appendLine("\$startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [uint16]0 }")
        appendLine("\$line = ${quote(powershell(script).joinToString(" "))}")
        appendLine("\$made = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = \$line; ProcessStartupInformation = \$startup }")
        appendLine("exit [int]\$made.ReturnValue")
    }

    fun powershell(script: String): List<String> = listOf(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
        "-EncodedCommand", encode(script),
    )

    // PowerShell's -EncodedCommand: the script as UTF-16LE, in base64.
    fun encode(script: String): String = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

    // A PowerShell string that says exactly this: single quotes take
    // nothing specially but a single quote, which is doubled.
    fun quote(text: String): String = "'" + text.replace("'", "''") + "'"
}

// The processes the MSI must wait for: this one, and the launcher that
// started it when that is Octo's own program.
fun octoProcesses(self: ProcessHandle = ProcessHandle.current()): List<Long> {
    val parent = self.parent().orElse(null)?.takeIf { handle ->
        handle.info().command().orElse("").substringAfterLast('\\').substringAfterLast('/').equals("Octo.exe", ignoreCase = true)
    }
    return listOfNotNull(self.pid(), parent?.pid())
}
