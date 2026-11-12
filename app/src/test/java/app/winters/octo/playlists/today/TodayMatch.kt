package app.winters.octo.playlists.today

import app.winters.octo.backup.SongKey
import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.matchKey
import app.winters.octo.discovery.TitleIndex
import app.winters.octo.discovery.sameArtist
import app.winters.octo.discovery.sameSong
import app.winters.octo.discovery.titleKeys
import app.winters.octo.discovery.versionOf
import app.winters.octo.playlists.M3uEntry
import app.winters.octo.playlists.M3uMatch
import app.winters.octo.playlists.M3uSong
import app.winters.octo.playlists.baseName
import app.winters.octo.playlists.pathParts
import kotlin.math.abs

// A labeled pin: backup/Backup.kt's SongFinder and playlists/M3uText.kt's
// matchM3u with its helpers, as they were at c2208eaa, over the whole
// library in library order, frozen as the measure the matching walk must
// match. Never edited to follow the app.

class SongFinder(library: List<TrackEntity>) {
    private val byRelink = library.filter { it.relinkKey.isNotEmpty() }.groupBy { it.relinkKey }
    private val byWhole = library.groupBy { wholeKey(it.title, it.artist, it.album) }
    private val byTitle = TitleIndex(library, { it.title }, { it.artist })

    fun find(song: SongKey): String? {
        if (song.relinkKey.isNotEmpty()) byRelink[song.relinkKey]?.firstOrNull()?.let { return it.id }
        if (song.title.isBlank()) return null
        byWhole[wholeKey(song.title, song.artist, song.album)]?.let { same -> return closest(same, song.durationMs).id }
        if (song.artist.isBlank()) return null
        val candidates = byTitle.candidates(song.title, song.artist).filter { sameSong(song.title, song.artist, song.durationMs, it) }
        return candidates.takeIf { it.isNotEmpty() }?.let { closest(it, song.durationMs).id }
    }

    private fun wholeKey(title: String, artist: String, album: String) =
        "${SongIdentity.songKeys(artist, title).title}|${matchKey(artist)}|${matchKey(album)}"

    private fun closest(tracks: List<TrackEntity>, durationMs: Long): TrackEntity =
        if (durationMs <= 0) tracks.first() else tracks.minBy { abs(it.durationMs - durationMs) }
}

private const val SAME_LENGTH_MS = 10_000L

private val TrackNumber = Regex("""^\d{1,3}\s*[-.)_]?\s+""")

fun matchM3u(entries: List<M3uEntry>, library: List<M3uSong>, paths: Map<String, String>): M3uMatch {
    val byId = library.associateBy { it.id }
    val byFileName = LinkedHashMap<String, MutableList<Pair<String, List<String>>>>()
    paths.forEach { (id, path) ->
        if (id !in byId) return@forEach
        val parts = pathParts(path).map { it.lowercase() }
        val name = parts.lastOrNull() ?: return@forEach
        byFileName.getOrPut(name) { mutableListOf() } += id to parts
    }
    val byTitle = HashMap<String, MutableList<M3uSong>>()
    library.forEach { track -> titleKeys(track.title).forEach { byTitle.getOrPut(it) { mutableListOf() } += track } }

    val ids = ArrayList<String>()
    val missed = ArrayList<M3uEntry>()
    for (entry in entries) {
        val found = byPath(entry, byFileName)
            ?: entry.title?.let { byTitleAndArtist(it, entry.artist, (entry.seconds ?: 0) * 1000L, byTitle) }
            ?: byFileNameAsTitle(entry, byTitle)
        if (found != null) ids += found else missed += entry
    }
    return M3uMatch(ids, missed)
}

private fun byPath(entry: M3uEntry, byFileName: Map<String, List<Pair<String, List<String>>>>): String? {
    val parts = pathParts(entry.location).map { it.lowercase() }
    val candidates = byFileName[parts.lastOrNull() ?: return null] ?: return null
    return candidates.maxBy { (_, known) -> sharedEnding(parts, known) }.first
}

private fun sharedEnding(a: List<String>, b: List<String>): Int {
    var n = 0
    while (n < a.size && n < b.size && a[a.size - 1 - n] == b[b.size - 1 - n]) n++
    return n
}

private fun byTitleAndArtist(title: String, artist: String?, lengthMs: Long, byTitle: Map<String, List<M3uSong>>): String? {
    val versions = versionOf(title)
    val candidates = titleKeys(title).flatMap { byTitle[it].orEmpty() }.distinctBy { it.id }
        .filter { versionOf(it.title) == versions }
        .filter { lengthMs <= 0 || it.durationMs <= 0 || abs(lengthMs - it.durationMs) <= SAME_LENGTH_MS }
    if (!artist.isNullOrBlank()) {
        return candidates.firstOrNull { sameArtist(artist, it.artist) && SongIdentity.sameTitle(title, it.title).isSame }?.id
            ?: candidates.firstOrNull { sameArtist(artist, it.artist) }?.id
    }
    return candidates.singleOrNull()?.id
}

private fun byFileNameAsTitle(entry: M3uEntry, byTitle: Map<String, List<M3uSong>>): String? {
    val name = baseName(entry.location).replace('_', ' ').replace(TrackNumber, "").trim()
    if (name.isEmpty()) return null
    val lengthMs = (entry.seconds ?: 0) * 1000L
    val split = name.indexOf(" - ")
    if (split > 0) {
        val artist = name.substring(0, split).trim()
        val title = name.substring(split + 3).trim()
        byTitleAndArtist(title, artist, lengthMs, byTitle)?.let { return it }
    }
    return byTitleAndArtist(name, entry.artist, lengthMs, byTitle)
}
