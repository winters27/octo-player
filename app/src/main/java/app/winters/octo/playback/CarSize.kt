@file:OptIn(UnstableApi::class)

package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import app.winters.octo.ui.common.indexLetter
import java.text.BreakIterator

// How much one answer to a car may take. Media3 cuts an answer at 256 KB of
// parcel without a sign, and a car never asks for the rest, so every answer
// is kept well under that.
const val CAR_ANSWER_BUDGET = 192 * 1024

// What an item costs before its texts, what each text costs beyond its
// characters, and what each extra costs. Media3 adds two extras of its own
// (the media type and the folder type) to every item it sends.
private const val ITEM_BYTES = 512
private const val TEXT_BYTES = 8
private const val KEY_BYTES = 152
private const val ADDED_KEYS = 2

// The longest text a car is sent, in UTF-16 units; it shows two lines at most.
const val CAR_TEXT_MAX = 120

// The most an item can take as Media3 sends it to a car: every text it
// carries at two bytes a unit, its cover address twice (as written and
// decoded), and each extra.
fun carBytes(item: MediaItem): Int {
    val m = item.mediaMetadata
    var bytes = ITEM_BYTES + textBytes(item.mediaId)
    listOf(m.title, m.displayTitle, m.subtitle, m.description, m.artist, m.albumTitle, m.albumArtist).forEach { bytes += textBytes(it) }
    bytes += 2 * textBytes(m.artworkUri?.toString())
    val extras = m.extras
    val keys = (extras?.size() ?: 0) + ADDED_KEYS + if (m.supportedCommands.isEmpty()) 0 else 1
    bytes += keys * KEY_BYTES
    bytes += textBytes(extras?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE))
    m.supportedCommands.forEach { bytes += textBytes(it) }
    return bytes
}

fun textBytes(text: CharSequence?): Int = if (text == null) 4 else 2 * text.length + TEXT_BYTES

// A text as a car gets it: whole when it fits CAR_TEXT_MAX, otherwise cut at
// the last word that fits, or between whole characters when one word is
// longer than that.
fun carText(text: String?): String? {
    if (text == null || text.length <= CAR_TEXT_MAX) return text
    val words = BreakIterator.getWordInstance().apply { setText(text) }
    val atWord = words.preceding(CAR_TEXT_MAX + 1).takeIf { it != BreakIterator.DONE && it > 0 }
    val cut = atWord ?: BreakIterator.getCharacterInstance().apply { setText(text) }.preceding(CAR_TEXT_MAX + 1)
    return text.substring(0, cut.coerceAtLeast(0)).trimEnd()
}

// The whole list as parts that each fit `budget`, by the size of each item,
// in order. The same sizes always pack the same way. An item bigger than
// the budget has a part of its own.
fun carParts(sizes: List<Int>, budget: Int = CAR_ANSWER_BUDGET): List<IntRange> {
    if (sizes.isEmpty()) return emptyList()
    val parts = mutableListOf<IntRange>()
    var start = 0
    var used = 0
    sizes.forEachIndexed { index, size ->
        if (index > start && used + size > budget) {
            parts += start until index
            start = index
            used = 0
        }
        used += size
    }
    parts += start until sizes.size
    return parts
}

// Whether every item of an answer fits in one.
fun fitsOneAnswer(sizes: List<Int>, budget: Int = CAR_ANSWER_BUDGET): Boolean = sizes.sum() <= budget

// The words of a part's row: where it starts and ends in the list, counting
// from one.
fun partTitle(range: IntRange): String = "${range.first + 1} to ${range.last + 1}"

// The letter each item of a list sorted by name files under, as the phone's
// headings and letter rail file it (a letter, or "#"), from the items' sort
// keys; null for a list in another order.
fun carLetters(sortKeys: List<String>?): List<String>? = sortKeys?.map { indexLetter(it).toString() }

// A list a car may open: how many items it holds, the letter each files
// under when it is sorted by name, how its items show, and each item, built
// only when an answer needs it.
class CarListing(
    val count: Int,
    val letters: List<String>? = null,
    val style: CarStyle? = null,
    private val build: (Int) -> MediaItem,
) {
    fun item(index: Int): MediaItem = build(index)

    // The items under `letter`, in list order.
    fun under(letter: String): List<MediaItem> {
        val letters = letters ?: return emptyList()
        return letters.indices.filter { letters[it] == letter }.map(::item)
    }

    fun all(): List<MediaItem> = List(count, ::item)

    companion object {
        fun of(items: List<MediaItem>, style: CarStyle? = null) = CarListing(items.size, style = style, build = items::get)
    }
}

// How the rows that open a long list's letters and parts look.
class CarSplitRows(
    val letter: (CarNode.Heading, CarStyle?) -> MediaItem,
    val part: (CarNode.Part, IntRange, String?) -> MediaItem,
)

// The answer for a list a car opened: whole when it fits one answer;
// otherwise a row for each letter it files under, in list order, when it
// files under more than one, or a row for each part. Items are built while
// their sizes are added up, which stops as soon as the list is too big to
// send whole.
fun carListAnswer(node: CarNode, list: CarListing, rows: CarSplitRows, budget: Int = CAR_ANSWER_BUDGET): List<MediaItem> {
    val built = ArrayList<MediaItem>()
    var used = 0
    while (built.size < list.count && used <= budget) {
        val item = list.item(built.size)
        used += carBytes(item)
        built += item
    }
    if (used <= budget) return built
    val letters = list.letters?.distinct()
    if (letters != null && letters.size > 1) return letters.map { rows.letter(CarNode.Heading(it, node), list.style) }
    while (built.size < list.count) built += list.item(built.size)
    return carFitOrParts(node, built, rows, budget)
}

// The items under one letter of a list: whole when they fit one answer,
// otherwise a row for each part.
fun carLetterAnswer(node: CarNode.Heading, list: CarListing, rows: CarSplitRows, budget: Int = CAR_ANSWER_BUDGET): List<MediaItem> =
    carFitOrParts(node, list.under(node.heading), rows, budget)

// `items` whole when they fit one answer, otherwise a row for each part.
fun carFitOrParts(node: CarNode, items: List<MediaItem>, rows: CarSplitRows, budget: Int = CAR_ANSWER_BUDGET): List<MediaItem> {
    val sizes = items.map(::carBytes)
    if (fitsOneAnswer(sizes, budget)) return items
    return carParts(sizes, budget).mapIndexed { index, range -> rows.part(CarNode.Part(index, node), range, items[range.first].mediaMetadata.title?.toString()) }
}

// The items of part `index` of `items`, as carFitOrParts cut them.
fun carPartItems(index: Int, items: List<MediaItem>, budget: Int = CAR_ANSWER_BUDGET): List<MediaItem> {
    val range = carParts(items.map(::carBytes), budget).getOrNull(index) ?: return emptyList()
    return items.subList(range.first, range.last + 1)
}

// The page of an answer a browser asked for. A car asks for none (page 0,
// as many as there are); a Media3 browser may ask for pages, and gets no
// more than it asked for.
fun <T> pageOf(items: List<T>, page: Int, pageSize: Int): List<T> {
    if (page < 0 || pageSize <= 0) return items
    val from = page.toLong() * pageSize
    if (from >= items.size) return emptyList()
    return items.subList(from.toInt(), minOf(items.size.toLong(), from + pageSize).toInt())
}
