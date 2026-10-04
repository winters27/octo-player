package app.winters.octo.desktop.audio

import app.winters.octo.desktop.player.OutputDevice
import app.winters.octo.desktop.settings.DesktopOs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

// Whether an output plays over Bluetooth, as far as the system says: the
// lyrics start earlier there by default, since Bluetooth holds more sound
// back than a cable. Asks the system, so it is called off the window's
// thread; anything it cannot tell counts as not Bluetooth.
//
// Windows keeps each sound endpoint's bus in the registry under the id the
// engine gives it ("wasapi:{0.0.0.00000000}.{guid}"): BTHENUM for classic
// Bluetooth, BTHLEDEVICE for Bluetooth LE. macOS says each device's
// transport in system_profiler. On Linux the engine plays to PulseAudio or
// PipeWire, whose default sink is named bluez_... for Bluetooth.
fun isBluetoothOutput(device: OutputDevice?, os: DesktopOs, ask: (List<String>) -> String? = ::askSystem): Boolean {
    if (device == null) return false
    if (nameSaysBluetooth(device.name)) return true
    return runCatching {
        when (os) {
            DesktopOs.Windows -> endpointGuidOf(device.id)?.let { guid ->
                ENDPOINT_BUS_VALUES.any { value -> busIsBluetooth(registryString(ask(listOf("reg", "query", "$RENDER_KEY\\$guid\\Properties", "/v", value)))) }
            } ?: false
            DesktopOs.Mac -> macDeviceIsBluetooth(ask(listOf("system_profiler", "SPAudioDataType", "-json")), device.name)
            DesktopOs.Linux -> linuxSinkIsBluetooth(ask(listOf("pactl", "get-default-sink")))
        }
    }.getOrDefault(false)
}

// The endpoint's own id in the registry, from the engine's id for it.
fun endpointGuidOf(id: String): String? = EndpointId.find(id)?.groupValues?.get(1)

private val EndpointId = Regex("""\{0\.0\.0\.[0-9A-Fa-f]+}\.(\{[0-9A-Fa-f-]{36}})$""")

private const val RENDER_KEY = """HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render"""

// The endpoint's enumerator name (PKEY_Device_EnumeratorName), then its
// device's instance path, which starts with the same bus.
private val ENDPOINT_BUS_VALUES = listOf("{a45c254e-df1c-4efd-8020-67d146a850e0},24", "{b3f8fa53-0004-438e-9003-51a46e139bfc},2")

// The text of the one value `reg query` printed, or null.
fun registryString(answer: String?): String? =
    answer?.lineSequence()?.firstNotNullOfOrNull { line -> RegistryLine.find(line)?.groupValues?.get(1)?.trim() }

private val RegistryLine = Regex("""\sREG_(?:SZ|EXPAND_SZ)\s+(.*)$""")

// BTHENUM, BTHLEDEVICE and BTHLE, alone or at the start of a device path
// ("{1}.BTHENUM\...").
fun busIsBluetooth(bus: String?): Boolean =
    bus != null && Regex("""(^|[.\\])BTH(ENUM|LE)""", RegexOption.IGNORE_CASE).containsMatchIn(bus)

// Whether system_profiler lists the device by this name as Bluetooth.
fun macDeviceIsBluetooth(json: String?, name: String): Boolean {
    val root = runCatching { Json.parseToJsonElement(json ?: return false) }.getOrNull() ?: return false
    return devicesIn(root).any { item ->
        val itemName = item["_name"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        val transport = item["coreaudio_device_transport"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        itemName.equals(name, ignoreCase = true) && transport?.contains("bluetooth", ignoreCase = true) == true
    }
}

// Every object in the answer, however deep, since the devices sit inside
// a group.
private fun devicesIn(element: JsonElement): Sequence<JsonObject> = when (element) {
    is JsonObject -> sequenceOf(element) + element.values.asSequence().flatMap(::devicesIn)
    is JsonArray -> element.asSequence().flatMap(::devicesIn)
    else -> emptySequence()
}

// PulseAudio's and PipeWire's Bluetooth sinks are named bluez_sink... or
// bluez_output....
fun linuxSinkIsBluetooth(sink: String?): Boolean = sink?.trim()?.startsWith("bluez", ignoreCase = true) == true

// A name that says so itself, as some systems name their outputs.
fun nameSaysBluetooth(name: String): Boolean =
    Regex("""\b(bluetooth|bluez|airpods)\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)

// A command's output, or null when it is missing, fails or takes too long.
private fun askSystem(command: List<String>): String? {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
    return try {
        val text = output.get(5, TimeUnit.SECONDS)
        if (process.waitFor(1, TimeUnit.SECONDS) && process.exitValue() == 0) text else null
    } catch (e: Exception) {
        process.destroy()
        null
    }
}
