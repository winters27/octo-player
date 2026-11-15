package app.winters.octo.subsonic

import com.squareup.moshi.JsonReader
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.BufferedSource
import okio.blackholeSink
import okio.buffer
import java.io.IOException

// How many songs of a listed answer (a playlist's entries, an album's
// songs) are handed on at once.
const val LISTED_PAGE = 500

// How many values of a long list one piece of a streamed form holds.
private const val FORM_PIECE = 500

// Values a playlist call carries that may be too many to hold at once:
// how many there are, and the values a page at a time, in order, as often
// as they are asked for. A page is read while the call is being sent, on
// the sending thread, so it may block.
interface PagedValues {
    val size: Int

    fun pages(pageSize: Int): Sequence<List<String>>

    companion object {
        // Values already in memory.
        fun of(values: List<String>): PagedValues = object : PagedValues {
            override val size = values.size

            override fun pages(pageSize: Int) = values.asSequence().chunked(pageSize)
        }
    }
}

// Reads a Subsonic answer that lists songs under one member (`key`, such as
// "playlist") as an array (`listKey`, such as "entry"), handing the songs on
// a page at a time as they are read, so the whole list is never held. Each
// song is decoded on its own exactly as a whole answer's would be, with the
// same `json`. The rest of the member is decoded at the end, by `head`,
// without the list. The status may come before or after the list, so it is
// judged at the end, as readStarred judges it: this returns only when the
// answer was read to its end and its status is ok, and otherwise throws
// what a whole decode would have: NotSubsonic for no answer, the error the
// server reported, a missing member, then a song or head that did not
// decode. A null list counts as none; any other list that is not an array
// goes to the head's decode, which fails on it as a whole decode does. A
// member or list given twice is refused as NotSubsonic, since the pages
// handed on cannot be taken back. With `default`, an answer missing the
// member answers it, as `get` with a default does.
internal suspend fun <T> readListed(
    source: BufferedSource,
    json: Json,
    key: String,
    listKey: String,
    head: KSerializer<T>,
    pageSize: Int = LISTED_PAGE,
    default: T? = null,
    page: suspend (List<Song>) -> Unit,
): T {
    val reader = JsonReader.of(source)
    val waiting = ArrayList<Song>(pageSize)
    var answered = false
    var status: String? = null
    var code = 0
    var message = "Request failed"
    var member: JsonElement? = null
    var members: LinkedHashMap<String, JsonElement>? = null
    var listed = false
    var failure: Exception? = null
    reader.beginObject()
    while (reader.hasNext()) {
        if (reader.nextName() != "subsonic-response") {
            reader.skipValue()
            continue
        }
        answered = true
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "status" -> status = scalarOf(reader)
                "error" -> if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "code" -> code = scalarOf(reader)?.toIntOrNull() ?: 0
                            "message" -> message = scalarOf(reader) ?: message
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                } else {
                    reader.skipValue()
                }
                key -> {
                    if (member != null || members != null) throw SubsonicException.NotSubsonic("The server listed \"$key\" twice")
                    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                        member = elementOf(reader, json)
                        continue
                    }
                    val kept = LinkedHashMap<String, JsonElement>()
                    members = kept
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if (name != listKey) {
                            kept[name] = elementOf(reader, json)
                            continue
                        }
                        if (listed) throw SubsonicException.NotSubsonic("The server listed \"$listKey\" twice")
                        listed = true
                        when (reader.peek()) {
                            JsonReader.Token.NULL -> reader.skipValue()
                            JsonReader.Token.BEGIN_ARRAY -> {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    val text = reader.nextSource().use { it.readUtf8() }
                                    if (failure != null) continue
                                    val song = try {
                                        json.decodeFromJsonElement(Song.serializer(), json.parseToJsonElement(text))
                                    } catch (e: IllegalArgumentException) {
                                        failure = e
                                        continue
                                    }
                                    waiting += song
                                    if (waiting.size == pageSize) {
                                        page(waiting.toList())
                                        waiting.clear()
                                    }
                                }
                                reader.endArray()
                            }
                            else -> kept[name] = elementOf(reader, json)
                        }
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }
    reader.endObject()
    if (!answered) throw SubsonicException.NotSubsonic("The server did not answer like a Subsonic server")
    if (status != "ok") throw subsonicError(code, message)
    val found = member ?: members?.let(::JsonObject) ?: default?.let { return it } ?: throw SubsonicException.Server(0, "Missing \"$key\" in the answer")
    failure?.let { throw it }
    val decoded = json.decodeFromJsonElement(head, found)
    if (waiting.isNotEmpty()) page(waiting.toList())
    return decoded
}

// Reads a playlist call's answer as a tree, as `decode` reads a whole one,
// but with the songs of the playlist it names skipped as they arrive:
// the answer's own object with the playlist's head in place of the
// playlist. Null when the body holds no answer object.
internal fun readAnswerHead(source: BufferedSource, json: Json): JsonObject? {
    val reader = JsonReader.of(source)
    var answer: JsonObject? = null
    reader.beginObject()
    while (reader.hasNext()) {
        if (reader.nextName() != "subsonic-response" || reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            reader.skipValue()
            continue
        }
        val root = LinkedHashMap<String, JsonElement>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (name != "playlist" || reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                root[name] = elementOf(reader, json)
                continue
            }
            val head = LinkedHashMap<String, JsonElement>()
            reader.beginObject()
            while (reader.hasNext()) {
                val member = reader.nextName()
                if (member == "entry") reader.skipValue() else head[member] = elementOf(reader, json)
            }
            reader.endObject()
            root[name] = JsonObject(head)
        }
        reader.endObject()
        answer = JsonObject(root)
    }
    reader.endObject()
    return answer
}

// The next value as a tree, read from its own text.
private fun elementOf(reader: JsonReader, json: Json): JsonElement = json.parseToJsonElement(reader.nextSource().use { it.readUtf8() })

// A string or number as text; null for null or anything else.
private fun scalarOf(reader: JsonReader): String? = when (reader.peek()) {
    JsonReader.Token.STRING, JsonReader.Token.NUMBER -> reader.nextString()
    JsonReader.Token.NULL -> reader.nextNull<String>()
    else -> {
        reader.skipValue()
        null
    }
}

// One playlist call whose values may be many: its fixed params, then each
// list's values under the list's name, in order.
internal class PagedCall(val endpoint: String, val params: List<Pair<String, String>>, val lists: List<Pair<String, PagedValues>> = emptyList()) {
    // Every param as one list, for a call sent in its address, which
    // carries at most a batch.
    fun pairs(): List<Pair<String, String>> =
        params + lists.flatMap { (name, values) -> values.pages(FORM_PIECE).flatMap { page -> page.asSequence().map { name to it } }.toList() }
}

// A form body holding a call's params and its long lists, written a piece
// at a time from the lists as it is sent, so a list of any length is never
// held. The bytes are those of one FormBody holding every pair: each piece
// is a FormBody of its own, and pieces are joined by "&". Its length comes
// from a pass that counts them, kept, and it can be written again, since
// OkHttp may send a body twice. A failure reading a list while it is sent
// is an IOException, which OkHttp reports as a failed call instead of
// losing its thread to it.
internal class StreamedForm(private val call: PagedCall) : RequestBody() {
    private val length: Long by lazy { writeAll(blackholeSink().buffer()) }

    override fun contentType(): MediaType? = FORM_TYPE

    override fun contentLength(): Long = guarded { length }

    override fun isOneShot(): Boolean = false

    override fun writeTo(sink: BufferedSink) {
        guarded { writeAll(sink) }
    }

    // Writes every piece, answering how many bytes that was.
    private fun writeAll(sink: BufferedSink): Long {
        var written = 0L
        var first = true
        for (piece in pieces()) {
            if (piece.size == 0) continue
            if (!first) {
                sink.writeUtf8("&")
                written += 1
            }
            first = false
            piece.writeTo(sink)
            written += piece.contentLength()
        }
        sink.flush()
        return written
    }

    private fun pieces(): Sequence<FormBody> = sequence {
        yield(formOf(call.params))
        for ((name, values) in call.lists) {
            for (page in values.pages(FORM_PIECE)) yield(formOf(page.map { name to it }))
        }
    }

    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException(e)
    }

    private companion object {
        val FORM_TYPE: MediaType? = FormBody.Builder().build().contentType()

        fun formOf(pairs: List<Pair<String, String>>): FormBody = FormBody.Builder().apply { pairs.forEach { (key, value) -> add(key, value) } }.build()
    }
}

// The calls that make a playlist's songs exactly these, as replaceSongsCalls
// makes them: a form body holds every song in one call; otherwise the
// songs go in batches of `perCall`, the first replacing the list and the
// rest added to its end.
internal fun replaceSongsPaged(
    playlistId: String,
    songs: PagedValues,
    formPost: Boolean,
    perCall: Int = PLAYLIST_SONGS_PER_CALL,
    ifStamp: String? = null,
    ownIds: Boolean = false,
): Sequence<PagedCall> {
    val batches = batchesOf(songs, formPost, perCall)
    return sequenceOf(PagedCall("createPlaylist", replaceParams(playlistId, ifStamp, ownIds), listOf("songId" to batches.first()))) +
        addSongsPaged(playlistId, batches.drop(1))
}

// The first call that makes a new playlist, as createCalls makes it, and
// the batches to add once the server says the new id.
internal fun createPaged(name: String, songs: PagedValues, formPost: Boolean, perCall: Int = PLAYLIST_SONGS_PER_CALL): Pair<PagedCall, Sequence<PagedValues>> {
    val batches = batchesOf(songs, formPost, perCall)
    return PagedCall("createPlaylist", listOf("name" to name), listOf("songId" to batches.first())) to batches.drop(1)
}

// An updatePlaylist call per batch of songs to add, as addSongsCalls makes them.
internal fun addSongsPaged(playlistId: String, batches: Sequence<PagedValues>): Sequence<PagedCall> =
    batches.filter { it.size > 0 }.map { PagedCall("updatePlaylist", listOf("playlistId" to playlistId), listOf("songIdToAdd" to it)) }

// The calls for updatePlaylist, as updateCalls makes them: every removal
// goes in the first call with the first batch of songs to add, the other
// batches after it.
internal fun updatePaged(
    playlistId: String,
    name: String?,
    comment: String?,
    public: Boolean?,
    songIdsToAdd: PagedValues,
    songIndexesToRemove: PagedValues,
    songsToRemove: PagedValues,
    formPost: Boolean,
    perCall: Int = PLAYLIST_SONGS_PER_CALL,
): Sequence<PagedCall> {
    val batches = batchesOf(songIdsToAdd, formPost, perCall)
    val params = buildList {
        add("playlistId" to playlistId)
        name?.let { add("name" to it) }
        comment?.let { add("comment" to it) }
        public?.let { add("public" to "$it") }
    }
    val first = PagedCall(
        "updatePlaylist",
        params,
        listOf("songIndexToRemove" to songIndexesToRemove, "octoSongToRemove" to songsToRemove, "songIdToAdd" to batches.first()),
    )
    return sequenceOf(first) + addSongsPaged(playlistId, batches.drop(1))
}

// The values in the batches songBatches makes: all at once in a form body
// or when they fit one call, otherwise `perCall` at a time. Always at least
// one, so an empty list still makes its call.
private fun batchesOf(values: PagedValues, formPost: Boolean, perCall: Int): Sequence<PagedValues> {
    if (formPost || values.size <= perCall) return sequenceOf(values)
    return values.pages(perCall).map { PagedValues.of(it) }
}
