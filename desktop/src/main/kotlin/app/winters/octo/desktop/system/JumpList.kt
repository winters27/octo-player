package app.winters.octo.desktop.system

import app.winters.octo.desktop.nav.Page
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

// The jump list on Windows: the menu on Octo's taskbar button and Start
// entry. It holds albums pinned from their menu, then what was played
// lately (albums, playlists and live lists), each a link that plays it:
//
//   octo://play/album/<id>, octo://play/playlist/<id>, octo://play/livelist/<id>
//
// A click starts Octo with the link. A running Octo is handed it (see
// SingleInstance) and plays it; otherwise Octo opens and plays it.

enum class JumpKind(val word: String) { Album("album"), Playlist("playlist"), LiveList("livelist") }

// Something the jump list can play: an album with its artist, a playlist,
// or a live list.
@Serializable
data class JumpTarget(val kind: JumpKind, val id: String, val name: String, val artist: String? = null) {
    val link: String get() = "octo://play/${kind.word}/" + URLEncoder.encode(id, Charsets.UTF_8)

    // What the pointer shows over the entry.
    val tip: String get() = when (kind) {
        JumpKind.Album -> if (artist.isNullOrBlank()) "Play $name" else "Play $name by $artist"
        JumpKind.Playlist -> "Play the playlist $name"
        JumpKind.LiveList -> "Play the live list $name"
    }

    fun sameAs(other: JumpTarget) = kind == other.kind && id == other.id
}

// What a play link asks for: which kind and which id, or null for any
// other link.
fun playLinkOf(link: String): Pair<JumpKind, String>? {
    val rest = link.trim().substringAfter("://", "").trim('/')
    val parts = rest.split('/')
    if (parts.size != 3 || !parts[0].equals("play", ignoreCase = true)) return null
    val kind = JumpKind.entries.firstOrNull { it.word.equals(parts[1], ignoreCase = true) } ?: return null
    val id = runCatching { URLDecoder.decode(parts[2].substringBefore('?'), Charsets.UTF_8) }.getOrNull()?.trim()
    return if (id.isNullOrEmpty()) null else kind to id
}

// The list played from a page, when it is one the jump list can play
// again: a playlist's or live list's page, or songs all from one album
// (the album's page, or several songs of it played from anywhere).
fun jumpTargetFor(page: Page, songs: List<Song>, listName: (String) -> String?): JumpTarget? {
    when (page) {
        is Page.Playlist -> return listName(page.id)?.let { JumpTarget(JumpKind.Playlist, page.id, it) }
        is Page.LiveList -> return listName(page.id)?.let { JumpTarget(JumpKind.LiveList, page.id, it) }
        else -> Unit
    }
    val first = songs.firstOrNull() ?: return null
    val album = first.albumId?.takeIf(String::isNotBlank) ?: return null
    if (songs.any { it.albumId != album }) return null
    if (songs.size < 2 && page != Page.Album(album)) return null
    val name = first.album?.takeIf(String::isNotBlank) ?: return null
    val artist = (first.displayAlbumArtist ?: first.albumArtists.joinToString(", ") { it.name }.ifBlank { null } ?: first.displayArtist ?: first.artist)
    return JumpTarget(JumpKind.Album, album, name, artist?.takeIf(String::isNotBlank))
}

// The jump list's own record: what was pinned, in pin order, and what was
// played, the latest first.
@Serializable
data class JumpEntries(
    val version: Int = 1,
    val pinned: List<JumpTarget> = emptyList(),
    val recent: List<JumpTarget> = emptyList(),
) {
    // Played now: to the front of the recent ones, and its name brought up
    // to date where it is pinned.
    fun played(target: JumpTarget): JumpEntries = copy(
        recent = (listOf(target) + recent.filterNot { it.sameAs(target) }).take(RECENT_KEPT),
        pinned = pinned.map { if (it.sameAs(target)) target else it },
    )

    fun isPinned(target: JumpTarget) = pinned.any { it.sameAs(target) }

    fun pinning(target: JumpTarget, pin: Boolean): JumpEntries = copy(
        pinned = if (pin) (pinned.filterNot { it.sameAs(target) } + target).takeLast(PINNED_KEPT) else pinned.filterNot { it.sameAs(target) },
    )

    // The listener took these out of the jump list itself, by their links:
    // they leave the record too (a pinned one is unpinned).
    fun without(links: Collection<String>): JumpEntries =
        if (links.isEmpty()) this else copy(pinned = pinned.filterNot { it.link in links }, recent = recent.filterNot { it.link in links })

    // The entries in the order the jump list shows them: the pinned ones,
    // then the recent ones not pinned, a few at most.
    fun items(): List<JumpItem> =
        pinned.map { JumpItem(PINNED_HEADING, it) } +
            recent.filterNot(::isPinned).take(RECENT_SHOWN).map { JumpItem(RECENT_HEADING, it) }

    companion object {
        const val RECENT_KEPT = 12
        const val RECENT_SHOWN = 8
        const val PINNED_KEPT = 12
        const val PINNED_HEADING = "Pinned albums"
        const val RECENT_HEADING = "Recently played"
    }
}

data class JumpItem(val heading: String, val target: JumpTarget)

// The entries as the system library takes them: one a line, the fields
// split by tabs (heading, name, command line, tip, icon file, icon
// number). An empty icon file means the program's own.
fun jumpListText(items: List<JumpItem>): String = items.joinToString("\n") { item ->
    listOf(item.heading, item.target.name, item.target.link, item.target.tip, "", "0").joinToString("\t") { it.oneLine() }
}

private fun String.oneLine() = replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim()

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun encodeJumpEntries(entries: JumpEntries): String = json.encodeToString(JumpEntries.serializer(), entries)

// The record from its file's text; anything unreadable starts empty.
fun decodeJumpEntries(text: String): JumpEntries = runCatching { json.decodeFromString(JumpEntries.serializer(), text) }.getOrDefault(JumpEntries())

// The signed-in account's record, kept in its folder beside its plays as
// jump-list.json (album ids belong to one server). Written a moment after
// each change, one write at a time. With no file it lives only while Octo
// runs.
@OptIn(ExperimentalCoroutinesApi::class)
class JumpListStore(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private val _entries = MutableStateFlow(JumpEntries())
    val entries: StateFlow<JumpEntries> = _entries

    @Volatile private var file: File? = null

    // A file that cannot be read now is left alone rather than saved over.
    fun open(file: File?) {
        val read = file?.let(::readForSaving) ?: Result.success(null)
        this.file = file.takeIf { read.isSuccess }
        _entries.value = read.getOrNull()?.let(::decodeJumpEntries) ?: JumpEntries()
    }

    fun played(target: JumpTarget) = change { it.played(target) }

    fun setPinned(target: JumpTarget, pin: Boolean) = change { it.pinning(target, pin) }

    fun forget(links: Collection<String>) {
        if (links.isNotEmpty()) change { it.without(links) }
    }

    // Writes the record now, on the caller's thread, for quitting.
    fun saveNow() {
        runCatching { write() }
    }

    private fun change(edit: (JumpEntries) -> JumpEntries) {
        val before = _entries.value
        val after = edit(before)
        if (after == before) return
        _entries.value = after
        if (file != null) scope.launch(io) { runCatching { write() } }
    }

    @Synchronized
    private fun write() {
        val target = file ?: return
        saveWhole(target, encodeJumpEntries(_entries.value))
    }

    companion object {
        const val FILE_NAME = "jump-list.json"
    }
}
