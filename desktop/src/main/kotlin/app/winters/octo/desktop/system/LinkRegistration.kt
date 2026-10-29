package app.winters.octo.desktop.system

import app.winters.octo.desktop.settings.separateProfile

// octo:// links open Octo. macOS learns this from the app's own details;
// on Windows and Linux the installed app tells the system itself, for this
// user only, whenever it runs from somewhere new.
// Nothing is written when Octo runs from a build rather than an install,
// or while it runs its self-check.

// The installed app's own program, or null outside an installed app. The
// Windows launcher does not say where it is, so the program this process
// runs as tells: Octo's own launcher once installed, Java in a build.
fun installedProgram(
    told: String? = System.getProperty("jpackage.app-path"),
    running: String? = ProcessHandle.current().info().command().orElse(null),
    // A run with a folder of its own (OCTO_PROFILE_DIR) counts as a build.
    separate: Boolean = separateProfile(),
): String? {
    if (separate) return null
    told?.takeIf(String::isNotBlank)?.let { return it }
    val name = running?.let { java.io.File(it).name.lowercase() } ?: return null
    return running.takeIf { name == "octo.exe" || name == "octo" }
}

// The command Windows runs for a link.
fun linkCommand(program: String): String = "\"$program\" \"%1\""

// Makes octo:// links open this program, unless they already do. True
// once they do. `key` is the scheme's place under the current user.
fun registerLinksOnWindows(program: String, key: String = LINK_KEY): Boolean {
    val command = linkCommand(program)
    if (linkProgramOnWindows(key) == command) return true
    WindowsRegistry.write(key, null, "URL:Octo")
    WindowsRegistry.write(key, "URL Protocol", "")
    WindowsRegistry.write("$key\\DefaultIcon", null, "\"$program\",0")
    WindowsRegistry.write("$key\\shell\\open\\command", null, command)
    return linkProgramOnWindows(key) == command
}

// The command octo:// links run now, or null when none is set.
fun linkProgramOnWindows(key: String = LINK_KEY): String? = WindowsRegistry.read("$key\\shell\\open\\command")

// Removes a scheme's registration and everything under it.
internal fun forgetLinksOnWindows(key: String) = WindowsRegistry.remove(key)

// The scheme's place under the current user; an update that moves Octo
// points it at the new program (update/InstallLauncher.kt).
internal const val LINK_KEY = "Software\\Classes\\octo"

// On Linux the package's own desktop entry starts Octo with no file, so
// "Open with Octo" would open nothing, and it does not claim octo:// links.
// The installed app writes an entry of the same name into the user's own
// applications folder, which the desktop reads in place of the package's:
// the same app, handed the files and links it is opened with.

// The desktop entry for the installed program. The package keeps its icon
// beside the program: /opt/octo/bin/Octo has /opt/octo/lib/Octo.png.
fun linuxDesktopEntry(program: String, icon: String = program.substringBeforeLast('/').substringBeforeLast('/') + "/lib/Octo.png"): String {
    val types = (AUDIO_TYPES + "x-scheme-handler/octo").joinToString(";", postfix = ";")
    return """
        [Desktop Entry]
        Type=Application
        Name=Octo
        Comment=A music player for Subsonic, Navidrome and Octo servers
        Exec="$program" %U
        Icon=$icon
        Terminal=false
        Categories=AudioVideo;Audio;Player;
        MimeType=$types
    """.trimIndent() + "\n"
}

// Writes the entry into `applications` (normally ~/.local/share/applications)
// when it differs, and makes Octo the handler for octo:// links. Audio files
// only gain "Open with Octo"; the listener's usual player stays the default.
// True once written.
fun registerWithLinuxDesktop(
    program: String,
    applications: java.io.File,
    makeDefault: Boolean = true,
    env: (String) -> String? = System::getenv,
): Boolean {
    // Flatpak exports its own desktop entry, with a sandbox-aware command.
    if (!env("FLATPAK_ID").isNullOrBlank()) return true
    val appImage = env("APPIMAGE")?.takeIf(String::isNotBlank)
    // An extracted AppImage has no durable file to register.
    if (appImage == null && !env("APPDIR").isNullOrBlank()) return true
    val entry = java.io.File(applications, LINUX_ENTRY)
    // The AppImage's mount disappears on exit. Keep its icon outside it and
    // point Exec at the original .AppImage, including when its path has spaces.
    val icon = if (appImage != null) {
        val source = java.io.File(program).parentFile.parentFile.resolve("lib/Octo.png")
        val target = applications.parentFile.resolve("icons/hicolor/512x512/apps/octo.png")
        if (!runCatching {
            target.parentFile.mkdirs()
            source.copyTo(target, overwrite = true)
        }.isSuccess) return false
        target.path
    } else {
        program.substringBeforeLast('/').substringBeforeLast('/') + "/lib/Octo.png"
    }
    val text = linuxDesktopEntry(appImage ?: program, icon)
    if (entry.isFile && runCatching { entry.readText() }.getOrNull() == text) return true
    return runCatching {
        applications.mkdirs()
        entry.writeText(text)
        if (makeDefault) {
            val process = ProcessBuilder("xdg-mime", "default", LINUX_ENTRY, "x-scheme-handler/octo").redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        true
    }.getOrDefault(false)
}

// The user's own applications folder.
fun linuxApplicationsFolder(env: (String) -> String? = System::getenv, home: String = System.getProperty("user.home")): java.io.File {
    val data = env("XDG_DATA_HOME")?.takeIf(String::isNotBlank) ?: "$home/.local/share"
    return java.io.File(data, "applications")
}

// The package's entry name, which the media controls announce too.
private const val LINUX_ENTRY = "octo-Octo.desktop"

// The audio types the installers associate with Octo.
private val AUDIO_TYPES = listOf("audio/mpeg", "audio/flac", "audio/mp4", "audio/aac", "audio/ogg", "audio/opus", "audio/wav", "audio/aiff")
