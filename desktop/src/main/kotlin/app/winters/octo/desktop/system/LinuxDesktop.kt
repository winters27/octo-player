package app.winters.octo.desktop.system

import app.winters.octo.desktop.player.RepeatMode
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Linux talks to the desktop over D-Bus, from the JVM with no native code:
// the MPRIS media player interfaces (media keys, the desktop's player
// widget, headset buttons), notifications, and logind's news of sleep.

// A plain value as a D-Bus variant, with the type D-Bus should see.
fun variantOf(value: Any): Variant<*> = when (value) {
    is Variant<*> -> value
    is ObjectPath -> Variant(DBusPath(value.path))
    is String -> Variant(value)
    is Boolean -> Variant(value)
    is Double -> Variant(value)
    is Float -> Variant(value.toDouble())
    is Long -> Variant(value)
    is Int -> Variant(value)
    is List<*> -> Variant(value.map { it.toString() }, "as")
    is Map<*, *> -> Variant(value.entries.associate { (k, v) -> k.toString() to variantOf(v!!) }, "a{sv}")
    else -> Variant(value.toString())
}

// Each value of a map as a variant, for GetAll and PropertiesChanged.
fun variantsOf(values: Map<String, Any>): Map<String, Variant<*>> = values.mapValues { (_, v) -> variantOf(v) }

@DBusInterfaceName(Mpris.ROOT)
@Suppress("FunctionName")
interface MprisRoot : DBusInterface {
    fun Raise()

    fun Quit()
}

@DBusInterfaceName(Mpris.PLAYER)
@Suppress("FunctionName")
interface MprisPlayer : DBusInterface {
    fun Next()

    fun Previous()

    fun Pause()

    fun PlayPause()

    fun Stop()

    fun Play()

    fun Seek(offset: Long)

    fun SetPosition(trackId: DBusPath, position: Long)

    fun OpenUri(uri: String)

    // Sent when the listener jumps in the song, so widgets move their line.
    class Seeked(path: String, val position: Long) : DBusSignal(path, position)
}

// The object at /org/mpris/MediaPlayer2: both MPRIS interfaces and their
// properties, served from what the app last showed. Methods are called on
// D-Bus threads, so the state is read under a lock.
class MprisObject(
    private val positionMs: () -> Long,
    private val events: (SystemEvent) -> Unit,
) : MprisRoot, MprisPlayer, Properties {
    private val lock = Any()
    private var now: NowPlaying? = null
    private var artUrl: String? = null
    private var volume = 1.0
    private var shuffle = false
    private var repeat = RepeatMode.Off

    override fun getObjectPath(): String = Mpris.OBJECT_PATH

    fun update(now: NowPlaying?, artUrl: String? = this.artUrl) = synchronized(lock) {
        this.now = now
        this.artUrl = artUrl
    }

    fun updateVolume(volume: Double) = synchronized(lock) { this.volume = volume }

    fun updateModes(shuffle: Boolean, repeat: RepeatMode) = synchronized(lock) {
        this.shuffle = shuffle
        this.repeat = repeat
    }

    // The Player properties as sent, Metadata and Position included.
    fun playerProperties(): Map<String, Any> = synchronized(lock) {
        Mpris.playerProperties(now, volume, shuffle, repeat) + mapOf(
            "Metadata" to Mpris.metadata(now, artUrl),
            "Position" to Mpris.micros(positionMs()),
        )
    }

    override fun Raise() = events(SystemEvent.Raise)

    override fun Quit() = events(SystemEvent.Quit)

    override fun Next() = events(SystemEvent.Next)

    override fun Previous() = events(SystemEvent.Previous)

    override fun Pause() = events(SystemEvent.Pause)

    override fun PlayPause() = events(SystemEvent.Toggle)

    override fun Stop() = events(SystemEvent.Stop)

    override fun Play() = events(SystemEvent.Play)

    override fun Seek(offset: Long) = events(SystemEvent.SeekBy(offset / 1000))

    // Only for the song playing now, as the specification asks.
    override fun SetPosition(trackId: DBusPath, position: Long) {
        val current = synchronized(lock) { now } ?: return
        if (trackId.path != Mpris.trackPath(current) || position < 0) return
        if (current.durationMs > 0 && position / 1000 > current.durationMs) return
        events(SystemEvent.SeekTo(position / 1000))
    }

    override fun OpenUri(uri: String) = events(SystemEvent.OpenUri(uri))

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A {
        val all = if (interfaceName == Mpris.ROOT) Mpris.rootProperties() else playerProperties()
        return variantOf(all[propertyName] ?: return null as A) as A
    }

    // The volume, shuffle and repeat can be set. A rate of 0 means pause, as
    // the specification says; other rates are left to the app's own setting.
    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        if (interfaceName != Mpris.PLAYER) return
        val raw = (value as? Variant<*>)?.value ?: value
        when (propertyName) {
            "Volume" -> (raw as? Number)?.let { events(SystemEvent.SetVolume(it.toFloat().coerceIn(0f, 1f))) }
            "Shuffle" -> (raw as? Boolean)?.let { events(SystemEvent.SetShuffle(it)) }
            "LoopStatus" -> (raw as? String)?.let(Mpris::repeatOf)?.let { events(SystemEvent.SetRepeat(it)) }
            "Rate" -> (raw as? Number)?.takeIf { it.toDouble() == 0.0 }?.let { events(SystemEvent.Pause) }
        }
    }

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
        Mpris.ROOT -> variantsOf(Mpris.rootProperties())
        Mpris.PLAYER -> variantsOf(playerProperties())
        else -> emptyMap()
    }
}

// The media controls on Linux: an MPRIS player on the session bus.
class LinuxMediaControls(private val positionMs: () -> Long) : SystemMediaControls {
    override val label = "MPRIS (D-Bus)"

    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "octo-mpris").apply { isDaemon = true } }
    private var connection: DBusConnection? = null
    private var player: MprisObject? = null
    private var lastSent: Map<String, Any> = emptyMap()

    override fun start(events: (SystemEvent) -> Unit): Boolean = runCatching {
        worker.submit<Boolean> {
            val bus = DBusConnectionBuilder.forSessionBus().build()
            val made = MprisObject(positionMs, events)
            bus.exportObject(Mpris.OBJECT_PATH, made)
            bus.requestBusName(Mpris.BUS_NAME)
            connection = bus
            player = made
            true
        }.get(5, TimeUnit.SECONDS)
    }.getOrDefault(false)

    override fun showTrack(now: NowPlaying, art: CoverArt?) = later {
        player?.update(now, art?.file?.toPath()?.toUri()?.toString())
        announce()
    }

    override fun showPlayback(now: NowPlaying, positionMs: Long, jumped: Boolean) = later {
        player?.update(now)
        announce()
        if (jumped) connection?.sendMessage(MprisPlayer.Seeked(Mpris.OBJECT_PATH, Mpris.micros(positionMs)))
    }

    override fun clear() = later {
        player?.update(null, null)
        announce()
    }

    override fun showVolume(volume: Float) = later {
        player?.updateVolume(volume.toDouble())
        announce()
    }

    override fun showModes(shuffle: Boolean, repeat: RepeatMode) = later {
        player?.updateModes(shuffle, repeat)
        announce()
    }

    // Tells listeners which properties changed since the last time.
    private fun announce() {
        val bus = connection ?: return
        val properties = player?.playerProperties()?.minus("Position") ?: return
        val changed = properties.filter { (k, v) -> lastSent[k] != v }
        lastSent = properties
        if (changed.isEmpty()) return
        bus.sendMessage(Properties.PropertiesChanged(Mpris.OBJECT_PATH, Mpris.PLAYER, variantsOf(changed), emptyList()))
    }

    override fun close() {
        later {
            runCatching { connection?.releaseBusName(Mpris.BUS_NAME) }
            runCatching { connection?.close() }
            connection = null
        }
        worker.shutdown()
        // Octo is quitting, so the bus gets a moment to let go, no more.
        runCatching { worker.awaitTermination(QUIT_WAIT_MS, TimeUnit.MILLISECONDS) }
    }

    private fun later(call: () -> Unit) {
        if (worker.isShutdown) return
        runCatching { worker.execute { runCatching(call) } }
    }
}

@DBusInterfaceName("org.freedesktop.Notifications")
@Suppress("FunctionName")
interface DesktopNotifications : DBusInterface {
    fun Notify(
        appName: String,
        replacesId: UInt32,
        appIcon: String,
        summary: String,
        body: String,
        actions: List<String>,
        hints: Map<String, @JvmSuppressWildcards Variant<*>>,
        expireTimeout: Int,
    ): UInt32
}

// "Now playing" notices through the desktop's notification service. Each
// one replaces the last, so songs never pile up.
class LinuxNotifier(private val iconPath: String?) : Notifier {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "octo-notify").apply { isDaemon = true } }
    private var connection: DBusConnection? = null
    private var lastId = UInt32(0)

    override fun show(title: String, text: String) {
        if (worker.isShutdown) return
        worker.execute {
            runCatching {
                val bus = connection ?: DBusConnectionBuilder.forSessionBus().build().also { connection = it }
                val service = bus.getRemoteObject("org.freedesktop.Notifications", "/org/freedesktop/Notifications", DesktopNotifications::class.java)
                lastId = service.Notify("Octo", lastId, iconPath.orEmpty(), title, text, emptyList(), mapOf("category" to Variant("x-gnome.music"), "transient" to Variant(true)), 5000)
            }
        }
    }

    override fun close() {
        worker.execute { runCatching { connection?.close() } }
        worker.shutdown()
    }
}

@DBusInterfaceName("org.freedesktop.login1.Manager")
interface LoginManager : DBusInterface {
    // True just before the machine sleeps, false once it has woken.
    class PrepareForSleep(path: String, val start: Boolean) : DBusSignal(path, start)
}

// Hears logind say the machine is about to sleep, and that it woke. A best
// effort: with no system bus (a container, say) it hears nothing.
class LinuxSleepWatch : AutoCloseable {
    private var connection: DBusConnection? = null

    fun start(events: (SystemEvent) -> Unit): Boolean = runCatching {
        val bus = DBusConnectionBuilder.forSystemBus().build()
        bus.addSigHandler(LoginManager.PrepareForSleep::class.java) { signal ->
            events(if (signal.start) SystemEvent.Sleep else SystemEvent.Wake)
        }
        connection = bus
        true
    }.getOrDefault(false)

    override fun close() {
        runCatching { connection?.close() }
        connection = null
    }
}
