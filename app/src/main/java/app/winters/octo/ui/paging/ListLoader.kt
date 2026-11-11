package app.winters.octo.ui.paging

import app.winters.octo.playback.QUEUE_WINDOW
import app.winters.octo.ui.common.LetterRun

// A row of a list with its place in the list.
data class Keyed<T>(val key: RowKey, val row: T)

// What one pass over a list found: how many rows it has, its letter runs
// (null when the list has no letter rail), how many rows come before the
// anchor, and for a list filtered in Kotlin the key of every 500th kept
// row, so a jump walks at most 500 rows from the nearest one, and what the
// pass kept to start a play from (`start`).
data class Survey(
    val count: Int,
    val runs: List<LetterRun>?,
    val anchorAt: Int?,
    val marks: List<RowKey>? = null,
    val start: SurveyStart? = null,
)

// A row a play can start from, by its key and its song.
data class StartRow(val key: RowKey, val trackId: String)

// What a list filtered in Kotlin keeps from its count's pass, in constant
// memory: its first row (Play), rows picked uniformly at random among the
// kept ones, each by its own draws (one per Shuffle), and the first
// QUEUE_WINDOW + 1 kept songs in list order (`ids`; more than QUEUE_WINDOW
// means the list is too long to hand a play whole).
data class SurveyStart(val first: StartRow?, val picks: List<StartRow>, val ids: List<String>, val count: Int)

// One list walked in pages by its row keys, both ways, with no Paging or
// Compose types: the screens, the car and the queue's builds all walk lists
// through it. Pages hold at most `limit` rows, so nothing holds the list.
interface ListLoader<T : Any> {
    // The tables whose writes change the list.
    val tables: Array<String>

    // The list's order of its row keys, in SqlOrder's rules.
    val order: Comparator<RowKey>

    // Whether the list holds each song at most once.
    val songsOnce: Boolean

    // How many rows the list has, read the cheapest exact way.
    suspend fun count(): Int

    // The count, the letter runs and how many rows come before `anchor`.
    suspend fun survey(anchor: RowKey?): Survey

    // Up to `limit` rows after `key` in list order; from the top for null.
    suspend fun after(key: RowKey?, limit: Int): List<Keyed<T>>

    // Up to `limit` rows before `key`, nearest first.
    suspend fun before(key: RowKey, limit: Int): List<Keyed<T>>

    // The key of the row at `position`, by one probe; null past the end.
    suspend fun keyAt(position: Int): RowKey?

    // The key of the first row holding this song, or null when none does.
    suspend fun keyOf(trackId: String): RowKey?

    // A key another reader of the list handed out, in this loader's keys:
    // the same key where keys are the rows' own data, or null when the row
    // it names is gone.
    suspend fun rekey(key: RowKey): RowKey?

    // Which of these ids the list still holds, at most 900 a call.
    suspend fun present(ids: List<String>): Set<String>

    // The whole list in list order when it has at most QUEUE_WINDOW rows,
    // read with at most QUEUE_WINDOW + 1 rows and no write, lock or wait;
    // null when it has more. A play decides by it whether the list plays
    // whole at once.
    suspend fun small(): List<Keyed<T>>? = after(null, QUEUE_WINDOW + 1).takeIf { it.size <= QUEUE_WINDOW }

    // The song a row holds, for a play.
    fun trackIdOf(row: T): String? = null
}
