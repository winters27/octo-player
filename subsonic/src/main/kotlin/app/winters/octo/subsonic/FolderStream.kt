package app.winters.octo.subsonic

import com.squareup.moshi.JsonReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okio.BufferedSource

// A play queue saved on the server, without its songs: the current one by
// id (the older form) or by its place (the index-based form), how far into
// it playback was, when it was saved and by which app.
data class QueueHead(
    val currentId: String?,
    val currentIndex: Int?,
    val positionMs: Long,
    val changed: String?,
    val changedBy: String?,
)

// Reads a folder answer (getIndexes' for the top, getMusicDirectory's for a
// folder) one child at a time, so a folder of many songs is never held: each
// song goes to `song` as it is read, decoded as a whole answer's would be;
// the folders are kept (a folder's worth) and answered at the end in the
// order musicDirectory and indexes give them, the top's letter groups before
// its loose folders. The status is judged at the end, as readListed judges
// it, and the same errors are thrown: NotSubsonic for no answer, the
// server's own error, a folder with no "directory" member; the top with none
// is empty, as indexes has it.
internal suspend fun readFolderAnswer(source: BufferedSource, json: Json, top: Boolean, song: suspend (Song) -> Unit): List<DirectoryRef> {
    val key = if (top) "indexes" else "directory"
    val reader = JsonReader.of(source)
    var answered = false
    var status: String? = null
    var code = 0
    var message = "Request failed"
    var found = false
    val grouped = ArrayList<DirectoryRef>()
    val loose = ArrayList<DirectoryRef>()
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
                "status" -> status = textOf(reader)
                "error" -> if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "code" -> code = textOf(reader)?.toIntOrNull() ?: 0
                            "message" -> message = textOf(reader) ?: message
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                } else {
                    reader.skipValue()
                }
                key -> {
                    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                        reader.skipValue()
                        continue
                    }
                    found = true
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "index" -> if (top && reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    val group = treeOf(reader, json)
                                    if (failure != null) continue
                                    try {
                                        grouped += json.decodeFromJsonElement(IndexGroupWire.serializer(), group).artist
                                    } catch (e: IllegalArgumentException) {
                                        failure = e
                                    }
                                }
                                reader.endArray()
                            } else {
                                reader.skipValue()
                            }
                            "child" -> if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    val child = treeOf(reader, json) as? JsonObject ?: continue
                                    if (failure != null) continue
                                    val id = child["id"]?.jsonPrimitive?.contentOrNull ?: continue
                                    if (child["isDir"]?.jsonPrimitive?.booleanOrNull == true) {
                                        val name = child["title"]?.jsonPrimitive?.contentOrNull ?: child["name"]?.jsonPrimitive?.contentOrNull
                                        loose += DirectoryRef(
                                            id,
                                            name.orEmpty(),
                                            coverArt = child["coverArt"]?.jsonPrimitive?.contentOrNull,
                                            songCount = child["songCount"]?.jsonPrimitive?.intOrNull,
                                        )
                                    } else {
                                        val decoded = try {
                                            json.decodeFromJsonElement(Song.serializer(), child)
                                        } catch (e: IllegalArgumentException) {
                                            failure = e
                                            continue
                                        }
                                        song(decoded)
                                    }
                                }
                                reader.endArray()
                            } else {
                                reader.skipValue()
                            }
                            else -> reader.skipValue()
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
    if (!found && !top) throw SubsonicException.Server(0, "Missing \"$key\" in the answer")
    failure?.let { throw it }
    return grouped + loose
}

private fun treeOf(reader: JsonReader, json: Json): JsonElement = json.parseToJsonElement(reader.nextSource().use { it.readUtf8() })

private fun textOf(reader: JsonReader): String? = when (reader.peek()) {
    JsonReader.Token.STRING, JsonReader.Token.NUMBER -> reader.nextString()
    JsonReader.Token.NULL -> reader.nextNull<String>()
    else -> {
        reader.skipValue()
        null
    }
}
