package app.winters.octo.output.dlna

// Finding media renderers (TVs, receivers, streamers) on the Wi-Fi: the
// phone asks the network who plays media, and each one answers with where
// its description is.

const val SSDP_HOST = "239.255.255.250"
const val SSDP_PORT = 1900
const val RENDERER_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"

// The question, sent to everyone on the network. `waitSeconds` is how long
// each device may take to answer, so they do not all answer at once.
fun searchMessage(target: String = RENDERER_TYPE, waitSeconds: Int = 2): String =
    "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: $SSDP_HOST:$SSDP_PORT\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: $waitSeconds\r\n" +
        "ST: $target\r\n" +
        "USER-AGENT: Android UPnP/1.1 Octo/1.0\r\n" +
        "\r\n"

// One device's answer: where its description is, its unique name, what
// kind of device it said it is, and what software it runs.
data class SsdpReply(val location: String, val usn: String, val target: String, val server: String?) {
    // The device's own id, the same across its answers.
    val deviceId: String get() = usn.substringBefore("::")
}

// Reads an answer. Null for anything that is not a renderer's answer with
// a web address for its description.
fun parseSsdpReply(text: String): SsdpReply? {
    val lines = text.split("\r\n", "\n")
    val status = lines.firstOrNull()?.trim() ?: return null
    if (!status.startsWith("HTTP/1.1 200") && !status.startsWith("HTTP/1.0 200")) return null
    val headers = lines.drop(1).filter { ':' in it }.associate { line ->
        line.substringBefore(':').trim().uppercase() to line.substringAfter(':').trim()
    }
    val location = headers["LOCATION"]?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        ?: return null
    val target = headers["ST"].orEmpty()
    if (!target.contains("MediaRenderer", ignoreCase = true)) return null
    val usn = headers["USN"]?.takeIf { it.isNotBlank() } ?: location
    return SsdpReply(location = location, usn = usn, target = target, server = headers["SERVER"])
}
