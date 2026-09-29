package app.winters.octo.desktop.update

import java.io.File
import java.util.Base64

// Puts a downloaded update in. On Windows, a small script runs the MSI
// once Octo has quit, then (for "Restart to update") opens the new Octo.
// The same upgrade code makes the MSI replace the installed Octo in place.
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
    fun command(msi: File, waitFor: List<Long>, relaunch: File?, log: File): List<String> =
        powershell(launchOutsideJob(installScript(msi, waitFor, relaunch, log)))

    // Waits for each of Octo's processes to end (the launcher and the
    // Java one inside it), runs the MSI with its progress bar and no
    // questions, then starts Octo again if asked. A failed MSI changes
    // nothing, and the Octo that was there opens again.
    fun installScript(msi: File, waitFor: List<Long>, relaunch: File?, log: File): String = buildString {
        appendLine("\$ErrorActionPreference = 'SilentlyContinue'")
        if (waitFor.isNotEmpty()) appendLine("foreach (\$id in @(${waitFor.joinToString(", ")})) { Wait-Process -Id \$id -Timeout 300 }")
        val arguments = "/i \"${msi.absolutePath}\" /passive /norestart /l*v \"${log.absolutePath}\""
        appendLine("Start-Process -FilePath 'msiexec.exe' -ArgumentList ${quote(arguments)} -Wait")
        if (relaunch != null) appendLine("Start-Process -FilePath ${quote(relaunch.absolutePath)}")
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
