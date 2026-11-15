package app.winters.octo.playback

import app.winters.octo.offline.KeptCopyCheck
import app.winters.octo.offline.checkKeptCopy
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

// How a server song sits in the queue: its id on the server and what its
// file is, never a signed address. The address is signed as the song loads.
const val STREAM_SCHEME = "octo-stream"

// A server song as the queue holds it: which server, its id there, its type
// and bits a second, and, once chosen, what exactly to ask the server for.
// A request is chosen ("pinned") just before the song loads, so the saved
// copy and the signed address always agree. `size` is the size of the
// song's file on the server, when it says: the server can swap a song's
// file for another under the same id, and the new file has a new size.
data class StreamRef(
    val sourceId: String?,
    val serverId: String,
    val mimeType: String?,
    val bitrate: Int?,
    val pinned: StreamRequest? = null,
    val size: Long? = null,
    // The queue entry the song plays as, written in by the player as the
    // song goes into the queue, so each request of its play names the play
    // (see playTokenOf). Never part of the name it is saved under.
    val entry: String? = null,
    // A song found online (a find in the queue), marked with its entry: one
    // whose video the server may change. Never part of the saved name.
    val find: Boolean = false,
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
        ref.size?.let { "size" to "$it" },
        ref.pinned?.let { "max" to (it.maxKbps?.toString() ?: RAW) },
        // Written only when a request differs from a stream's, so older addresses read the same.
        ref.pinned?.takeIf { it.maxKbps != null && it.format != DownloadFormat.Mp3 }?.let { "fmt" to it.format.param },
        ref.pinned?.takeIf { it.maxKbps != null && !it.estimate }?.let { "est" to "0" },
        ref.entry?.let { "entry" to it },
        ref.find.takeIf { it }?.let { "find" to "1" },
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
    val format = DownloadFormat.entries.firstOrNull { it.param == query["fmt"] } ?: DownloadFormat.Mp3
    val estimate = query["est"] != "0"
    val pinned = query["max"]?.let { max -> if (max == RAW) StreamRequest(null) else max.toIntOrNull()?.let { StreamRequest(it, format, estimate) } }
    return StreamRef(
        sourceId = query["source"]?.ifEmpty { null },
        serverId = decode(id),
        mimeType = query["mime"]?.ifEmpty { null },
        bitrate = query["bitrate"]?.toIntOrNull(),
        pinned = pinned,
        size = query["size"]?.toLongOrNull()?.takeIf { it > 0 },
        entry = query["entry"]?.ifEmpty { null },
        find = query["find"] == "1",
    )
}

// The name a server song is saved under on the phone: the server, the song,
// its file type, what was asked for and, when the server says, the size of
// its file, so a new file under the same id is saved apart from the old
// one. Never the signed address, which has a new random part every time,
// so the same song finds its saved copy.
fun streamCacheKey(sourceId: String, ref: StreamRef, request: StreamRequest): String =
    "$sourceId|${ref.serverId}|${ref.mimeType ?: "unknown"}|${requestToken(request)}" + (ref.size?.let { "|$it" } ?: "")

// "raw", the cap in kbps for an MP3 (as every name was before formats), or
// the format's name before the cap for another ("opus128"), so each format
// is saved apart and an MP3 is found under the name it always had.
private fun requestToken(request: StreamRequest): String = when {
    request.maxKbps == null -> RAW
    request.format == DownloadFormat.Mp3 -> "${request.maxKbps}"
    else -> "${request.format.param}${request.maxKbps}"
}

private val RequestTokenPattern = Regex("(?:opus|aac)?[0-9]+")

// A saved key less the file size, when it has one. The part before a size
// is a request ("raw", a number, or a format and a number), which a file
// type never is.
fun cacheKeyWithoutSize(key: String): String {
    val head = key.substringBeforeLast('|', "")
    val before = head.substringAfterLast('|')
    return if (before == RAW || RequestTokenPattern.matches(before)) head else key
}

// One server song, whatever type, request or size it is saved in: its
// server and its id there. Every saved name of the song starts with it.
fun songKey(sourceId: String, serverId: String): String = "$sourceId|$serverId"

// The song a saved name is of (see streamCacheKey): the name less its size,
// its request and its type.
fun songOfCacheKey(key: String): String = cacheKeyWithoutSize(key).substringBeforeLast('|').substringBeforeLast('|')

// What the phone has saved under a name, for picking the name a song reads
// and writes its saved copy under.
interface SavedCopies {
    // Whether any of it is saved.
    fun has(key: String): Boolean

    // How long it is whole, or -1 when that is not known.
    fun length(key: String): Long

    // The server's file size noted with it, if one was.
    fun notedSize(key: String): Long?

    fun note(key: String, size: Long)

    fun drop(key: String)
}

// The name a song's saved copy is read and written under. A copy saved
// under the name without the file size (as songs were saved before names
// carried it) is still used while nothing says it is of another file: see
// checkKeptCopy. One of another file is let go, so it never plays, and the
// song is fetched again under its full name.
fun savedCopyKey(sourceId: String, ref: StreamRef, request: StreamRequest, saved: SavedCopies): String {
    val key = streamCacheKey(sourceId, ref, request)
    val size = ref.size ?: return key
    if (saved.has(key)) return key
    val older = streamCacheKey(sourceId, ref.copy(size = null), request)
    if (!saved.has(older)) return key
    return when (checkKeptCopy(saved.notedSize(older), size, saved.length(older), asIs = request.maxKbps == null)) {
        KeptCopyCheck.Keep -> older
        KeptCopyCheck.Note -> older.also { saved.note(it, size) }
        KeptCopyCheck.Refetch -> key.also { saved.drop(older) }
    }
}

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
// or it would land in the middle of a different one. `keyOf` names the
// saved copy of each request.
fun chooseRequest(
    sourceId: String,
    ref: StreamRef,
    quality: StreamQuality,
    fullySaved: (String) -> Boolean,
    partlySaved: (String) -> Boolean = { false },
    keyOf: (StreamRequest) -> String = { streamCacheKey(sourceId, ref, it) },
): StreamRequest {
    ref.pinned?.let { return it }
    val wanted = streamRequest(ref.mimeType, ref.bitrate, quality)
    val key = keyOf
    if (fullySaved(key(wanted))) return wanted
    val others = requestVariants(ref).filter { it != wanted }
    others.firstOrNull { fullySaved(key(it)) }?.let { return it }
    if (partlySaved(key(wanted))) return wanted
    return others.firstOrNull { partlySaved(key(it)) } ?: wanted
}

private const val RAW = "raw"

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
