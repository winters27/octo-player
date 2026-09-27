package app.winters.octo.output.dlna

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

// Talking to a media renderer: its description, and the requests it takes
// (SOAP over HTTP). Everything here is plain text in and out.

const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"

// One of a renderer's services: its exact type (a renderer may offer a
// newer version), where requests go, and where its list of actions is.
data class ServiceEndpoint(val type: String, val controlUrl: String, val actionsUrl: String?)

// What a renderer says about itself.
data class RendererDescription(
    val id: String,
    val name: String,
    val manufacturer: String?,
    val model: String?,
    val transport: ServiceEndpoint,
    val rendering: ServiceEndpoint?,
    val connections: ServiceEndpoint?,
)

// Reads a renderer's description, fetched from `location`. Null when it is
// not one Octo can play on: no transport service to send songs to.
fun parseDescription(xml: String, location: String): RendererDescription? {
    val doc = parseXml(xml) ?: return null
    val root = doc.documentElement ?: return null
    val base = root.child("URLBase")?.text()?.takeIf { it.isNotBlank() } ?: location
    val devices = root.descendants("device")
    // The renderer itself, which may sit inside another device.
    val device = devices.firstOrNull { it.child("deviceType")?.text()?.contains("MediaRenderer") == true }
        ?: devices.firstOrNull { d -> d.services().any { it.first.contains(":AVTransport:") } }
        ?: return null
    val services = device.services()
    fun endpoint(kind: String): ServiceEndpoint? = services.firstOrNull { it.first.contains(":$kind:") }?.let { (type, el) ->
        val control = el.child("controlURL")?.text()?.takeIf { it.isNotBlank() } ?: return@let null
        ServiceEndpoint(type, resolveUrl(base, control), el.child("SCPDURL")?.text()?.takeIf { it.isNotBlank() }?.let { resolveUrl(base, it) })
    }
    val transport = endpoint("AVTransport") ?: return null
    val id = device.child("UDN")?.text()?.takeIf { it.isNotBlank() } ?: location
    val name = device.child("friendlyName")?.text()?.takeIf { it.isNotBlank() }
        ?: device.child("modelName")?.text()?.takeIf { it.isNotBlank() }
        ?: "Media renderer"
    return RendererDescription(
        id = id,
        name = name,
        manufacturer = device.child("manufacturer")?.text()?.takeIf { it.isNotBlank() },
        model = device.child("modelName")?.text()?.takeIf { it.isNotBlank() },
        transport = transport,
        rendering = endpoint("RenderingControl"),
        connections = endpoint("ConnectionManager"),
    )
}

// Whether a service's list of actions has `action`, such as
// SetNextAVTransportURI, which not every renderer offers.
fun hasAction(actionsXml: String, action: String): Boolean {
    val doc = parseXml(actionsXml) ?: return false
    return doc.documentElement?.descendants("action")?.any { it.child("name")?.text() == action } == true
}

// The loudest a renderer's volume goes, from its RenderingControl list of
// actions and values; 100 when it does not say.
fun volumeMaximum(actionsXml: String?): Int {
    val doc = actionsXml?.let(::parseXml) ?: return 100
    val volume = doc.documentElement?.descendants("stateVariable")?.firstOrNull { it.child("name")?.text() == "Volume" }
    val max = volume?.descendants("maximum")?.firstOrNull()?.text()?.toIntOrNull()
    return max?.takeIf { it > 0 } ?: 100
}

// A request to a renderer's service, as the body of an HTTP POST.
fun soapRequest(serviceType: String, action: String, args: List<Pair<String, String>>): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
    append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
    append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
    append("<u:").append(action).append(" xmlns:u=\"").append(xmlEscape(serviceType)).append("\">")
    args.forEach { (name, value) -> append('<').append(name).append('>').append(xmlEscape(value)).append("</").append(name).append('>') }
    append("</u:").append(action).append("></s:Body></s:Envelope>")
}

// The SOAPACTION header that goes with it.
fun soapAction(serviceType: String, action: String): String = "\"$serviceType#$action\""

// A renderer said no, with UPnP's number for why: 401 and 602 mean it does
// not have that action, 701 a transition it cannot make, 714-716 a
// resource it cannot play.
class UpnpError(val code: Int?, message: String) : IOException(message)

// Reads a renderer's answer: its values by name. Throws UpnpError when it
// is a refusal, or when it cannot be read.
fun parseSoapResponse(xml: String, action: String): Map<String, String> {
    val doc = parseXml(xml) ?: throw UpnpError(null, "Unreadable answer to $action")
    val root = doc.documentElement ?: throw UpnpError(null, "Empty answer to $action")
    root.descendants("Fault").firstOrNull()?.let { fault ->
        val code = fault.descendants("errorCode").firstOrNull()?.text()?.trim()?.toIntOrNull()
        val text = fault.descendants("errorDescription").firstOrNull()?.text()?.trim()
            ?: fault.descendants("faultstring").firstOrNull()?.text()?.trim()
            ?: "Refused"
        throw UpnpError(code, "$action: $text")
    }
    val response = root.descendants("${action}Response").firstOrNull() ?: throw UpnpError(null, "No answer to $action")
    return response.childElements().associate { it.localNameOrTag() to it.text() }
}

// Times as renderers write them, "H:MM:SS" with maybe a fraction. Null for
// "NOT_IMPLEMENTED" and anything else unreadable.
fun parseUpnpTime(text: String?): Long? {
    val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = value.split(':')
    if (parts.size != 3) return null
    val hours = parts[0].toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull() ?: return null
    val seconds = parts[2].toDoubleOrNull() ?: return null
    if (hours < 0 || minutes !in 0..59 || seconds < 0 || seconds >= 60) return null
    return hours * 3_600_000 + minutes * 60_000 + Math.round(seconds * 1000)
}

// A place in a song, for a seek: "H:MM:SS".
fun formatUpnpTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    return String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
}

// A song's length, for its description: "H:MM:SS.mmm".
fun formatUpnpDuration(ms: Long): String {
    val clean = ms.coerceAtLeast(0)
    return formatUpnpTime(clean) + String.format(Locale.ROOT, ".%03d", clean % 1000)
}

// Text made safe to put inside XML, with characters XML cannot hold left out.
fun xmlEscape(text: String): String = buildString(text.length) {
    text.forEach { c ->
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            '\t', '\n', '\r' -> append(c)
            else -> if (c >= ' ' && c.code != 0xFFFE && c.code != 0xFFFF) append(c)
        }
    }
}

internal fun resolveUrl(base: String, relative: String): String = try {
    URI(base.trim()).resolve(relative.trim()).toString()
} catch (e: Exception) {
    relative.trim()
}

// Some devices start their text with this invisible mark.
private val BYTE_ORDER_MARK = Char(0xFEFF)

// Parsed without fetching anything the text points at, since it comes from
// devices on the network.
internal fun parseXml(xml: String): Document? = try {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.isExpandEntityReferences = false
    runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    factory.newDocumentBuilder().parse(InputSource(StringReader(xml.trim().trimStart(BYTE_ORDER_MARK))))
} catch (e: Exception) {
    null
}

private fun Element.localNameOrTag(): String = localName ?: tagName.substringAfter(':')

internal fun Element.childElements(): List<Element> {
    val list = childNodes
    return (0 until list.length).mapNotNull { list.item(it) as? Element }
}

internal fun Element.child(name: String): Element? = childElements().firstOrNull { it.localNameOrTag() == name }

internal fun Element.descendants(name: String): List<Element> {
    val found = mutableListOf<Element>()
    fun walk(el: Element) {
        el.childElements().forEach { child ->
            if (child.localNameOrTag() == name) found += child
            walk(child)
        }
    }
    if (localNameOrTag() == name) found += this
    walk(this)
    return found
}

internal fun Element.text(): String = textContent.orEmpty().trim()

// This device's own services (not those of devices inside it), by type.
private fun Element.services(): List<Pair<String, Element>> =
    child("serviceList")?.childElements()?.filter { it.localNameOrTag() == "service" }?.mapNotNull { service ->
        service.child("serviceType")?.text()?.let { it to service }
    }.orEmpty()
