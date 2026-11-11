package app.winters.octo.playback

import app.winters.octo.health.HealthCheck
import app.winters.octo.query.LibraryQuery
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.search.SearchFilter
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

// The most songs a list may name one by one, and the most a list plays
// whole at once.
const val QUEUE_WINDOW = 500

// A list to play, named by what it is rather than by its songs, with the
// order and filter the screen showed when it was tapped. Orders are kept
// in the words the sort settings save them in ("Title:asc").
@Serializable
sealed interface ListSpec {
    // Songs named by id, at most QUEUE_WINDOW of them, and the name the
    // queue heads them with.
    @Serializable
    @SerialName("ids")
    data class Ids(val ids: List<String>, val source: String? = null) : ListSpec

    // Every song, the liked ones or a genre's, in an order, through the
    // Songs list's filter when there is one.
    @Serializable(with = SongsSerializer::class)
    data class Songs(val scope: SongScope, val order: SortOrder, val filter: LibraryQuery? = null) : ListSpec

    // An album's songs, with the songs it is missing found online or not.
    @Serializable
    @SerialName("album")
    data class Album(val albumId: String, val withFinds: Boolean) : ListSpec

    // Albums one after another, at most QUEUE_WINDOW of them.
    @Serializable
    @SerialName("albumList")
    data class AlbumList(val albumIds: List<String>) : ListSpec

    // An artist's songs as their page lists them, or album by album.
    @Serializable
    @SerialName("artist")
    data class Artist(val artistId: String, val order: ArtistOrder) : ListSpec

    // Every album or every artist in an order, each one's songs in turn.
    @Serializable(with = GroupsSerializer::class)
    data class Groups(val kind: GroupKind, val order: SortOrder) : ListSpec

    @Serializable
    @SerialName("playlist")
    data class Playlist(val playlistId: String) : ListSpec

    // A live list, saved (with its id and name) or still being made.
    @Serializable
    @SerialName("live")
    data class Live(val listId: String?, val query: LibraryQuery, val name: String?) : ListSpec

    // A phone folder's songs: everything under it, or its own songs in an
    // order.
    @Serializable(with = FolderSerializer::class)
    data class Folder(val path: String, val recursive: Boolean, val order: SortOrder? = null) : ListSpec

    // A server folder's songs; null is the server's top: everything under
    // it as a Play gathers it, or its own songs alone in an order (the
    // server's when none), as its page lists them.
    @Serializable(with = ServerFolderSerializer::class)
    data class ServerFolder(val folderId: String?, val recursive: Boolean = true, val order: SortOrder? = null) : ListSpec

    // The finished downloads in an order.
    @Serializable(with = DownloadsSerializer::class)
    data class Downloads(val order: SortOrder) : ListSpec

    // An album, list or station the server answers for; with
    // `libraryOnly`, the library's songs in it alone.
    @Serializable
    @SerialName("online")
    data class Online(val kind: OnlineKind, val id: String, val libraryOnly: Boolean = false) : ListSpec

    // The rows picked by tapping on another list's page, by the keys that
    // page picks them by.
    @Serializable
    @SerialName("selection")
    data class Selection(val base: ListSpec, val ids: List<String>) : ListSpec

    // The songs of one Library health check in a published report.
    @Serializable
    @SerialName("health")
    data class Health(val gen: Long, val check: HealthCheck) : ListSpec

    // The queue saved on the server.
    @Serializable
    @SerialName("serverQueue")
    data object ServerQueue : ListSpec

    // Radio from one song: the song, then songs like it. An album's or an
    // artist's radio (`around`) takes its likeness from their first songs
    // too, and an album's leaves the album's own songs out.
    @Serializable
    @SerialName("radio")
    data class Radio(val seedId: String, val source: String? = null, val around: RadioAround? = null) : ListSpec

    // The songs a search found, as the search screen ran it.
    @Serializable
    @SerialName("search")
    data class Search(val query: String, val filter: SearchFilter) : ListSpec

    fun toJson(): String = ListJson.encodeToString(ListSpec.serializer(), this)

    companion object {
        fun fromJson(text: String): ListSpec = ListJson.decodeFromString(ListSpec.serializer(), text)
    }
}

enum class ArtistOrder { Page, Albums }

// What a radio started from a collection is started around.
@Serializable
sealed interface RadioAround {
    @Serializable
    @SerialName("album")
    data class Album(val albumId: String) : RadioAround

    @Serializable
    @SerialName("artist")
    data class Artist(val artistId: String) : RadioAround
}

enum class GroupKind { Albums, Artists }

enum class OnlineKind { Album, List, Station }

// Where a play starts: a tapped row (with its key on the page, when the
// page has one), the first row holding a song, a place in the list (and
// how far into its song), or a song the page already knows comes first.
// No start means the list's first row.
@Serializable
sealed interface ListStart {
    @Serializable
    @SerialName("row")
    data class Row(val trackId: String, val key: RowKey?) : ListStart

    @Serializable
    @SerialName("track")
    data class Track(val trackId: String) : ListStart

    @Serializable
    @SerialName("at")
    data class At(val index: Int, val positionMs: Long) : ListStart

    @Serializable
    @SerialName("first")
    data class First(val trackId: String, val key: RowKey?) : ListStart

    fun toJson(): String = ListJson.encodeToString(ListStart.serializer(), this)

    companion object {
        fun fromJson(text: String): ListStart = ListJson.decodeFromString(ListStart.serializer(), text)
    }
}

internal val ListJson = Json { ignoreUnknownKeys = true }

// An order in the sort settings' words, checked to be one this list offers,
// so it reads back as the same order.
private fun SortList.words(order: SortOrder): String {
    val words = encode(order)
    if (decode(words) != order) throw SerializationException("the $key list does not offer $words")
    return words
}

private val SongScope.list: SortList
    get() = when (this) {
        SongScope.All -> SortList.Songs
        SongScope.Liked -> SortList.Liked
        is SongScope.Genre -> SortList.GenreSongs
    }

private val GroupKind.list: SortList
    get() = when (this) {
        GroupKind.Albums -> SortList.Albums
        GroupKind.Artists -> SortList.Artists
    }

// A case written through a plain form that holds its order as words.
@OptIn(ExperimentalSerializationApi::class)
internal abstract class WordsSerializer<T, W>(
    name: String,
    private val form: KSerializer<W>,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = SerialDescriptor(name, form.descriptor)

    abstract fun toWire(value: T): W

    abstract fun fromWire(wire: W): T

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeSerializableValue(form, toWire(value))

    override fun deserialize(decoder: Decoder): T = fromWire(decoder.decodeSerializableValue(form))
}

@Serializable
internal class SongsWire(val scope: String, val genre: String? = null, val order: String, val filter: LibraryQuery? = null)

internal object SongsSerializer : WordsSerializer<ListSpec.Songs, SongsWire>("songs", SongsWire.serializer()) {
    override fun toWire(value: ListSpec.Songs) = SongsWire(
        scope = when (value.scope) {
            SongScope.All -> "all"
            SongScope.Liked -> "liked"
            is SongScope.Genre -> "genre"
        },
        genre = (value.scope as? SongScope.Genre)?.name,
        order = value.scope.list.words(value.order),
        filter = value.filter,
    )

    override fun fromWire(wire: SongsWire): ListSpec.Songs {
        val scope = when (wire.scope) {
            "all" -> SongScope.All
            "liked" -> SongScope.Liked
            "genre" -> SongScope.Genre(wire.genre ?: throw SerializationException("a genre's songs need the genre"))
            else -> throw SerializationException("unknown songs scope ${wire.scope}")
        }
        return ListSpec.Songs(scope, scope.list.decode(wire.order), wire.filter)
    }
}

@Serializable
internal class GroupsWire(val kind: GroupKind, val order: String)

internal object GroupsSerializer : WordsSerializer<ListSpec.Groups, GroupsWire>("groups", GroupsWire.serializer()) {
    override fun toWire(value: ListSpec.Groups) = GroupsWire(value.kind, value.kind.list.words(value.order))

    override fun fromWire(wire: GroupsWire) = ListSpec.Groups(wire.kind, wire.kind.list.decode(wire.order))
}

@Serializable
internal class FolderWire(val path: String, val recursive: Boolean, val order: String? = null)

internal object FolderSerializer : WordsSerializer<ListSpec.Folder, FolderWire>("folder", FolderWire.serializer()) {
    override fun toWire(value: ListSpec.Folder) = FolderWire(value.path, value.recursive, value.order?.let(SortList.FolderSongs::words))

    override fun fromWire(wire: FolderWire) = ListSpec.Folder(wire.path, wire.recursive, wire.order?.let(SortList.FolderSongs::decode))
}

@Serializable
internal class ServerFolderWire(val folderId: String?, val recursive: Boolean = true, val order: String? = null)

internal object ServerFolderSerializer : WordsSerializer<ListSpec.ServerFolder, ServerFolderWire>("serverFolder", ServerFolderWire.serializer()) {
    override fun toWire(value: ListSpec.ServerFolder) = ServerFolderWire(value.folderId, value.recursive, value.order?.let(SortList.FolderSongs::words))

    override fun fromWire(wire: ServerFolderWire) = ListSpec.ServerFolder(wire.folderId, wire.recursive, wire.order?.let(SortList.FolderSongs::decode))
}

@Serializable
internal class DownloadsWire(val order: String)

internal object DownloadsSerializer : WordsSerializer<ListSpec.Downloads, DownloadsWire>("downloads", DownloadsWire.serializer()) {
    override fun toWire(value: ListSpec.Downloads) = DownloadsWire(SortList.Downloads.words(value.order))

    override fun fromWire(wire: DownloadsWire) = ListSpec.Downloads(SortList.Downloads.decode(wire.order))
}
