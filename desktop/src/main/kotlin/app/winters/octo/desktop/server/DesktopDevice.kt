package app.winters.octo.desktop.server

import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.currentOs
import app.winters.octo.subsonic.DeviceIdentity
import app.winters.octo.subsonic.FamilyPlatform
import okhttp3.Interceptor
import java.net.InetAddress
import java.util.UUID

// This install's id, made the first time it is asked for and kept in the
// settings from then on.
fun deviceId(settings: SettingsStore): String {
    settings.current.deviceId.takeIf(String::isNotEmpty)?.let { return it }
    val made = UUID.randomUUID().toString()
    settings.update { if (it.deviceId.isEmpty()) it.copy(deviceId = made) else it }
    return settings.current.deviceId.ifEmpty { made }
}

// This computer as its own server knows it: its id and its name.
fun desktopDevice(settings: SettingsStore, name: () -> String = ::computerName): DeviceIdentity =
    DeviceIdentity(deviceId(settings), name())

// The computer's name as the system has it: Windows and most Linux shells
// say it in the environment; otherwise the network name, which can take a
// moment, so this is read off the window's thread.
fun computerName(env: (String) -> String? = System::getenv): String =
    env("COMPUTERNAME")?.takeIf(String::isNotBlank)
        ?: env("HOSTNAME")?.takeIf(String::isNotBlank)
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf(String::isNotBlank)?.removeSuffix(".local")
        ?: "Octo for ${osName(currentOs())}"

// The system as Octo's server names it.
fun osName(os: DesktopOs): String = when (os) {
    DesktopOs.Windows -> "Windows"
    DesktopOs.Mac -> "macOS"
    DesktopOs.Linux -> "Linux"
}

fun familyPlatform(os: DesktopOs): FamilyPlatform = when (os) {
    DesktopOs.Windows -> FamilyPlatform.Windows
    DesktopOs.Mac -> FamilyPlatform.MacOs
    DesktopOs.Linux -> FamilyPlatform.Linux
}

// "Octo/1.6.0 (Windows)": what the app calls itself to every host, like the
// phone's "Octo/<version> (Android)". A build with no version says "dev".
fun desktopUserAgent(version: String? = System.getProperty("octo.version"), os: DesktopOs = currentOs()): String =
    "Octo/${version?.trim()?.takeIf(String::isNotEmpty) ?: "dev"} (${osName(os)})"

// Sets the app's name on every request.
fun userAgentOf(agent: String) = Interceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", agent).build()) }
