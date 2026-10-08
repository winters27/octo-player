package app.winters.octo.radio

// How a listener tunes Octo's radio: how much of it is music from outside
// the library, how far it strays from the seeds, how soon an artist or an
// album comes back, and whether favorites come round more often. Each app
// keeps these in its own settings. The defaults play as radio always has,
// apart from where the outside songs land.
data class RadioTuning(
    val discovery: RadioDiscovery = RadioDiscovery.Balanced,
    val adventure: RadioAdventure = RadioAdventure.Balanced,
    val variety: RadioVariety = RadioVariety.Normal,
    val favorites: Boolean = false,
)

// The share of a radio kept for songs the library does not have, from the
// server's suggestions. Balanced is the share Octo's Your Mix keeps.
enum class RadioDiscovery(val share: Double, val label: String, val detail: String) {
    LibraryOnly(0.0, "Only my library", "Never a song from outside your library"),
    ALittle(0.15, "A little", "About 1 song in 7 from outside your library"),
    Balanced(0.35, "Balanced", "About 1 song in 3 from outside your library"),
    Lots(0.6, "Lots", "About 3 songs in 5 from outside your library"),
    MostlyNew(0.85, "Mostly new", "About 5 songs in 6 from outside your library"),
}

// How far down the best matches a pick may land, as a share of their total
// weight, and how many of the library's matches stay in the running.
enum class RadioAdventure(val reach: Double, val keep: Double, val label: String, val detail: String) {
    Focused(0.2, 0.5, "Focused", "Only the closest matches"),
    Balanced(0.5, 1.0, "Balanced", "Close matches, with some surprises"),
    Wander(1.0, 2.0, "Wander", "Deep cuts and further afield"),
}

// How far apart an album, a title and an artist play, against the usual
// spacing, which grows with the library.
enum class RadioVariety(val scale: Double, val label: String, val detail: String) {
    Tight(0.5, "Tight", "Artists and albums come back sooner"),
    Normal(1.0, "Normal", "Spaced as the library's size suits"),
    Wide(1.6, "Wide", "Artists and albums come back much later"),
}
