package app.winters.octo.playback

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

// How a server song sits in the queue: its id on the server and what its
// file is, never a signed address. The address is signed as the song loads.
const val STREAM_SCHEME = "octo-stream"

// A server song as the queue holds it: which server, its id there, its type
// and bits a second, and, once chosen, what exactly to ask the server for.
// A request is chosen ("pinned") just before the song loads, so the saved
// copy and the signed address always agree.
data class StreamRef(
    val sourceId: String?,
    val serverId: String,
    val mimeType: String?,
    val bitrate: Int?,
    val pinned: StreamRequest? = null,
) {
    fun pin(request: StreamRequest) = copy(pinned = request)

    // What to ask for: the pinned request, or the one for this stream size.
    fun request(quality: StreamQuality): StreamRequest = pinned ?: streamRequest(mimeType, bitrate, quality)
}

fun streamUri(ref: StreamRef): String = buildString {
    append(STREAM_SCHEME).append("://song/").append(encode(ref.serverId))
    val params = listOfNotNull(
        ref.sourceId?.let { "source" to it },
        ref.mimeType?.let { "mime" to it },
        ref.bitrate?.let { "bitrate" to "$it" },
        ref.pinned?.let { "max" to (it.maxKbps?.toString() ?: RAW) },
    )
    if (params.isNotEmpty()) append('?').append(params.joinToString("&") { (k, v) -> "$k=${encode(v)}" })
}

// Reads back what `streamUri` wrote. Anything else, such as a phone file,
// is not a server song.
fun parseStreamUri(uri: String): StreamRef? {
    val parsed = try {
        URI(uri)
    } catch (e: Exception) {
        return null
    }
    if (parsed.scheme != STREAM_SCHEME || parsed.rawAuthority != "song") return null
    val id = parsed.rawPath?.removePrefix("/")?.takeIf { it.isNotEmpty() && '/' !in it } ?: return null
    val query = parsed.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
        val at = pair.indexOf('=')
        if (at < 0) decode(pair) to "" else decode(pair.substring(0, at)) to decode(pair.substring(at + 1))
    }
    val pinned = query["max"]?.let { max -> if (max == RAW) StreamRequest(null) else max.toIntOrNull()?.let(::StreamRequest) }
    return StreamRef(
        sourceId = query["source"]?.ifEmpty { null },
        serverId = decode(id),
        mimeType = query["mime"]?.ifEmpty { null },
        bitrate = query["bitrate"]?.toIntOrNull(),
        pinned = pinned,
    )
}

// The name a server song is saved under on the phone: the server, the song,
// its file type and what was asked for. Never the signed address, which has
// a new random part every time, so the same song finds its saved copy.
fun streamCacheKey(sourceId: String, ref: StreamRef, request: StreamRequest): String =
    "$sourceId|${ref.serverId}|${ref.mimeType ?: "unknown"}|${request.maxKbps ?: RAW}"

// Every request any stream size could make for this song: the file as it
// is first, then smaller MP3s, largest first.
fun requestVariants(ref: StreamRef): List<StreamRequest> =
    StreamQuality.entries.map { streamRequest(ref.mimeType, ref.bitrate, it) }
        .distinct()
        .sortedByDescending { it.maxKbps ?: Int.MAX_VALUE }

// The request to load a song with. What this connection's stream size asks
// for, unless it is not saved on the phone and another size is: then that
// one plays, since it costs no data and works with no connection. Failing
// that, a size already partly saved, such as the one a song started playing
// in: a seek after moving from Wi-Fi to mobile data must read the same file,
// or it would land in the middle of a different one.
fun chooseRequest(
    sourceId: String,
    ref: StreamRef,
    quality: StreamQuality,
    fullySaved: (String) -> Boolean,
    partlySaved: (String) -> Boolean = { false },
): StreamRequest {
    ref.pinned?.let { return it }
    val wanted = streamRequest(ref.mimeType, ref.bitrate, quality)
    val key = { request: StreamRequest -> streamCacheKey(sourceId, ref, request) }
    if (fullySaved(key(wanted))) return wanted
    val others = requestVariants(ref).filter { it != wanted }
    others.firstOrNull { fullySaved(key(it)) }?.let { return it }
    if (partlySaved(key(wanted))) return wanted
    return others.firstOrNull { partlySaved(key(it)) } ?: wanted
}

private const val RAW = "raw"

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
