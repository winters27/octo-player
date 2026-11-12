package app.winters.octo.playlists

import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.MatchGroup
import app.winters.octo.catalog.PLAYLIST_ROW_BYTES
import app.winters.octo.catalog.PlaylistEntryEntity
import app.winters.octo.catalog.PoolTrack
import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.SongIdentity.ParsedSong
import app.winters.octo.data.Drains
import app.winters.octo.device.explicitOf
import app.winters.octo.discovery.knownLengthMs
import app.winters.octo.discovery.SongLengths
import app.winters.octo.discovery.mimeTypeOf
import app.winters.octo.discovery.parsedSong
import app.winters.octo.discovery.sameSong
import app.winters.octo.discovery.sameSongLengths
import app.winters.octo.discovery.titleKeys
import app.winters.octo.subsonic.PlaylistWithSongs
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.findId

// The disk one entry of a server playlist takes in the work tables while a
// step runs, measured on the generator (PlaylistDiskConstantsTest).
internal const val PLAYLIST_STEP_BYTES = 700L

// The disk one entry takes in a drained answer, measured the same way.
internal const val PLAYLIST_JSON_BYTES = 1_300L

// How many pool songs one page of the match walk reads.
private const val POOL_PAGE = 50

// How many open groups one lookup reads at a time.
private const val GROUP_PAGE = 200

// About how many rows one transaction of the match walk writes: it ends
// after the group whose rows reach this many, and the next one goes on
// after it.
private const val POOL_WRITES = 500

// How many open groups the match walk keeps parsed.
private const val GROUP_FORMS = 2_048

// The most rows the links step writes for one entry: its own, a match
// group, two lookup keys and three pool keys, and one to spare.
private const val ENTRY_WRITES = 8

// The phone ran short of room for a playlist step: the step stops, counted
// as failed, and the playlist is left as it was.
internal class PlaylistNoRoom(val neededBytes: Long) : Exception("needs $neededBytes bytes free")

// Points in a read where a test can step in.
internal open class ReadHooks {
    // A page of a read's entries was written.
    open suspend fun streamed(read: Long, pages: Int) {}

    // A page of the pool was matched.
    open suspend fun pooled(read: Long, pages: Int) {}

    // The pool walk began; resolutions are counted by it.
    open fun resolving(read: Long) {}

    // The match walk parsed `songs` pool songs (a page it just read) or
    // `groups` open groups (met when none of their kept forms held them).
    open fun parsed(read: Long, songs: Int, groups: Int) {}

    // A page of the match walk compared `pairs` of a pool song and an open
    // group.
    open fun compared(read: Long, pairs: Int) {}
}

// Reads server playlists into the work tables and works out what each entry
// is to the phone, never holding a playlist whole.
//
// A read drains the answer to a file first (Drains), then writes its
// entries into playlist_entry a page at a time, each with the compact
// columns today's resolution and finds take from the decoded song.
//
// Resolution then does what idsFromServer does, over the whole read at
// once so a song can match through another entry's keys, as the one pool
// does today: an entry the library has from the server (and the server
// does not mark as outside it) is that song; any other is matched by title
// against every library song filed under a key some unlinked entry looks
// up, walked in (key, id) order, each match group taking the first song in
// that order that is the same song; the rest are finds, each kept once in
// the order it first comes. It runs once per read, outside every lock: a
// swap between its transactions moves the ids it already wrote with every
// other playlist row (the relinks cover these tables), and pool pages after
// the swap see the new library.
internal class PlaylistReads(
    private val db: CatalogDatabase,
    private val drains: Drains,
    private val freeSpace: FreeSpace,
    // The signed-in server's source, read once per resolution; null when
    // none is signed in.
    private val serverInUse: () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    private val page: Int = READ_PAGE,
) {
    private val work = db.playlistWorkDao()
    private val sources = db.sourceDao()
    private val online = db.onlineDao()

    internal var hooks = ReadHooks()

    // Reads the server playlist `serverId` into read `read`, entry by entry
    // in its order, and answers it without its entries. `songCount`, when
    // the list of playlists said it, sizes the room asked for first;
    // otherwise room is asked for as the answer and the rows grow. An id
    // holding a line break is counted in `odd`.
    suspend fun read(client: SubsonicClient, serverId: String, read: Long, songCount: Int? = null): Read {
        songCount?.let { need(it.toLong()) }
        var position = 0L
        var pages = 0
        var odd = 0
        val head = drains.use("plsync-$read.json") { file ->
            client.playlistInto(
                serverId,
                file,
                room = { written -> if (songCount == null) need(written / PLAYLIST_JSON_BYTES + 1) },
                pageSize = page,
            ) { songs ->
                db.withTransaction {
                    work.insertEntries(songs.mapIndexed { i, song -> entryOf(read, position + i, song) })
                }
                odd += songs.count { '\n' in it.id }
                position += songs.size
                pages++
                if (songCount == null && pages % 10 == 0) need(position)
                hooks.streamed(read, pages)
            }
        }
        return Read(read, head, position, odd)
    }

    // The room `entries` entries need for a whole step: the drained answer,
    // the work tables and the new rows beside the old until the old go.
    private fun need(entries: Long) {
        val need = entries * (PLAYLIST_JSON_BYTES + PLAYLIST_STEP_BYTES * 2 + PLAYLIST_ROW_BYTES)
        if (freeSpace.bytes() < need) throw PlaylistNoRoom(need)
    }

    private fun entryOf(read: Long, position: Long, song: Song) = PlaylistEntryEntity(
        read = read,
        position = position,
        songId = song.id,
        external = song.isExternal,
        title = song.title,
        artist = song.artist.orEmpty(),
        lengthMs = knownLengthMs(song),
        album = song.album.orEmpty(),
        albumId = song.albumId?.takeIf(String::isNotEmpty),
        artistId = song.artistId?.takeIf(String::isNotEmpty),
        coverId = song.coverArt?.takeIf(String::isNotEmpty),
        mimeType = mimeTypeOf(song),
        bitrate = song.bitRate?.takeIf { it > 0 },
        explicit = explicitOf(song.explicitStatus.orEmpty()),
        trackId = null,
        groupId = null,
    )

    // Works out what each entry of a read is. Answers false, writing
    // nothing, when no server is signed in and the read has entries (as
    // idsFromServer answers nothing then).
    suspend fun resolve(read: Long): Boolean {
        val sourceId = serverInUse()
        if (sourceId == null) return !work.hasEntries(read)
        hooks.resolving(read)
        val at = now()
        val open = links(read, sourceId)
        if (open > 0) pool(read, open)
        settle(read)
        finds(read, sourceId, at)
        return true
    }

    // Step one, a page of entries at a time: an entry the library has from
    // the server is that song; any other joins the match group of its
    // title, artist and length, made with its lookup keys when new; an
    // entry with no link adds its title's keys to the pool. An entry writes
    // up to eight rows, so an eighth of a page goes to a transaction.
    // Answers how many groups are open.
    private suspend fun links(read: Long, sourceId: String): Long {
        var open = 0L
        var from = 0L
        while (true) {
            val rows = work.entryHeads(read, from, page)
            if (rows.isEmpty()) return open
            // The links are read in the transaction that writes them, so a
            // swap lands before or after both, and its relinks follow them.
            for (part in rows.chunked((page / ENTRY_WRITES).coerceAtLeast(1))) db.withTransaction {
                val links = sources.libraryLinks(sourceId, part.map { it.songId }.distinct()).associate { it.serverId to it.trackId }
                val keys = work.trackKeys(links.values.distinct()).associate { it.id to it.relinkKey }
                for (row in part) {
                    val link = links[row.songId]
                    if (!row.external && link != null) {
                        work.setLinked(read, row.position, link, keys[link].orEmpty())
                    } else {
                        val group = work.matchGroup(read, row.title, row.artist, row.lengthMs) ?: run {
                            work.insertMatch(read, row.title, row.artist, row.lengthMs)
                            val made = work.matchGroup(read, row.title, row.artist, row.lengthMs)!!
                            SongIdentity.titleLookupKeys(row.title, row.artist).forEach { work.insertLookup(read, it, made, row.lengthMs) }
                            open++
                            made
                        }
                        work.setWaiting(read, row.position, group)
                    }
                    if (link == null) titleKeys(row.title).forEach { work.insertKey(read, it) }
                }
            }
            if (rows.size < page) return open
            from = rows.last().position + 1
        }
    }

    // Step two: the pool in (key, id) order; each song is offered to every
    // open group sharing a lookup key with it, and a group takes the first
    // that is the same song. A page of the pool is read and compared outside
    // every transaction, each song parsed once for all its groups and the
    // groups met last kept parsed; then its matches are written in one
    // transaction, or in several when the rows reach POOL_WRITES, each going
    // on from the match the last one ended at. Each transaction checks a
    // song is still in the library before it writes the song's matches, and
    // a group goes to the first song still there that matched it. Stops
    // once no group is open.
    private suspend fun pool(read: Long, groups: Long) {
        val walk = PoolWalk(read, groups)
        var lastKey = ""
        var lastId = ""
        var pages = 0
        while (walk.open > 0) {
            val tracks = work.poolPage(read, lastKey, lastId, POOL_PAGE)
            if (tracks.isEmpty()) return
            val songs = tracks.map(::PoolSong)
            hooks.parsed(read, songs.size, 0)
            walk.compare(songs)
            var from: PoolPlace? = PoolPlace(0, 0)
            while (from != null) {
                val at = from
                from = db.withTransaction { walk.place(songs, at) }
            }
            hooks.pooled(read, ++pages)
            if (tracks.size < POOL_PAGE) return
            lastKey = tracks.last().poolKey
            lastId = tracks.last().id
        }
    }

    // One read's match walk: how many groups are still open, and the open
    // groups it met last, parsed, the one met longest ago let go first.
    private inner class PoolWalk(private val read: Long, var open: Long) {
        private val forms = object : LinkedHashMap<Long, GroupForm>(GROUP_FORMS * 4 / 3 + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, GroupForm>): Boolean = size > GROUP_FORMS
        }

        private fun formOf(group: MatchGroup): GroupForm = forms.getOrPut(group.id) {
            hooks.parsed(read, 0, 1)
            GroupForm(parsedSong(group.title, group.artist, group.lengthMs))
        }

        // Each song's matches: the groups open now under its lookup keys
        // that are the same song, in the order they are met. A group an
        // earlier song of the page matched is still open here, so a later
        // one keeps it in case the earlier song is gone when they are written.
        // A song meets only the groups whose lengths sameSong can still
        // accept beside it (sameSongLengths of its parsed form: any, when it
        // carries an ISRC), read by length.
        suspend fun compare(songs: List<PoolSong>) {
            var pairs = 0
            for (song in songs) {
                val near = lengthsNear(sameSongLengths(song.parsed))
                for (key in song.keys) {
                    for (lengths in near) {
                        var afterLength = Long.MIN_VALUE
                        var after = Long.MIN_VALUE
                        while (true) {
                            val found = work.openGroupsNear(read, key, lengths.first, lengths.last, afterLength, after, GROUP_PAGE)
                            for (group in found) {
                                if (group.id in song.matched) continue
                                val form = formOf(group)
                                pairs++
                                if (sameSong(form.parsed, song.parsed)) {
                                    song.matched += group.id
                                    song.matches += PoolMatch(group.id, form.keys)
                                }
                            }
                            if (found.size < GROUP_PAGE) break
                            afterLength = found.last().lengthMs
                            after = found.last().id
                        }
                    }
                }
            }
            hooks.compared(read, pairs)
        }

        // Writes the page's matches from `from` on, in the caller's
        // transaction, until the page ends or the rows written reach
        // POOL_WRITES. A group another song took first stays with it.
        // Answers where the next transaction goes on, or null at the
        // page's end.
        suspend fun place(songs: List<PoolSong>, from: PoolPlace): PoolPlace? {
            var written = 0
            for (s in from.song until songs.size) {
                if (open == 0L) return null
                val song = songs[s]
                // A swap since the page was read, or since the transaction
                // before this one, may have renamed the song; it is offered
                // again under its new id if the walk still comes to it.
                if (!work.trackExists(song.track.id)) continue
                for (m in (if (s == from.song) from.match else 0) until song.matches.size) {
                    val match = song.matches[m]
                    if (work.placeGroup(match.id, song.track.id, song.track.relinkKey) != 1) continue
                    match.keys.forEach { work.deleteLookup(read, it, match.id) }
                    forms.remove(match.id)
                    open--
                    written += 1 + match.keys.size
                    if (written >= POOL_WRITES) return PoolPlace(s, m + 1)
                }
            }
            return null
        }
    }

    // Step three: each waiting entry takes its group's song, or is a find.
    private suspend fun settle(read: Long) {
        var from = 0L
        while (true) {
            val rows = work.entryHeads(read, from, page)
            if (rows.isEmpty()) return
            val to = rows.last().position + 1
            db.withTransaction { work.settle(read, from, to) }
            if (rows.size < page) return
            from = to
        }
    }

    // Step four: each find is kept once, from its first entry, in the order
    // first entries come.
    private suspend fun finds(read: Long, sourceId: String, at: Long) {
        var afterSong = ""
        var afterPosition = -1L
        var previous: String? = null
        while (true) {
            val rows = work.findsBySong(read, afterSong, afterPosition, page)
            if (rows.isEmpty()) break
            val first = rows.filter { row -> (row.songId != previous).also { previous = row.songId } }.map { it.position }
            if (first.isNotEmpty()) db.withTransaction { work.markFirstFinds(read, first) }
            if (rows.size < page) break
            afterSong = rows.last().songId
            afterPosition = rows.last().position
        }
        var after = -1L
        while (true) {
            val rows = work.firstFinds(read, after, page)
            if (rows.isEmpty()) return
            online.keep(rows.map { it.toFind(sourceId, at) })
            if (rows.size < page) return
            after = rows.last().position
        }
    }

    private fun PlaylistEntryEntity.toFind(sourceId: String, at: Long) = OnlineSongEntity(
        id = findId(songId),
        sourceId = sourceId,
        nativeId = songId,
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        artistId = artistId,
        durationMs = lengthMs,
        coverId = coverId,
        mimeType = mimeType,
        bitrate = bitrate,
        seenAt = at,
        explicit = explicit,
    )
}

// Where the writing of a page's matches goes on: the page's song, and its
// match.
private class PoolPlace(val song: Int, val match: Int)

// The ranges of group lengths `lengths` holds, each read on its own: no
// length (0 or less), then the known lengths it names; every length in one
// range when it holds any.
private fun lengthsNear(lengths: SongLengths): List<LongRange> {
    val near = lengths.near ?: return listOf(Long.MIN_VALUE..Long.MAX_VALUE)
    return listOf(Long.MIN_VALUE..0L, maxOf(1L, near.first)..near.last)
}

// A pool song, parsed once for all the groups it is offered to, and the
// open groups it matched.
private class PoolSong(val track: PoolTrack) {
    val parsed: ParsedSong = parsedSong(track.title, track.artist, track.durationMs)
    val keys: Set<String> = SongIdentity.titleLookupKeys(parsed)
    val matches = ArrayList<PoolMatch>()
    val matched = HashSet<Long>()
}

// An open group a pool song matched, with the lookup keys it is filed under.
private class PoolMatch(val id: Long, val keys: Set<String>)

// An open group parsed, with the lookup keys it is filed under.
private class GroupForm(val parsed: ParsedSong) {
    val keys: Set<String> = SongIdentity.titleLookupKeys(parsed)
}

// How many entries one page of a read holds.
internal const val READ_PAGE = 500

// One read of a server playlist: its number, the playlist without its
// entries, how many entries it has, and how many of their ids hold a line
// break.
internal class Read(val number: Long, val head: PlaylistWithSongs, val size: Long, val odd: Int)
