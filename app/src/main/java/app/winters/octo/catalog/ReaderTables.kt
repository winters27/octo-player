package app.winters.octo.catalog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// The work tables of the library's other readers: a live list's picks,
// Library health's report, a backup's or playlist file's songs matched
// against the library, and the steps that undo a health fix. Each table's
// key leads with its owner (a WorkOwners token) where one owns its rows,
// so a clear by owner reaches them all. Every open of the database empties
// them.

// One song a live list picks, in the list's order: `orderKey` sorts the
// way the list's sort and limit put its songs, then by library order.
// `gen` is the generation of the list it belongs to; readers read the one
// live_state names.
@Entity(
    tableName = "live_song",
    primaryKeys = ["owner", "gen", "orderKey"],
    indices = [Index("owner", "gen", "trackId", name = "index_live_song_track")],
)
class LiveSongEntity(val owner: String, val gen: Long, val orderKey: ByteArray, val trackId: String, val durationMs: Long)

// What one owner's live list holds: its published generation, the
// question that built it, how many songs and how long together, when it
// was last used (a counter, not a clock), and the library's swap count the
// generation's songs were read at.
@Entity(tableName = "live_state")
data class LiveStateEntity(
    @PrimaryKey val owner: String,
    val listId: String?,
    val gen: Long,
    val queryHash: String,
    val count: Int,
    val durationMs: Long,
    val usedAt: Long,
    val swapCount: Long,
)

// Library health's report: the generation readers read, whether one is
// being built, how many each check found, how many songs were looked at,
// and the library's swap count the report was built at.
@Entity(tableName = "health_run")
data class HealthRunEntity(
    @PrimaryKey val id: Int,
    val gen: Long,
    val building: Boolean,
    val duplicates: Int,
    val splitAlbums: Int,
    val noLength: Int,
    val noTrackNumber: Int,
    val noAlbumArtist: Int,
    val noCover: Int,
    val noYear: Int,
    val noGenre: Int,
    val checked: Int,
    val swapCount: Long,
)

// A song missing tags (`mask`, a bit per HealthTag), with the keys that
// put songs of many albums in order: artist, album, the album's id, disc,
// track, then title, each name as a key that sorts as the text does.
@Entity(
    tableName = "health_song",
    primaryKeys = ["owner", "gen", "trackId"],
    indices = [Index("owner", "gen", "k1", "k2", "albumId", "disc", "trackKey", "k3", "trackId", name = "index_health_song_order")],
)
class HealthSongEntity(
    val owner: String,
    val gen: Long,
    val trackId: String,
    val mask: Int,
    val k1: ByteArray,
    val k2: ByteArray,
    val albumId: String,
    val disc: Int,
    val trackKey: Int,
    val k3: ByteArray,
)

// The first copy seen with a recording's tag, in one place, and its length.
@Entity(tableName = "health_tag", primaryKeys = ["gen", "key"])
data class HealthTagEntity(val gen: Long, val key: String, val firstNode: Long, val firstSeconds: Int)

// Sets of copies of one recording, joined as found, by copy (its row in
// source_track). A copy with no row is a set of its own. `tagged` marks a
// copy joined by its tags; a set's root carries it for the whole set once
// the sets are done.
@Entity(tableName = "health_node", primaryKeys = ["gen", "node"])
data class HealthNodeEntity(val gen: Long, val node: Long, val parent: Long, val size: Int, val tagged: Boolean)

// A copy in a set of two or more, with how it sounds and how many tags it
// has, so the set's copies come best first.
@Entity(
    tableName = "health_member",
    primaryKeys = ["owner", "gen", "root", "copyId"],
    indices = [
        Index(value = ["owner", "gen", "root", "sound", "tags", "copyId"], orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.ASC, Index.Order.DESC, Index.Order.DESC, Index.Order.ASC], name = "index_health_member_order"),
        Index("owner", "gen", "trackId", name = "index_health_member_track"),
    ],
)
data class HealthMemberEntity(
    val owner: String,
    val gen: Long,
    val root: Long,
    val sound: Long,
    val tags: Int,
    val copyId: String,
    val trackId: String,
)

// A set of copies, in report order by its best copy's artist and title
// then id: its size, why its copies were taken for one, why its best is
// best, and how many library songs its copies are.
@Entity(
    tableName = "health_group",
    primaryKeys = ["owner", "gen", "root"],
    indices = [Index("owner", "gen", "k1", "k2", "bestId", name = "index_health_group_order")],
)
class HealthGroupEntity(
    val owner: String,
    val gen: Long,
    val k1: ByteArray,
    val k2: ByteArray,
    val bestId: String,
    val root: Long,
    val size: Int,
    val basis: String,
    val reason: String,
    val songs: Int,
)

// How a library song in a set of copies sounds, from the last set in
// report order that holds it.
@Entity(tableName = "health_quality", primaryKeys = ["gen", "trackId"])
data class HealthQualityEntity(val gen: Long, val trackId: String, val text: String)

// What the split album check reads of one album: the key of its title (its
// bucket), its first song in library order, its size, its album artist as
// shown and its key, every artist the album artist names (key, name, one a
// line each), and its years (one a line).
@Entity(
    tableName = "health_part",
    primaryKeys = ["gen", "albumId"],
    indices = [Index("gen", "bucket", "firstKey", "firstId", name = "index_health_part_bucket")],
)
data class HealthPartEntity(
    val gen: Long,
    val albumId: String,
    val bucket: String,
    val firstKey: String,
    val firstId: String,
    val size: Int,
    val artist: String,
    val artistKey: String,
    val albumArtists: String,
    val years: String,
)

// A release code an album's songs carry: 0 a MusicBrainz release, 1 its
// release group.
@Entity(
    tableName = "health_code",
    primaryKeys = ["gen", "albumId", "kind", "code"],
    indices = [Index("gen", "kind", "code", name = "index_health_code_code")],
)
data class HealthCodeEntity(val gen: Long, val albumId: String, val kind: Int, val code: String)

// How many of an album's songs name each artist, for its album artist when
// none is named.
@Entity(tableName = "health_tally", primaryKeys = ["gen", "albumId", "value"])
data class HealthTallyEntity(val gen: Long, val albumId: String, val value: String, val n: Int)

// A split album, in report order by its artist and title then its lead
// part, with what the check says of it, and where its heading comes in the
// check's lines: after every line of the albums before it in report order,
// each a heading and its songs (`linesBefore`, written for the report's
// own albums once they are all in).
@Entity(
    tableName = "health_split",
    primaryKeys = ["owner", "gen", "setId"],
    indices = [
        Index("owner", "gen", "k1", "k2", "leadAlbum", name = "index_health_split_order"),
        Index("owner", "gen", "linesBefore", name = "index_health_split_place"),
    ],
)
class HealthSplitEntity(
    val owner: String,
    val gen: Long,
    val k1: ByteArray,
    val k2: ByteArray,
    val leadAlbum: String,
    val setId: Long,
    val title: String,
    val artist: String,
    val reasons: String,
    val differences: String,
    @ColumnInfo(defaultValue = "0") val linesBefore: Long = 0,
)

// A split album's songs: part by part, each in its album's order; a song's
// album is found by the song.
@Entity(
    tableName = "health_split_song",
    primaryKeys = ["owner", "gen", "setId", "part", "disc", "trackKey", "titleKey", "trackId"],
    indices = [Index("owner", "gen", "trackId", name = "index_health_split_song_track")],
)
class HealthSplitSongEntity(
    val owner: String,
    val gen: Long,
    val setId: Long,
    val part: Int,
    val disc: Int,
    val trackKey: Int,
    val titleKey: ByteArray,
    val trackId: String,
)

// One step that puts back a done step of a fix run, numbered in the order
// the steps were done; they run the last done first.
@Entity(tableName = "fix_undo", primaryKeys = ["owner", "ord"])
data class FixUndoEntity(val owner: String, val ord: Long, val kind: String, val id: String, val title: String, val payload: String)

// One song a backup or a playlist file names, in file order: what it says
// of the song, the keys it is looked for by, and what the library walk
// found for each way of finding it. `kind` is which file (0 a backup, 1 a
// playlist file), `list` which of its lists, `value` what the entry carries
// beside the song (a rating).
@Entity(
    tableName = "match_query",
    primaryKeys = ["owner", "id"],
    indices = [
        Index("owner", "relinkKey", name = "index_match_query_relinkKey"),
        Index("owner", "wholeKey", name = "index_match_query_wholeKey"),
        Index("owner", "nameKey", name = "index_match_query_nameKey"),
        Index("owner", "kind", "foundId", "id", name = "index_match_query_found"),
        Index("owner", "kind", "list", "id", name = "index_match_query_list"),
    ],
)
data class MatchQueryEntity(
    val owner: String,
    val id: Long,
    val kind: Int,
    val list: Int,
    val value: Int,
    val relinkKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val wholeKey: String,
    val nameKey: String,
    val parts: String,
    val relinkId: String? = null,
    val wholeId: String? = null,
    val wholeDist: Long? = null,
    val titleId: String? = null,
    val titleDist: Long? = null,
    val pathId: String? = null,
    @ColumnInfo(defaultValue = "0") val pathEnd: Int = 0,
    val foundId: String? = null,
    val pos: Long? = null,
    @ColumnInfo(defaultValue = "0") val skip: Boolean = false,
)

// One title question a playlist file asks of an entry (up to three), and
// the best answers the walk found: by artist and title, by artist alone,
// and, with no artist, the one song it points at.
@Entity(tableName = "match_title", primaryKeys = ["owner", "query", "rank"])
data class MatchTitleEntity(
    val owner: String,
    val query: Long,
    val rank: Int,
    val title: String,
    val artist: String,
    val lengthMs: Long,
    val versions: String,
    val hasArtist: Boolean,
    val p1Id: String? = null,
    val p1Rank: Int? = null,
    val p2Id: String? = null,
    val p2Rank: Int? = null,
    val soleId: String? = null,
    @ColumnInfo(defaultValue = "0") val soleCount: Int = 0,
)

// A title key a question is looked for by, the question (`target`: its
// entry times four plus which question), and the key's place among the
// question's keys.
@Entity(tableName = "match_lookup", primaryKeys = ["owner", "key", "target"])
data class MatchLookupEntity(val owner: String, val key: String, val target: Long, val rank: Int)
