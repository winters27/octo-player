package app.winters.octo.playback

// The tabs a car shows, and the ones that did not fit, which a last tab
// lists as rows.
data class CarTabs(val tabs: List<CarNode>, val more: List<CarNode>)

// The four tabs in the order they show, and the order they keep when a car
// has room for fewer: Radio goes first, since Home has its shelves too.
private val TabsShown = listOf(CarNode.Home, CarNode.Radio, CarNode.Playlists, CarNode.Library)
private val TabsKept = listOf(CarNode.Home, CarNode.Playlists, CarNode.Library, CarNode.Radio)

// The most tabs a car shows when it does not say: Android Auto shows four.
const val CAR_TABS_USUAL = 4

// The tabs for a car that says it shows at most `limit` (null when it said
// nothing). A missing, zero or negative limit, or one over four, counts as
// four. Under four, the first tabs that fit show, then More holding the
// rest; a car that shows one tab gets only that last tab, holding all four.
fun carTabs(limit: Int?): CarTabs {
    val room = limit?.takeIf { it in 1 until CAR_TABS_USUAL } ?: return CarTabs(TabsShown, emptyList())
    val shown = TabsKept.take(room - 1)
    return CarTabs(shown + CarNode.More, TabsKept.drop(room - 1))
}

// What the last tab is called: More beside other tabs, Octo when alone.
fun moreTitle(tabs: CarTabs): String = if (tabs.tabs.size == 1) "Octo" else "More"
