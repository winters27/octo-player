package app.winters.octo.radio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

// Octo's radio: songs like the seeds, from the library and from what the
// server suggests, played the way a good station plays them.
//
// Each song is a list of weighted features: its genres (a genre that is
// rare in this library says more), each genre in the song's era, its year,
// and who made it. How alike a song is to the seeds is the cosine of the
// two lists, but only when they share a genre: a shared year or artist
// lifts a song that already fits and never pulls in one that does not. The
// server's suggestions add a bonus by their rank. Picks are drawn at random
// with the better matches favored, then spaced so an album, a title or an
// artist does not come back too soon. Songs from outside the library land
// at random, at the share the listener chose (see RadioTuning).

// How much each kind of likeness counts. Genre and genre-in-its-era weigh
// the same, so a song from the right genre but another era scores well
// under one from both (about 0.4 against 0.85), which a tenth taken off
// for not being a favorite cannot overturn.
private const val GENRE_WEIGHT = 1.0
private const val ERA_WEIGHT = 1.0
private const val YEAR_WEIGHT = 0.5
private const val ARTIST_WEIGHT = 0.6
private const val COMPOSER_WEIGHT = 0.4
private const val YEAR_SPREAD = 7

// A server suggestion's bonus: the top one counts about as much as a close
// match from the library, and the bonus fades down its list.
private const val SUGGESTED_WEIGHT = 0.5
private const val RANK_DEPTH = 20.0

// Songs played this recently wait until nothing else will do.
internal const val RECENT_DAYS = 8

// Nudges: a song not marked as a favorite, one played a lot, and one
// played in the last couple of weeks each give up a tenth.
private const val FRESH_DAYS = 15
private const val MANY_PLAYS = 4
private const val NUDGE = 0.9

// An artist with a song rated one star gives up half.
private const val DISLIKED_ARTIST = 0.5

// How far a radio may drift from its share of outside songs, in songs,
// before the next pick puts it right. Outside songs have a share of their
// own because library songs carry genre and year and would otherwise win
// nearly every draw against a suggestion that only has its rank. At 1 song
// the pull-back fired so often that the old every-third-song opening still
// came up one radio in fourteen; at 1.5 it is one in two hundred.
private const val SHARE_SLACK = 1.5

// The most songs of the rarer kind in a row: outside songs when they are
// under half of the radio, library songs when they are over half.
private const val RARER_RUN = 2

// What a favorite (a heart, or four or five stars) counts for when the
// listener asks for favorites more often. A favorite already skips the
// tenth taken off the rest, so this makes one about 1.3 times as likely.
private const val FAVORITE_BOOST = 1.15

// A big library is sampled, and only the best matches are drawn from.
private const val CANDIDATE_CAP = 4000
private const val KEEP_AT_LEAST = 200
private const val KEEP_PER_PICK = 8

private const val DAY_MS = 86_400_000L

// What the radio is asked for.
class RadioInput(
    // What the radio is like. The first is what it started from.
    val seeds: List<RadioSong>,
    // Every song in the library.
    val library: List<RadioSong>,
    // The server's songs like the seeds, best first.
    val suggested: List<RadioSong> = emptyList(),
    // What played or is queued just before, oldest first; the spacing
    // carries on from these.
    val before: List<RadioSong> = emptyList(),
    // Never picked, like the queue. The first seed is never picked either;
    // the other seeds can be (an artist radio plays more of the artist).
    val exclude: Set<String> = emptySet(),
    val now: Long,
    // How the listener tuned radio (see RadioTuning).
    val tuning: RadioTuning = RadioTuning(),
    // Which of the songs in `before` came from outside the library, oldest
    // first, so the run of one kind carries on from the songs already
    // queued. Null works it out from the library; the phone passes it,
    // since its `before` holds only library songs.
    val beforeOutside: List<Boolean>? = null,
)

private class Scored(val song: RadioSong, val score: Double)

// Up to `count` songs like the seeds, in play order. Empty when nothing is
// alike at all (the seeds have no genre and the server suggested nothing).
fun radioMix(input: RadioInput, count: Int, random: Random = Random.Default): List<RadioSong> {
    if (count <= 0 || input.seeds.isEmpty()) return emptyList()
    val everything = input.library + input.suggested
    val rarity = genreRarity(everything)
    val seedFeatures = HashMap<String, Double>()
    input.seeds.forEach { features(it, rarity, seedFeatures) }
    val seedNorm = sqrt(seedFeatures.values.sumOf { it * it })
    val seedGenres = input.seeds.flatMapTo(HashSet()) { it.genreKeys }
    val seedArtists = input.seeds.flatMapTo(HashSet()) { it.artistKeys }
    val disliked = everything.filter { it.rating == 1 }.flatMapTo(HashSet()) { it.artistKeys }
    val rank = HashMap<String, Int>()
    input.suggested.forEachIndexed { index, song -> rank.putIfAbsent(song.id, index) }
    val skip = input.exclude + input.seeds.first().id
    val owned = input.library.mapTo(HashSet()) { it.id }

    // Library songs that could fit: sharing a genre or an artist with the seeds.
    val fromLibrary = input.library
        .filter { song -> song.genreKeys.any(seedGenres::contains) || song.artistKeys.any(seedArtists::contains) }
        .let { if (it.size > CANDIDATE_CAP) it.shuffled(random).take(CANDIDATE_CAP) else it }
    val scored = (input.suggested + fromLibrary).distinctBy { it.id }
        .filter { it.id !in skip && it.rating != 1 && !isRadioFiller(it.title, it.durationMs, it.genres) }
        .map { song ->
            val own = HashMap<String, Double>()
            features(song, rarity, own)
            var score = likeness(own, seedFeatures, seedNorm)
            rank[song.id]?.let { score += SUGGESTED_WEIGHT / (1 + it / RANK_DEPTH) }
            Scored(song, nudged(song, score, input.now, disliked, input.tuning.favorites))
        }
        .filter { it.score > 0 }
        .sortedByDescending { it.score }
    // Songs the library has and songs it does not are drawn from apart, so
    // the new ones keep their share. Only the library's are trimmed.
    val (fromOwned, found) = scored.partition { it.song.id in owned }
    // Only my library: songs from outside it never play, not even to fill.
    val fromNew = if (input.tuning.discovery == RadioDiscovery.LibraryOnly) emptyList() else found
    val heardAfter = input.now - RECENT_DAYS * DAY_MS
    val heardLately = { s: Scored -> (s.song.lastPlayedAt ?: Long.MIN_VALUE) >= heardAfter }
    // Adventure widens or narrows how many of the best matches stay in,
    // never below the floor; songs heard lately do not count toward it.
    val keep = max(KEEP_AT_LEAST, (count * KEEP_PER_PICK * input.tuning.adventure.keep).toInt())
    val (fresh, lately) = fromOwned.partition { !heardLately(it) }
    val known = fresh.take(keep)
    val spare = lately.take(keep)
    // Variety moves the artist and album spacing; another version of the
    // same song always waits the usual spacing.
    val titleGap = radioSpacing(input.library.size)
    val gap = (titleGap * input.tuning.variety.scale).roundToInt().coerceAtLeast(2)
    val beforeOutside = input.beforeOutside ?: input.before.map { it.id !in owned }
    return pick(known, fromNew.filterNot(heardLately), spare + fromNew.filter(heardLately), input.before, count,
        Spacing(gap, titleGap), input.tuning.discovery.share, input.tuning.adventure.reach, runOf(beforeOutside), random)
}

// How far apart an album and an artist (`gap`) and a title (`title`) play.
private class Spacing(val gap: Int, val title: Int)

// The run of one kind at the end of `outside` (oldest first): above 0 for
// songs from outside the library, below 0 for the library's.
internal fun runOf(outside: List<Boolean>): Int {
    val last = outside.lastOrNull() ?: return 0
    val length = outside.takeLastWhile { it == last }.size
    return if (last) length else -length
}

// The share of the picks that can go to songs from outside the library:
// the share asked for, as far as there are songs of each kind to fill it.
// Without this, a share the outside songs cannot reach takes them all
// first and leaves a block of library songs at the end, and the same the
// other way. The server places outside songs the same way
// (last_fm_radio_placement.rs, paced_share).
internal fun pacedShare(share: Double, outside: Int, library: Int, slots: Int): Double {
    if (slots <= 0 || outside + library == 0) return share
    if (outside + library < slots) return outside.toDouble() / (outside + library)
    val most = (outside.toDouble() / slots).coerceIn(0.0, 1.0)
    val least = (1.0 - library.toDouble() / slots).coerceIn(0.0, 1.0)
    return share.coerceIn(least, most)
}

// How many picks apart an album or a title must be; an artist must be half
// as far. Grows with the library: 8 for a small one, up to 30.
fun radioSpacing(librarySize: Int): Int = (6.7 + librarySize / 6000.0).toInt().coerceIn(8, 30)

// Whether the next pick goes to a song from outside the library. A draw at
// the share, so outside songs land anywhere instead of every third song;
// pulled back once the radio is more than SHARE_SLACK songs off its share;
// and never more than RARER_RUN of the rarer kind in a row. `run` counts
// the picks in a row just before: above 0 outside, below 0 library. The
// server's song radio uses the same rule (last_fm_radio_placement.rs).
internal fun outsideDue(share: Double, picked: Int, outside: Int, run: Int, random: Random): Boolean {
    if (share <= 0.0) return false
    if (share >= 1.0) return true
    val target = share * (picked + 1)
    if (target - outside > SHARE_SLACK) return true
    if (outside + 1 - target > SHARE_SLACK) return false
    if (share < 0.5 && run >= RARER_RUN) return false
    if (share > 0.5 && run <= -RARER_RUN) return true
    return random.nextDouble() < share
}

// Picks in play order from two sorted lists: `known` (the library's) and
// `discovered` (suggestions the library does not have), keeping about
// `share` of the picks for the second while it lasts, placed at random
// (outsideDue). A draw may land as far down a list as `reach` of its
// total weight.
private fun pick(
    known: List<Scored>,
    discovered: List<Scored>,
    spare: List<Scored>,
    before: List<RadioSong>,
    count: Int,
    spacing: Spacing,
    asked: Double,
    reach: Double,
    leadRun: Int,
    random: Random,
): List<RadioSong> {
    val all = known + discovered
    val albums = all.mapNotNullTo(HashSet()) { it.song.album }.size
    val artists = all.flatMapTo(HashSet()) { it.song.artistKeys }.size
    val played = before.toMutableList()
    val picked = ArrayList<RadioSong>()
    val taken = HashSet<String>()
    val held = LinkedHashMap<String, Scored>()
    val knownTotal = known.sumOf { it.score }
    val discoveredTotal = discovered.sumOf { it.score }
    var newPicks = 0
    var run = leadRun
    val share = if (asked <= 0.0) 0.0 else pacedShare(asked, discovered.size, known.size, count)

    // One weighted draw from a list: land on the song whose share of the
    // running total holds a random threshold, then take the first one from
    // there, going on from the top, that is free and that the spacing
    // allows. Null when none is.
    fun draw(list: List<Scored>, total: Double): Scored? {
        if (list.isEmpty()) return null
        val threshold = random.nextDouble() * total * reach
        var start = 0
        var sum = list[0].score
        while (start < list.size - 1 && sum < threshold) sum += list[++start].score
        for (step in list.indices) {
            val candidate = list[(start + step) % list.size]
            if (candidate.song.id in taken) continue
            if (tooSoon(candidate.song, played, spacing, albums, artists, random)) {
                held.putIfAbsent(candidate.song.id, candidate)
                continue
            }
            return candidate
        }
        return null
    }

    var tries = 0
    while (picked.size < count && taken.size < all.size && tries++ < count * 100) {
        val newDue = outsideDue(share, picked.size, newPicks, run, random) && discovered.any { it.song.id !in taken }
        val chosen = (
            if (newDue) draw(discovered, discoveredTotal) ?: draw(known, knownTotal)
            else draw(known, knownTotal) ?: draw(discovered, discoveredTotal)
            ) ?: continue
        taken += chosen.song.id
        picked += chosen.song
        played += chosen.song
        if (chosen in discovered) {
            newPicks++
            run = maxOf(run, 0) + 1
        } else {
            run = minOf(run, 0) - 1
        }
    }
    // Not enough with the spacing: the best of what it held back, then
    // songs heard lately, never the same artist twice in a row unless
    // nothing else is left.
    val rest = (held.values.sortedByDescending { it.score } + spare)
        .map { it.song }.filter { it.id !in taken }.distinctBy { it.id }.toMutableList()
    while (picked.size < count && rest.isNotEmpty()) {
        val last = played.lastOrNull()
        val next = rest.firstOrNull { song -> last == null || song.artistKeys.none(last.artistKeys::contains) } ?: rest.first()
        rest.remove(next)
        taken += next.id
        picked += next
        played += next
    }
    return picked
}

// Whether a song would come too soon after the same album, title, artist
// or composer. The distance is drawn fresh each time so the spacing does
// not feel mechanical, and never asks for more variety than the pool has.
private fun tooSoon(song: RadioSong, played: List<RadioSong>, spacing: Spacing, albums: Int, artists: Int, random: Random): Boolean {
    val gap = spacing.gap
    val far = minOf(gap + random.nextInt(gap), albums - 1, played.size)
    if (far > 0) {
        val recent = played.subList(played.size - far, played.size)
        if (recent.any { song.album != null && it.album == song.album }) return true
    }
    // The title waits the usual spacing whatever the variety; at Normal it
    // is the album's distance, drawn once.
    val titleFar = if (spacing.title == gap) far else minOf(spacing.title + random.nextInt(spacing.title), albums - 1, played.size)
    if (titleFar > 0 && song.titleKey.isNotEmpty()) {
        val recent = played.subList(played.size - titleFar, played.size)
        if (recent.any { it.titleKey == song.titleKey }) return true
    }
    val half = gap / 2
    val near = minOf(half + random.nextInt(half + 1), artists - 1, played.size)
    if (near <= 0) return false
    val recent = played.subList(played.size - near, played.size)
    return recent.any { other ->
        other.artistKeys.any(song.artistKeys::contains) || other.composerKeys.any(song.composerKeys::contains)
    }
}

// How telling each genre is in this library: a rarer genre says more.
// Counted by album, so a long album counts no more than a short one.
private fun genreRarity(songs: List<RadioSong>): Map<String, Double> {
    val albums = HashSet<String>()
    val withGenre = HashMap<String, HashSet<String>>()
    songs.forEach { song ->
        val album = song.album ?: song.id
        albums += album
        song.genreKeys.forEach { withGenre.getOrPut(it) { HashSet() } += album }
    }
    return withGenre.mapValues { (_, set) -> ln(1.0 + albums.size.toDouble() / set.size) }
}

// A year spread over the seven either side, most on the year itself, so
// 1994 and 1997 count as close. The spread has a length of one, so a whole
// year weighs what its weight says however it is spread.
private val YearShape: DoubleArray = run {
    val raw = DoubleArray(2 * YEAR_SPREAD + 1) { cos((it - YEAR_SPREAD) / (YEAR_SPREAD + 1.0) * PI / 2) }
    val length = sqrt(raw.sumOf { it * it })
    DoubleArray(raw.size) { raw[it] / length }
}

private fun MutableMap<String, Double>.add(key: String, weight: Double) {
    this[key] = (this[key] ?: 0.0) + weight
}

// A song's features, added into `into`. Genre and genre-in-its-era are
// what a match must share; year, artist and composer only add to one.
private fun features(song: RadioSong, rarity: Map<String, Double>, into: MutableMap<String, Double>) {
    val genres = song.genreKeys
    val total = genres.sumOf { rarity[it] ?: 1.0 }
    genres.forEach { genre ->
        val share = (rarity[genre] ?: 1.0) / total
        into.add("g:$genre", GENRE_WEIGHT * share)
        song.year?.let { year ->
            YearShape.forEachIndexed { i, w -> into.add("e:$genre:${year + i - YEAR_SPREAD}", ERA_WEIGHT * share * w) }
        }
    }
    song.year?.let { year ->
        YearShape.forEachIndexed { i, w -> into.add("y:${year + i - YEAR_SPREAD}", YEAR_WEIGHT * w) }
    }
    song.artistKeys.forEach { into.add("a:$it", ARTIST_WEIGHT / song.artistKeys.size) }
    song.composerKeys.forEach { into.add("c:$it", COMPOSER_WEIGHT / song.composerKeys.size) }
}

// How alike a song is to the seeds, 0 to 1: the cosine of their features,
// or 0 when they share no genre.
private fun likeness(song: Map<String, Double>, seeds: Map<String, Double>, seedNorm: Double): Double {
    var core = 0.0
    var extra = 0.0
    var norm = 0.0
    for ((key, weight) in song) {
        norm += weight * weight
        val other = seeds[key] ?: continue
        if (key[0] == 'g' || key[0] == 'e') core += weight * other else extra += weight * other
    }
    if (core <= 0.0 || norm <= 0.0 || seedNorm <= 0.0) return 0.0
    return (core + extra) / (sqrt(norm) * seedNorm)
}

private fun nudged(song: RadioSong, score: Double, now: Long, disliked: Set<String>, favorites: Boolean): Double {
    var result = score
    if (!song.liked && song.rating < 4) result *= NUDGE
    if (song.plays > MANY_PLAYS) result *= NUDGE
    if (song.lastPlayedAt != null && now - song.lastPlayedAt < FRESH_DAYS * DAY_MS) result *= NUDGE
    if (song.artistKeys.any(disliked::contains)) result *= DISLIKED_ARTIST
    if (favorites && (song.liked || song.rating >= 4)) result *= FAVORITE_BOOST
    return result
}
