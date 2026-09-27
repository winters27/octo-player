package app.winters.octo.output.dlna

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.winters.octo.output.DeviceShape
import app.winters.octo.output.OutputDevice
import app.winters.octo.output.OutputFamily
import app.winters.octo.output.OutputFinder
import app.winters.octo.output.localNetworkAddress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// How often to ask the network again while looking, and how long to
// listen for answers each time.
private const val SEARCH_EVERY_MS = 10_000L
private const val LISTEN_MS = 3_000L

// A renderer not heard from in this long is taken off the list.
private const val FORGET_AFTER_MS = 45_000L

// Finds media renderers on the Wi-Fi (SSDP), reads what each one is, and
// connects to one. It looks only while asked to: while the device list is
// open.
@Singleton
class RendererFinder @Inject constructor(
    @ApplicationContext private val context: Context,
    http: OkHttpClient,
) : OutputFinder {
    override val family = OutputFamily.Renderer

    private val client = UpnpClient(
        http.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build(),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Found(val description: RendererDescription, val device: OutputDevice, @Volatile var seenAt: Long)

    // By the address of each description, so a renderer is read only once.
    private val found = ConcurrentHashMap<String, Found>()
    private val _devices = MutableStateFlow<List<OutputDevice>>(emptyList())
    override val devices: StateFlow<List<OutputDevice>> = _devices

    private var looking: Job? = null
    private var events: OutputFinder.Events? = null

    override fun setEvents(events: OutputFinder.Events) {
        this.events = events
    }

    override fun startLooking() {
        if (looking?.isActive == true) return
        looking = scope.launch {
            while (isActive) {
                search()
                delay(SEARCH_EVERY_MS)
            }
        }
    }

    override fun stopLooking() {
        looking?.cancel()
        looking = null
    }

    override fun connect(device: OutputDevice) {
        val renderer = found.values.firstOrNull { it.device.id == device.id }?.description
        scope.launch {
            val output = try {
                renderer ?: throw IOException("Not found")
                // It answers, and says what it plays and whether it takes
                // the next song ahead of time.
                client.call(renderer.transport, "GetTransportInfo", listOf("InstanceID" to "0"))
                val sinks = renderer.connections?.let { runCatching { splitProtocols(client.call(it, "GetProtocolInfo", emptyList())["Sink"]) }.getOrNull() }.orEmpty()
                val takesNext = renderer.transport.actionsUrl?.let { url -> runCatching { hasAction(client.get(url), "SetNextAVTransportURI") }.getOrNull() } ?: true
                val maxVolume = volumeMaximum(renderer.rendering?.actionsUrl?.let { url -> runCatching { client.get(url) }.getOrNull() })
                RendererOutput(device, renderer, client, sinks, takesNext, maxVolume) { gone -> events?.ended(gone, lost = true) }
            } catch (e: Exception) {
                Log.i("Octo", "renderer: could not connect: ${e.javaClass.simpleName}")
                null
            }
            withContext(Dispatchers.Main) {
                if (output != null) events?.connected(output) else events?.failed(device)
            }
        }
    }

    // Asks who plays media, reads any renderer not met before, and forgets
    // those gone quiet.
    private suspend fun search() {
        val address = localNetworkAddress(context) ?: return
        val locations = try {
            ask(address)
        } catch (e: IOException) {
            Log.i("Octo", "renderer search: ${e.javaClass.simpleName}")
            emptyMap()
        }
        val now = SystemClock.elapsedRealtime()
        locations.forEach { (location, reply) ->
            found[location]?.let {
                it.seenAt = now
                return@forEach
            }
            describe(location, reply)?.let { found[location] = Found(it.first, it.second, now) }
        }
        found.entries.removeIf { now - it.value.seenAt > FORGET_AFTER_MS }
        _devices.value = found.values.map { it.device }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    // Sends the question twice (it goes over UDP, which can drop it) and
    // gathers the answers, by where each description is.
    private fun ask(address: InetAddress): Map<String, SsdpReply> {
        val replies = LinkedHashMap<String, SsdpReply>()
        MulticastSocket(InetSocketAddress(address, 0)).use { socket ->
            runCatching { socket.networkInterface = NetworkInterface.getByInetAddress(address) }
            socket.timeToLive = 4
            socket.soTimeout = 500
            val message = searchMessage().toByteArray(Charsets.US_ASCII)
            val group = InetSocketAddress(SSDP_HOST, SSDP_PORT)
            repeat(2) { socket.send(DatagramPacket(message, message.size, group)) }
            val buffer = ByteArray(2048)
            val until = SystemClock.elapsedRealtime() + LISTEN_MS
            while (SystemClock.elapsedRealtime() < until) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                parseSsdpReply(String(packet.data, 0, packet.length, Charsets.UTF_8))?.let { replies.putIfAbsent(it.location, it) }
            }
        }
        return replies
    }

    private fun describe(location: String, reply: SsdpReply): Pair<RendererDescription, OutputDevice>? {
        val description = try {
            parseDescription(client.get(location), location)
        } catch (e: IOException) {
            null
        } ?: return null
        val maker = listOfNotNull(description.manufacturer, description.model).joinToString(" ").ifBlank { null }
        val tv = listOfNotNull(description.name, description.model, reply.server).any { it.contains("TV", ignoreCase = false) || it.contains("television", ignoreCase = true) }
        val device = OutputDevice(
            id = "renderer:${description.id}",
            name = description.name,
            family = OutputFamily.Renderer,
            shape = if (tv) DeviceShape.Tv else DeviceShape.Speaker,
            detail = maker,
        )
        return description to device
    }
}
