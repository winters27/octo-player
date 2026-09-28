package app.winters.octo.livelists

import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.SongFields
import app.winters.octo.query.select
import app.winters.octo.ui.playlist.playlistCopyName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.util.UUID

// A list of songs picked by rules rather than by hand: "Added in the last
// month, lossless". It is worked out from the library each time it is
// shown, so it keeps up as songs are added, played and liked. Kept on the
// device, per account; both apps keep the same shape, so a later sync or a
// copy on the server can reuse it.
data class LiveList(
    val id: String,
    val name: String,
    val query: LibraryQuery = LibraryQuery(),
    // Milliseconds since 1970.
    val created: Long = 0,
    val changed: Long = 0,
) {
    // The library's songs this list picks, in its order, at most its limit.
    fun <T> songsOf(library: List<T>, now: Long, fields: SongFields<T>): List<T> = query.select(library, now, fields)

    companion object {
        fun new(name: String, query: LibraryQuery, now: Long, id: String = UUID.randomUUID().toString()) =
            LiveList(id, name.trim(), query, now, now)
    }
}

// The lists as a file or a stored value keeps them: `{"version":1,"lists":[...]}`.
object LiveListsJson {
    private const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(lists: List<LiveList>): String {
        val items = lists.map { list ->
            JsonObject(
                buildMap {
                    put("id", JsonPrimitive(list.id))
                    put("name", JsonPrimitive(list.name))
                    put("query", json.parseToJsonElement(list.query.toJson()))
                    if (list.created != 0L) put("created", JsonPrimitive(list.created))
                    if (list.changed != 0L) put("changed", JsonPrimitive(list.changed))
                },
            )
        }
        return JsonObject(mapOf("version" to JsonPrimitive(VERSION), "lists" to JsonArray(items))).toString()
    }

    // The lists in the text, or none when it is not a lists file. A list
    // that cannot be read is left out rather than losing the others, and a
    // rule this version does not know is dropped from its list only.
    fun decode(text: String?): List<LiveList> {
        if (text.isNullOrBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val items = root["lists"] as? JsonArray ?: return emptyList()
        return items.mapNotNull(::readList).distinctBy(LiveList::id)
    }

    private fun readList(element: JsonElement): LiveList? {
        val item = element as? JsonObject ?: return null
        val id = (item["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        val name = (item["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val query = item["query"]?.let { LibraryQuery.fromJson(it.toString()) } ?: LibraryQuery()
        return LiveList(id, name, query, (item["created"] as? JsonPrimitive)?.longOrNull ?: 0, (item["changed"] as? JsonPrimitive)?.longOrNull ?: 0)
    }
}

// The lists with `list` saved: in its place when it is there, else at the end.
fun List<LiveList>.saving(list: LiveList): List<LiveList> =
    if (any { it.id == list.id }) map { if (it.id == list.id) list else it } else this + list

fun List<LiveList>.removing(id: String): List<LiveList> = filterNot { it.id == id }

// A copy of `list` named "<name> (copy)", placed right after it.
fun List<LiveList>.duplicating(list: LiveList, now: Long, id: String = UUID.randomUUID().toString()): Pair<List<LiveList>, LiveList> {
    val copy = LiveList.new(playlistCopyName(list.name), list.query, now, id)
    val at = indexOfFirst { it.id == list.id }
    val lists = if (at < 0) this + copy else take(at + 1) + copy + drop(at + 1)
    return lists to copy
}

// Which account's lists these are: the first 8 bytes of SHA-256 of
// "username@address", in hex, the same name the desktop gives the folder of
// an account's plays and queue.
fun accountKey(username: String, address: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("$username@$address".toByteArray())
    return digest.take(8).joinToString("") { "%02x".format(it) }
}
