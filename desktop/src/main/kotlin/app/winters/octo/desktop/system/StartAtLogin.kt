package app.winters.octo.desktop.system


// Starting Octo when the listener signs in to Windows: a value in the
// current user's Run key naming the installed program, with --tray added
// when Octo should start hidden in the tray. Only the installed app writes
// it, since a build runs on Java and would not be there after a restart.
// Windows' own switch for it (Task Manager's Startup apps) is read, never
// changed.

const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
const val STARTUP_APPROVED_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run"

// The value's name, which Task Manager shows beside Octo's icon.
const val RUN_VALUE = "Octo"

// Added to the command when Octo should start in the tray.
const val TRAY_FLAG = "--tray"

// Where Octo's start-at-sign-in line is kept, and Windows' own switch for it.
interface StartupEntries {
    fun read(name: String): String?

    fun write(name: String, command: String): Boolean

    fun remove(name: String): Boolean

    // Whether the listener turned it off in Task Manager.
    fun turnedOff(name: String): Boolean
}

// The line Windows runs at sign-in.
fun startCommand(program: String, inTray: Boolean): String = "\"$program\"" + if (inTray) " $TRAY_FLAG" else ""

// Whether a command starts an Octo program, so Octo only ever removes its own.
fun isOctoCommand(command: String?): Boolean {
    val program = command?.trim()?.let { if (it.startsWith("\"")) it.drop(1).substringBefore('"') else it.substringBefore(' ') } ?: return false
    // The last part of the path, whichever slash the command uses.
    val name = program.substringAfterLast('/').substringAfterLast('\\').lowercase()
    return name == "octo.exe" || name == "octo"
}

// Whether this launch asked to start in the tray.
fun startsInTray(args: List<String>): Boolean = args.any { it.trim().equals(TRAY_FLAG, ignoreCase = true) }

// Brings the sign-in line in step with the setting: written when it should
// be there and differs, removed when it should not and is Octo's. Answers
// whether it is as wanted.
fun syncStartAtLogin(on: Boolean, inTray: Boolean, program: String, entries: StartupEntries, name: String = RUN_VALUE): Boolean {
    val now = entries.read(name)
    if (on) {
        val wanted = startCommand(program, inTray)
        return now == wanted || (entries.write(name, wanted) && entries.read(name) == wanted)
    }
    if (now == null || !isOctoCommand(now)) return true
    return entries.remove(name) && entries.read(name) == null
}

// Windows' sign-in list for the current user.
class WindowsStartupEntries(
    private val runKey: String = RUN_KEY,
    private val approvedKey: String = STARTUP_APPROVED_KEY,
) : StartupEntries {
    override fun read(name: String): String? = WindowsRegistry.read(runKey, name)

    override fun write(name: String, command: String): Boolean = WindowsRegistry.write(runKey, name, command)

    override fun remove(name: String): Boolean = WindowsRegistry.removeValue(runKey, name)

    override fun turnedOff(name: String): Boolean = turnedOffBy(WindowsRegistry.firstByte(approvedKey, name))
}

// Task Manager keeps its switch as a binary value whose first byte is odd
// when the program is turned off (2 on, 3 off). No value is on.
fun turnedOffBy(firstByte: Int?): Boolean = firstByte != null && firstByte % 2 == 1

// The switch's caption, which says so when Task Manager has Octo turned off.
fun startCaption(turnedOff: Boolean): String =
    if (turnedOff) {
        "Turned off in Task Manager's Startup apps, so Windows skips it. Turn Octo on there as well."
    } else {
        "Opens Octo when you sign in, for music ready as soon as you are."
    }
