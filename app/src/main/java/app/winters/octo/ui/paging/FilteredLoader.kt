package app.winters.octo.ui.paging

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.playback.QUEUE_WINDOW
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryField
import app.winters.octo.query.matcher
import app.winters.octo.sort.Listening
import app.winters.octo.sort.TrackFields
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.random.Random

// How many rows a filtered walk reads from its list at a time.
private const val FILTER_CHUNK = 500

// How many of the kept rows apart a survey keeps a key, for jumps.
private const val MARK_EVERY = 500

// How many independent random picks a survey keeps for Shuffle.
const val SURVEY_PICKS = 16

// What a filter reads beside the songs: their likes and their plays, each
// only when one of its rules asks.
class FilterInputs(
    val liked: suspend (List<String>) -> Set<String>,
    val plays: suspend (List<String>) -> Map<String, Listening>,
    val songs: suspend (List<String>) -> List<TrackEntity>,
    val now: () -> Long = System::currentTimeMillis,
    val random: Random = Random.Default,
)

// A song list as the Songs page's filter leaves it, in its order: today's
// matcher, which folds text in Kotlin, run over the list a chunk at a time,
// never a SQL stand-in. Its pages read on through the list until they have
// as many kept rows as they asked for. Its count is one full pass, which
// also files the kept rows under letters for a list ordered by name, keeps
// a key every 500 kept rows (so a jump walks at most 500 rows), and keeps
// what a play starts from: the first kept row, independent random picks,
// and the first QUEUE_WINDOW + 1 kept songs.
class FilteredLoader(
    private val base: ListLoader<TrackEntity>,
    query: LibraryQuery,
    private val inputs: FilterInputs,
    // For a list ordered by a name: the name a row files under, and whether
    // the list runs Z to A.
    private val heading: ((TrackEntity) -> String)? = null,
    private val descending: Boolean = false,
) : ListLoader<TrackEntity> {
    private val query = query.copy(sort = null, limit = null)
    private val readsLikes = this.query.rules.any { it.field == QueryField.Favourite }
    private val readsPlays = this.query.rules.any { it.field == QueryField.Plays || it.field == QueryField.LastPlayed }

    override val tables: Array<String> =
        (base.tables.toList() + listOfNotNull(if (readsLikes) "liked_track" else null, if (readsPlays) PLAY_TOTAL else null)).distinct().toTypedArray()

    override val order: Comparator<RowKey> get() = base.order

    override val songsOnce: Boolean get() = base.songsOnce

    @Volatile private var marks: List<RowKey>? = null

    // The rows of `rows` the filter keeps, in their order.
    private suspend fun kept(rows: List<Keyed<TrackEntity>>): List<Keyed<TrackEntity>> {
        if (rows.isEmpty()) return rows
        val ids = rows.map { it.row.id }
        val liked = if (readsLikes) inputs.liked(ids) else emptySet()
        val listening = if (readsPlays) inputs.plays(ids) else emptyMap()
        val test = query.matcher(inputs.now(), TrackFields(liked, listening)) ?: return rows
        return rows.filter { test(it.row) }
    }

    override suspend fun after(key: RowKey?, limit: Int): List<Keyed<TrackEntity>> {
        val out = ArrayList<Keyed<TrackEntity>>()
        var cursor = key
        while (out.size < limit) {
            currentCoroutineContext().ensureActive()
            val chunk = base.after(cursor, FILTER_CHUNK)
            out += kept(chunk).take(limit - out.size)
            if (chunk.size < FILTER_CHUNK) break
            cursor = chunk.last().key
        }
        return out
    }

    override suspend fun before(key: RowKey, limit: Int): List<Keyed<TrackEntity>> {
        val out = ArrayList<Keyed<TrackEntity>>()
        var cursor = key
        while (out.size < limit) {
            currentCoroutineContext().ensureActive()
            val chunk = base.before(cursor, FILTER_CHUNK)
            out += kept(chunk).take(limit - out.size)
            if (chunk.size < FILTER_CHUNK) break
            cursor = chunk.last().key
        }
        return out
    }

    override suspend fun count(): Int = survey(null).count

    override suspend fun survey(anchor: RowKey?): Survey {
        val bins = if (heading != null) IntArray(LetterRanges.ranges.size) else null
        val marks = ArrayList<RowKey>()
        val picks = arrayOfNulls<StartRow>(SURVEY_PICKS)
        val ids = ArrayList<String>()
        var first: StartRow? = null
        var count = 0
        var before = 0
        var cursor: RowKey? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val chunk = base.after(cursor, FILTER_CHUNK)
            for (row in kept(chunk)) {
                if (count % MARK_EVERY == 0) marks += row.key
                val start = StartRow(row.key, row.row.id)
                if (first == null) first = start
                if (ids.size <= QUEUE_WINDOW) ids += row.row.id
                count++
                // The count-th kept row takes each slot with chance 1/count,
                // each slot by its own draw.
                for (slot in picks.indices) if (inputs.random.nextInt(count) == 0) picks[slot] = start
                if (anchor != null && order.compare(row.key, anchor) < 0) before++
                if (bins != null) heading?.let { bins[LetterRanges.indexOf(it(row.row))]++ }
            }
            if (chunk.size < FILTER_CHUNK) break
            cursor = chunk.last().key
        }
        this.marks = marks
        val runs = bins?.let { LetterRanges.runs(it.toList(), descending) }
        return Survey(count, runs, anchor?.let { before }, marks, SurveyStart(first, picks.filterNotNull(), ids, count))
    }

    // The row `position` kept rows in: from the nearest key the last count
    // kept, walking on through the filter, so it only lands on kept rows.
    override suspend fun keyAt(position: Int): RowKey? {
        if (position < 0) return null
        val known = marks
        val mark = known?.getOrNull(position / MARK_EVERY)
        var left = if (mark == null) position else position % MARK_EVERY
        if (mark != null && left == 0) return mark
        var cursor: RowKey? = mark
        if (mark != null) left--
        while (true) {
            val rows = after(cursor, minOf(left + 1, FILTER_CHUNK))
            if (rows.size > left) return rows[left].key
            if (rows.isEmpty() || rows.size < minOf(left + 1, FILTER_CHUNK)) return null
            left -= rows.size
            cursor = rows.last().key
        }
    }

    override suspend fun keyOf(trackId: String): RowKey? {
        val key = base.keyOf(trackId) ?: return null
        val song = inputs.songs(listOf(trackId)).firstOrNull() ?: return null
        return key.takeIf { kept(listOf(Keyed(it, song))).isNotEmpty() }
    }

    override suspend fun rekey(key: RowKey): RowKey? = key

    override suspend fun present(ids: List<String>): Set<String> {
        val inList = base.present(ids)
        if (inList.isEmpty()) return inList
        val songs = inputs.songs(inList.toList())
        return kept(songs.map { Keyed(RowKey(0, emptyList(), it.id), it) }).mapTo(HashSet()) { it.row.id }
    }

    override suspend fun small(): List<Keyed<TrackEntity>>? = after(null, QUEUE_WINDOW + 1).takeIf { it.size <= QUEUE_WINDOW }

    override fun trackIdOf(row: TrackEntity): String = row.id
}
