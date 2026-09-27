package app.winters.octo.output.dlna

import app.winters.octo.output.dlnaFeatures

// What a renderer is told about a song as it is handed over, so its screen
// can show the title, artist, album and cover: a DIDL-Lite document.
data class DidlTrack(
    val url: String,
    val mimeType: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val coverUrl: String? = null,
    val durationMs: Long? = null,
    // Whether the renderer may ask for parts of the file, which is how it seeks.
    val seekable: Boolean = true,
    // A radio station, which never ends.
    val live: Boolean = false,
)

// How the song is fetched and what it is: "http-get:*:<type>:<DLNA flags>".
fun protocolInfo(mimeType: String, seekable: Boolean): String = "http-get:*:$mimeType:${dlnaFeatures(seekable)}"

fun didlLite(track: DidlTrack): String = buildString {
    append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
    append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
    append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
    append("xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">")
    append("<item id=\"octo\" parentID=\"0\" restricted=\"1\">")
    append("<dc:title>").append(xmlEscape(track.title)).append("</dc:title>")
    track.artist?.takeIf { it.isNotBlank() }?.let { artist ->
        append("<upnp:artist>").append(xmlEscape(artist)).append("</upnp:artist>")
        append("<dc:creator>").append(xmlEscape(artist)).append("</dc:creator>")
    }
    track.album?.takeIf { it.isNotBlank() }?.let { append("<upnp:album>").append(xmlEscape(it)).append("</upnp:album>") }
    track.coverUrl?.let { append("<upnp:albumArtURI>").append(xmlEscape(it)).append("</upnp:albumArtURI>") }
    append("<upnp:class>")
    append(if (track.live) "object.item.audioItem.audioBroadcast" else "object.item.audioItem.musicTrack")
    append("</upnp:class>")
    append("<res protocolInfo=\"").append(xmlEscape(protocolInfo(track.mimeType, track.seekable))).append('"')
    track.durationMs?.takeIf { it > 0 && !track.live }?.let { append(" duration=\"").append(formatUpnpDuration(it)).append('"') }
    append('>').append(xmlEscape(track.url)).append("</res>")
    append("</item></DIDL-Lite>")
}

// Whether a renderer plays a type, by the list it gave of what it takes
// ("http-get:*:audio/flac:*,..."). With no list, it is assumed to.
fun sinkAccepts(sinkProtocols: List<String>, mimeType: String): Boolean {
    if (sinkProtocols.isEmpty()) return true
    val wanted = mimeType.lowercase()
    val family = wanted.substringBefore('/') + "/*"
    return sinkProtocols.any { entry ->
        val parts = entry.trim().split(':')
        if (parts.size < 3) return@any false
        val protocol = parts[0].lowercase()
        val type = parts[2].lowercase()
        (protocol == "http-get" || protocol == "*") && (type == wanted || type == family || type == "*")
    }
}

// The list a renderer gives of what it takes, split up.
fun splitProtocols(sink: String?): List<String> = sink.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
