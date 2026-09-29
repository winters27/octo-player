package app.winters.octo.desktop.settings

import java.io.File

// The operating systems the app knows its way around.
enum class DesktopOs { Windows, Mac, Linux }

fun currentOs(name: String = System.getProperty("os.name").orEmpty()): DesktopOs = when {
    name.startsWith("Windows", ignoreCase = true) -> DesktopOs.Windows
    name.startsWith("Mac", ignoreCase = true) || name.contains("Darwin", ignoreCase = true) -> DesktopOs.Mac
    else -> DesktopOs.Linux
}

// A folder of its own for a run of Octo beside the one in use, for measuring
// or trying a build: OCTO_PROFILE_DIR=<folder> keeps the settings in
// <folder>/config and the cache in <folder>/cache. The one-Octo lock lives
// with the settings, so such a run never hands over to a running Octo, and
// it leaves the system's octo:// links, Start with Windows and the jump
// list alone (see installedProgram).
const val PROFILE_VARIABLE = "OCTO_PROFILE_DIR"

// Whether this run keeps to a folder of its own.
fun separateProfile(env: (String) -> String? = System::getenv): Boolean = !env(PROFILE_VARIABLE).isNullOrBlank()

// Where the app keeps its settings and its cache on each system:
// %APPDATA%\Octo and %LOCALAPPDATA%\Octo\Cache on Windows,
// ~/Library/Application Support/Octo and ~/Library/Caches/Octo on macOS,
// and the XDG folders (~/.config/octo, ~/.cache/octo) on Linux.
class AppPlaces(val config: File, val cache: File) {
    companion object {
        fun forSystem(
            os: DesktopOs = currentOs(),
            env: (String) -> String? = System::getenv,
            home: String = System.getProperty("user.home").orEmpty(),
        ): AppPlaces = env(PROFILE_VARIABLE)?.takeIf(String::isNotBlank)?.let { File(it) }?.let { AppPlaces(File(it, "config"), File(it, "cache")) } ?: when (os) {
            DesktopOs.Windows -> {
                val roaming = env("APPDATA")?.takeIf(String::isNotBlank) ?: "$home\\AppData\\Roaming"
                val local = env("LOCALAPPDATA")?.takeIf(String::isNotBlank) ?: "$home\\AppData\\Local"
                AppPlaces(File(roaming, "Octo"), File(File(local, "Octo"), "Cache"))
            }
            DesktopOs.Mac -> AppPlaces(
                File(home, "Library/Application Support/Octo"),
                File(home, "Library/Caches/Octo"),
            )
            DesktopOs.Linux -> {
                val config = env("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank) ?: "$home/.config"
                val cache = env("XDG_CACHE_HOME")?.takeIf(String::isNotBlank) ?: "$home/.cache"
                AppPlaces(File(config, "octo"), File(cache, "octo"))
            }
        }
    }
}
