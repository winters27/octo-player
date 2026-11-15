package app.winters.octo.subsonic

import com.squareup.moshi.JsonReader
import okio.BufferedSource

// One item getStarred2 lists: a song, album or artist by its id on the
// server, and when it was starred there.
data class StarredItem(val kind: StarredKind, val id: String, val starredAt: String?)

// The lists getStarred2 answers with, by the name its answer gives each.
enum class StarredKind(val wire: String) {
    Song("song"),
    Album("album"),
    Artist("artist"),
}

// How many starred items are handed on at once.
const val STARRED_PAGE = 500

// The error a Subsonic answer reports, by its code.
internal fun subsonicError(code: Int, message: String, conflict: Boolean = false): SubsonicException = when (code) {
    40, 44 -> SubsonicException.WrongCredentials(message)
    41, 42 -> SubsonicException.AuthNotSupported(code, message)
    70 -> SubsonicException.NotFound(message)
    else -> SubsonicException.Server(code, message, conflict)
}

// Reads a getStarred2 answer as it arrives and hands on what it lists, a
// page at a time, so the whole answer is never held. The status may come
// before or after the lists, so it is only judged at the end: this returns
// only when the answer was read to its end and its status is ok, and
// otherwise throws (the error the server reported, or NotSubsonic for an
// answer that is not a Subsonic one), whatever pages went before. An answer
// cut off part way throws the reader's IOException.
internal suspend fun readStarred(source: BufferedSource, page: suspend (List<StarredItem>) -> Unit) {
    val reader = JsonReader.of(source)
    val waiting = ArrayList<StarredItem>(STARRED_PAGE)
    var answered = false
    var status: String? = null
    var code = 0
    var message = "Request failed"
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
                "status" -> status = scalar(reader)
                "error" -> if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "code" -> code = scalar(reader)?.toIntOrNull() ?: 0
                            "message" -> message = scalar(reader) ?: message
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                } else {
                    reader.skipValue()
                }
                "starred2" -> readLists(reader) { item ->
                    waiting += item
                    if (waiting.size == STARRED_PAGE) {
                        page(waiting.toList())
                        waiting.clear()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }
    reader.endObject()
    if (!answered) throw SubsonicException.NotSubsonic("The server did not answer like a Subsonic server")
    if (status != "ok") throw subsonicError(code, message)
    if (waiting.isNotEmpty()) page(waiting.toList())
}

// The artist, album and song lists of a starred2 object, item by item.
private suspend fun readLists(reader: JsonReader, item: suspend (StarredItem) -> Unit) {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        reader.skipValue()
        return
    }
    reader.beginObject()
    while (reader.hasNext()) {
        val name = reader.nextName()
        val kind = StarredKind.entries.firstOrNull { it.wire == name }
        if (kind == null || reader.peek() != JsonReader.Token.BEGIN_ARRAY) {
            reader.skipValue()
            continue
        }
        reader.beginArray()
        while (reader.hasNext()) readItem(reader, kind)?.let { item(it) }
        reader.endArray()
    }
    reader.endObject()
}

// One listed item's id and starred time; null when it has no id.
private fun readItem(reader: JsonReader, kind: StarredKind): StarredItem? {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        reader.skipValue()
        return null
    }
    var id: String? = null
    var starredAt: String? = null
    reader.beginObject()
    while (reader.hasNext()) {
        when (reader.nextName()) {
            "id" -> id = scalar(reader)
            "starred" -> starredAt = scalar(reader)
            else -> reader.skipValue()
        }
    }
    reader.endObject()
    return id?.takeIf(String::isNotEmpty)?.let { StarredItem(kind, it, starredAt) }
}

// A string or number as text; null for null or anything else.
private fun scalar(reader: JsonReader): String? = when (reader.peek()) {
    JsonReader.Token.STRING, JsonReader.Token.NUMBER -> reader.nextString()
    JsonReader.Token.NULL -> reader.nextNull<String>()
    else -> {
        reader.skipValue()
        null
    }
}
