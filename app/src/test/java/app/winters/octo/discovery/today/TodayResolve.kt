package app.winters.octo.discovery.today

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.discovery.TitleIndex
import app.winters.octo.discovery.knownLengthMs
import app.winters.octo.discovery.sameSong
import app.winters.octo.discovery.titleKeys
import app.winters.octo.discovery.toFind
import app.winters.octo.discovery.asTrack
import app.winters.octo.subsonic.Song

// A labeled pin: discovery/Discovery.kt's resolve over a whole decoded
// answer (resolveEach, resolveSongs and asAdopted), as at c2208eaa, frozen
// as the measure a server list's paged read must match. The finds are kept
// as stored (`kept`, a find's row by id with what the app knew of it) and
// the library read whole. Never edited to follow the app.

private sealed interface Resolved {
    data class InLibrary(val trackId: String) : Resolved

    data class Found(val song: OnlineSongEntity) : Resolved
}

fun todayResolve(
    db: CatalogDatabase,
    sourceId: String,
    songs: List<Song>,
    kept: (OnlineSongEntity) -> OnlineSongEntity = { it },
): List<TrackEntity> = kotlinx.coroutines.runBlocking {
    if (songs.isEmpty()) return@runBlocking emptyList()
    val links = songs.map { it.id }.distinct().chunked(900)
        .flatMap { db.sourceDao().libraryLinks(sourceId, it) }
        .associate { it.serverId to it.trackId }
    val unlinked = songs.filter { it.id !in links }
    val candidates = unlinked.flatMap { titleKeys(it.title) }.distinct().sorted().chunked(900).flatMap { db.dao().tracksWithKeys(it) }
    val byTitle = TitleIndex(candidates, { it.title }, { it.artist })
    val each = songs.map { song ->
        val link = if (!song.isExternal) links[song.id] else null
        if (link != null) {
            Resolved.InLibrary(link)
        } else {
            val length = knownLengthMs(song)
            val same = byTitle.candidates(song.title, song.artist.orEmpty()).firstOrNull { sameSong(song.title, song.artist.orEmpty(), length, it) }
            if (same != null) Resolved.InLibrary(same.id) else Resolved.Found(kept(song.toFind(sourceId, 0)))
        }
    }
    val adopted = each.filterIsInstance<Resolved.Found>().mapNotNull { it.song.adoptedId.ifEmpty { null } }
    val library = (each.filterIsInstance<Resolved.InLibrary>().map { it.trackId } + adopted).distinct().chunked(900)
        .flatMap { db.dao().tracksByIdsUnordered(it) }.associateBy { it.id }
    each.map { r ->
        when {
            r is Resolved.Found && r.song.adoptedId in library.keys -> library[r.song.adoptedId]
            r is Resolved.InLibrary -> library[r.trackId]
            r is Resolved.Found -> r.song.asTrack()
            else -> null
        }
    }.filterNotNull().distinctBy { it.id }
}
