package app.winters.octo.playback

import androidx.media3.common.MediaItem
import app.winters.octo.home.HomeSection
import app.winters.octo.home.HomeSectionEntry

// How many of a shelf's items Home shows in a car before "See all", from the
// most down to the fewest it tries before showing each shelf as one row.
val CarShelfCaps = listOf(8, 6, 4, 2)

// A shelf as a car's Home shows it: which one, and its items.
data class CarShelf(val section: HomeSection, val items: List<MediaItem>)

// Home's shelves for a car, as the phone's Home shows them: the ones the
// listener shows, in their order, leaving out any that are empty. With no
// songs in the library, only the chart (even when hidden, as the phone
// shows it under its empty card) and Live radio (when shown).
fun carHomeShelves(order: List<HomeSectionEntry>, shelves: Map<HomeSection, List<MediaItem>>, libraryEmpty: Boolean): List<CarShelf> {
    fun of(section: HomeSection) = shelves[section]?.takeIf { it.isNotEmpty() }?.let { CarShelf(section, it) }
    if (libraryEmpty) {
        val live = order.firstOrNull { it.section == HomeSection.InternetRadio }?.shown != false
        return listOfNotNull(of(HomeSection.Charts), if (live) of(HomeSection.InternetRadio) else null)
    }
    return order.filter { it.shown }.mapNotNull { of(it.section) }
}

// The ways Home's answer is put together, by what each shelf needs.
class CarHomeParts(
    // A shelf's item carried under the shelf's title.
    val grouped: (MediaItem, String) -> MediaItem,
    // The "See all" item at the end of a shelf that holds more than shown.
    val seeAll: (CarShelf) -> MediaItem,
    // The shelf as one row, opening it whole.
    val row: (CarShelf) -> MediaItem,
    val size: (MediaItem) -> Int = ::carBytes,
)

// Home's answer for a car: the resume row when there is one, then each shelf
// as a group of up to a cap of its items and "See all" when it holds more.
// The caps step down until the whole fits one answer; when even the fewest
// do not, each shelf becomes one row that opens it.
fun carHomeAnswer(resume: MediaItem?, shelves: List<CarShelf>, parts: CarHomeParts, budget: Int = CAR_ANSWER_BUDGET): List<MediaItem> {
    val lead = listOfNotNull(resume)
    for (cap in CarShelfCaps) {
        val items = lead + shelves.flatMap { shelf ->
            val shown = shelf.items.take(cap) + if (shelf.items.size > cap) listOf(parts.seeAll(shelf)) else emptyList()
            shown.map { parts.grouped(it, shelf.section.title) }
        }
        if (items.sumOf(parts.size) <= budget) return items
    }
    return lead + shelves.map(parts.row)
}
