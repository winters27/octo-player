package app.winters.octo.desktop.system

import app.winters.octo.desktop.settings.DesktopOs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Whether the computer is in its dark mode, read once before any window
// draws, so the first frame is already the right theme with Match on.
object DeviceMode {
    private const val PERSONALIZE = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
    private const val APPS_USE_LIGHT_THEME = "AppsUseLightTheme"

    // True for dark. Windows and macOS are read at once; Linux is the portal
    // watcher's answer so far, or, with `waitForPortal`, its first answer
    // (dark when it gives none in time).
    fun seed(os: DesktopOs, portal: PortalWatcher? = null, waitForPortal: Boolean = false): Boolean = when (os) {
        DesktopOs.Windows -> windowsDark(runCatching { WindowsRegistry.firstByte(PERSONALIZE, APPS_USE_LIGHT_THEME) }.getOrNull())
        DesktopOs.Mac -> macDark(runCatching(::macAppearance).getOrNull())
        DesktopOs.Linux -> if (waitForPortal) portal?.awaitFirst() ?: true else portal?.dark?.value ?: true
    }

    // Windows keeps 0 in AppsUseLightTheme for dark mode; with no value it
    // is dark.
    internal fun windowsDark(appsUseLightTheme: Int?): Boolean = appsUseLightTheme == null || appsUseLightTheme == 0

    // macOS answers `Dark` in dark mode and fails the read in light mode.
    internal fun macDark(answer: String?): Boolean = answer?.trim() == "Dark"

    private fun macAppearance(): String? {
        val process = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle").start()
        val answer = process.inputStream.bufferedReader().readText()
        return if (process.waitFor(MAC_READ_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) answer else null
    }

    private const val MAC_READ_SECONDS = 2L
}

// How long a launch with Match on waits for the desktop portal's answer
// before showing the dark theme.
const val THEME_PORTAL_TIMEOUT_MS = 1_000L

// The portal's color-scheme value: 1 is dark, 2 is light, 0 (no preference)
// counts as dark.
fun portalValueIsDark(value: Int): Boolean = value != 2

// Where the portal's answers come from.
interface PortalSource {
    // The color-scheme now, or null when the portal does not answer.
    fun read(): Int?

    // Calls `changed` with each new color-scheme; null when the portal is not there.
    fun watch(changed: (Int) -> Unit): AutoCloseable?
}

// Follows the desktop's dark or light mode on Linux through the XDG desktop
// portal, on a thread of its own. `dark` holds the latest answer (dark
// until there is one) and `answered` says whether there has been one.
class PortalWatcher(
    private val timeoutMs: Long = THEME_PORTAL_TIMEOUT_MS,
    private val source: PortalSource = DbusPortal(),
) : AutoCloseable {
    private val _dark = MutableStateFlow(true)
    val dark: StateFlow<Boolean> = _dark

    private val _answered = MutableStateFlow(false)
    val answered: StateFlow<Boolean> = _answered

    private val first = CountDownLatch(1)

    @Volatile
    private var watching: AutoCloseable? = null

    // Starts asking; the answers arrive on the watcher's own thread.
    fun start() {
        Thread({
            runCatching { watching = source.watch(::heard) }
            runCatching { source.read() }.getOrNull()?.let(::heard)
        }, "octo-theme-portal").apply { isDaemon = true }.start()
    }

    // Waits for the first answer, up to the timeout, and says whether it was
    // dark. An answer that comes later is still taken into `dark`.
    fun awaitFirst(): Boolean {
        first.await(timeoutMs, TimeUnit.MILLISECONDS)
        return _dark.value
    }

    private fun heard(value: Int) {
        _dark.value = portalValueIsDark(value)
        _answered.value = true
        first.countDown()
    }

    override fun close() {
        runCatching { watching?.close() }
        watching = null
    }
}

@DBusInterfaceName("org.freedesktop.portal.Settings")
@Suppress("FunctionName")
interface PortalSettings : DBusInterface {
    fun Read(namespace: String, key: String): Variant<*>

    class SettingChanged(path: String, val namespace: String, val key: String, val value: Variant<*>) : DBusSignal(path, namespace, key, value)
}

// The portal over the session bus.
class DbusPortal : PortalSource {
    private var connection: DBusConnection? = null

    private fun bus(): DBusConnection = connection ?: DBusConnectionBuilder.forSessionBus().build().also { connection = it }

    override fun read(): Int? {
        val settings = bus().getRemoteObject(PORTAL_NAME, PORTAL_PATH, PortalSettings::class.java)
        return portalNumber(settings.Read(APPEARANCE, COLOR_SCHEME))
    }

    override fun watch(changed: (Int) -> Unit): AutoCloseable? {
        val bus = bus()
        val handle = bus.addSigHandler(PortalSettings.SettingChanged::class.java) { signal ->
            if (signal.namespace == APPEARANCE && signal.key == COLOR_SCHEME) portalNumber(signal.value)?.let(changed)
        }
        return AutoCloseable {
            runCatching { handle.close() }
            runCatching { bus.close() }
            connection = null
        }
    }

    private companion object {
        const val PORTAL_NAME = "org.freedesktop.portal.Desktop"
        const val PORTAL_PATH = "/org/freedesktop/portal/desktop"
        const val APPEARANCE = "org.freedesktop.appearance"
        const val COLOR_SCHEME = "color-scheme"
    }
}

// The number inside a portal answer, which may be wrapped in a variant more
// than once.
internal fun portalNumber(answer: Any?): Int? = when (answer) {
    is Variant<*> -> portalNumber(answer.value)
    is Number -> answer.toInt()
    else -> null
}
