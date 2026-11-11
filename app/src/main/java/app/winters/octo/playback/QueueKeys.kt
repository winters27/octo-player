package app.winters.octo.playback

// The numbers and keys of the queue's entries.
//
// An entry's number is its list's id in the high 32 bits and its row's
// number in the low: a list's rows are one range of numbers whatever its
// length, so lists never share one. A play numbers its start entry 0, the
// rows after it 1, 2, ... (phase 0) and the rows before it from
// PHASE1_BASE (phase 1); an add numbers its rows from 0.
//
// Each entry has a key in play order (qk) and in shuffle order (sk). A
// play's keys are functions of its rows' numbers, so a build that writes a
// number again writes the same keys; phase 1's keys all sort before phase
// 0's. A shuffled key is a hash of the play key and the list's seed: a
// random order that is the same after the app restarts.

const val LIST_SHIFT = 32

// The first number of a play's phase 1, and the most rows a phase holds.
const val PHASE1_BASE = 1L shl 22

// Keys of a list's rows are this far apart.
const val KEY_STEP = 1L shl 40

// A play's start entry's key; phase 0 counts up from it, phase 1 from 0.
const val KEY_MID = 1L shl 62

// The shuffled key of a shuffled play's first song, before every hashed one.
const val SK_FIRST = 1L shl 59

// Hashed shuffled keys fill [SK_REST, SK_REST + 2^62).
const val SK_REST = 1L shl 60

// The middle of the hashed keys' range, for an upgraded queue's shuffle order.
const val SK_MID = SK_REST + (1L shl 61)

// The closest two keys of a run may be; a gap that cannot hold a run this
// far apart is spaced out again first.
const val MIN_KEY_GAP = 1L shl 8

private const val LOW_BITS = 0xFFFFFFFFL

fun entryOf(listId: Long, n: Long): Long = (listId shl LIST_SHIFT) or n

fun listOfEntry(entry: Long): Long = entry ushr LIST_SHIFT

fun numberOfEntry(entry: Long): Long = entry and LOW_BITS

// The first and last numbers a list's rows can take.
fun firstEntryOf(listId: Long): Long = listId shl LIST_SHIFT

fun lastEntryOf(listId: Long): Long = (listId shl LIST_SHIFT) or LOW_BITS

// The id a player's item carries for an entry, and back.
fun entryKey(entry: Long): String = "e$entry"

fun entryOfKey(key: String?): Long? = key?.takeIf { it.startsWith("e") }?.substring(1)?.toLongOrNull()

// A play's play key for its row numbered `n`.
fun playKey(n: Long): Long = if (n < PHASE1_BASE) KEY_MID + n * KEY_STEP else (n - PHASE1_BASE) * KEY_STEP

// SplitMix64's finalizer: every bit of the answer depends on every bit of `z`.
fun mix64(z0: Long): Long {
    var z = z0
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}

// A row's shuffled key from its play key and its list's seed.
fun shuffledKey(seed: Long, qk: Long): Long = SK_REST + (mix64(seed xor qk) ushr 2)

// The number a play gives the row at `index` of a list started at `start`:
// 0 for the start, counting up after it, phase 1's numbers before it.
fun playNumber(index: Int, start: Int): Long = when {
    index == start -> 0L
    index > start -> (index - start).toLong()
    else -> PHASE1_BASE + index
}

// A play's shuffled key for its row numbered `n`: a shuffled play's first
// song before every other, the rest hashed with the list's seed.
fun playShuffledKey(n: Long, seed: Long, firstShuffled: Boolean): Long =
    if (n == 0L && firstShuffled) SK_FIRST else shuffledKey(seed, playKey(n))

// The hashed key a shuffled play's first song would have among the rest,
// kept while it holds the first key, so turning shuffle on at another song
// gives it back; null for every other row.
fun firstKeyBefore(n: Long, seed: Long, firstShuffled: Boolean): Long? = if (n == 0L && firstShuffled) shuffledKey(seed, playKey(0)) else null

// The first shuffled key below `lowest` (the smallest key the queue shows
// or a list still being written can take) and at or above `floor`:
// KEY_STEP below it, else halfway down to the floor, else the floor itself
// when the floor's own row is the lowest.
fun firstKeyBelow(lowest: Long, floor: Long): Long {
    val room = if (floor < 0 && lowest > 0 && lowest > Long.MAX_VALUE + floor) Long.MAX_VALUE else lowest - floor
    return when {
        room > KEY_STEP -> lowest - KEY_STEP
        room > 0 -> floor + room / 2
        else -> floor
    }
}

// The place of a shuffled play's first song among `count`, each equally
// likely.
fun firstPick(random: kotlin.random.Random, count: Int): Int = random.nextInt(count)

// `count` keys for a run placed between `lo` and `hi` (null for no
// neighbor on that side), in the run's order: evenly spaced inside a gap,
// KEY_STEP apart before the first key or after the last. Null when the gap
// cannot hold them MIN_KEY_GAP apart, so it is spaced out first.
fun spacedKeys(lo: Long?, hi: Long?, count: Int): LongArray? {
    if (count <= 0) return LongArray(0)
    return when {
        lo == null && hi == null -> LongArray(count) { KEY_MID + it * KEY_STEP }
        hi == null -> {
            val room = Long.MAX_VALUE - lo!!
            val step = if (room / (count + 1) >= KEY_STEP) KEY_STEP else room / (count + 1)
            if (step < MIN_KEY_GAP) null else LongArray(count) { lo + (it + 1) * step }
        }
        lo == null -> {
            val room = hi - Long.MIN_VALUE
            val step = if (room < 0 || room / (count + 1) >= KEY_STEP) KEY_STEP else room / (count + 1)
            if (step < MIN_KEY_GAP) null else LongArray(count) { hi - (count - it) * step }
        }
        else -> {
            val step = gapStep(lo, hi, count)
            if (step < MIN_KEY_GAP) null else LongArray(count) { lo + (it + 1) * step }
        }
    }
}

// How far apart `count` keys strictly between `lo` and `hi` fall, spaced evenly.
fun gapStep(lo: Long, hi: Long, count: Int): Long {
    if (hi <= lo) return 0
    // A span past Long.MAX_VALUE spaces them as if it were that wide.
    val span = try {
        Math.subtractExact(hi, lo)
    } catch (e: ArithmeticException) {
        Long.MAX_VALUE
    }
    return span / (count + 1L)
}

// A big add's base and step in one order: rows numbered i take base +
// (i + 1) * step, room for PHASE1_BASE rows. Null when the gap is too
// small for that many at MIN_KEY_GAP, so it is spaced out first.
fun addSpacing(lo: Long?, hi: Long?): Pair<Long, Long>? {
    val rows = PHASE1_BASE.toInt()
    return when {
        lo == null && hi == null -> KEY_MID to KEY_STEP
        hi == null -> {
            val room = Long.MAX_VALUE - lo!!
            val step = if (room / (rows + 1) >= KEY_STEP) KEY_STEP else room / (rows + 1)
            if (step < MIN_KEY_GAP) null else lo to step
        }
        lo == null -> {
            val room = hi - Long.MIN_VALUE
            val step = if (room < 0 || room / (rows + 1) >= KEY_STEP) KEY_STEP else room / (rows + 1)
            if (step < MIN_KEY_GAP) null else (hi - (rows + 1L) * step) to step
        }
        else -> gapStep(lo, hi, rows).takeIf { it >= MIN_KEY_GAP }?.let { lo to it }
    }
}
