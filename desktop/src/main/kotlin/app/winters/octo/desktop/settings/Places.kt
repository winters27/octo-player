package app.winters.octo.desktop.settings

import java.io.File

// The operating systems the app knows its way around.
enum class DesktopOs { Windows, Mac, Linux }

fun currentOs(name: String = System.getProperty("os.name").orEmpty()): DesktopOs = when {
    name.startsWith("Windows", ignoreCase = true) -> DesktopOs.Windows
    name.startsWith("Mac", ignoreCase = true) || name.contains("Darwin", ignoreCase = true) -> DesktopOs.Mac
    else -> DesktopOs.Linux
}

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
        ): AppPlaces = when (os) {
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
