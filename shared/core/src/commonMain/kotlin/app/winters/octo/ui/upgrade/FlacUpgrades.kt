package app.winters.octo.ui.upgrade

import app.winters.octo.subsonic.LibraryActionResult
import app.winters.octo.subsonic.LibraryActionState
import app.winters.octo.subsonic.Upgrade
import app.winters.octo.subsonic.UpgradeStage

// Looking for a FLAC to take a song's place, as the phone and the desktop
// both ask and say it: the menu words, which songs are still being looked
// for, and the one line said when some are done. The apps run the calls
// and the timers; what each answer means is worked out here, once.

// The server takes at most this many songs in one go, and refuses more.
const val UPGRADE_BATCH = 50

// How often to ask how the upgrades are going while any is still on.
const val UPGRADE_POLL_MS = 3_000L

// The library is read again at most this often while FLACs keep landing,
// and once more when the last one is done.
const val UPGRADE_RELOAD_GAP_MS = 30_000L

// A song the server is no longer listing as asked for is let go after this
// many looks, so a server that forgot it is not asked about forever.
const val UPGRADE_MISSES = 10

// The song menu's row.
const val FIND_HIGHER_QUALITY = "Find higher quality"

// What the line says while Soulseek is out and the server holds on.
const val WAITING_FOR_SOULSEEK = "Waiting for Soulseek"

// "1 song", "12 songs".
fun songsText(count: Int): String = if (count == 1) "1 song" else "$count songs"

// The album menu's row: "Find higher quality for 9 songs".
fun findHigherQualityLabel(count: Int): String = "Find higher quality for ${songsText(count)}"

// What is asked before an album's songs go to the server, and the
// promise under it; together they are findHigherQualityQuestion.
fun findHigherQualityAsk(count: Int): String = "Look for a higher quality copy of ${songsText(count)} on Soulseek?"

const val KEEPS_ORIGINAL = "Each original is kept until its replacement passes."

fun findHigherQualityQuestion(count: Int): String = "${findHigherQualityAsk(count)} $KEEPS_ORIGINAL"

// One song to look for, with the title the line uses until the server
// says its own.
data class UpgradeAsk(val id: String, val title: String)

// What one look at the server's list means: a line to show, if any, and
// whether the library should be read again now.
data class UpgradeNews(val notice: String?, val reload: Boolean)

// Follows the songs asked for here until each is done. Not tied to any
// thread: every call takes the follower's own lock, so the asking and the
// looking may run side by side.
class UpgradeFollower(
    private val reloadGapMs: Long = UPGRADE_RELOAD_GAP_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Songs sent to the server and not answered yet. They show as being
    // looked for, but a look does not settle them: the server may still
    // list an older, finished try of the same song.
    private val asking = LinkedHashMap<String, String>()

    // Songs the server took on, as it last listed them.
    private val following = LinkedHashMap<String, Upgrade>()
    private val misses = HashMap<String, Int>()

    // What the asking has met since its line was last made.
    private var queued = 0
    private var over = 0
    private val refused = ArrayList<Pair<String, String>>()

    // An upgrade landed since the library was last read again.
    private var landed = false
    private var lastReload = Long.MIN_VALUE / 2
    private var waiting = false

    // Each song being looked for, by id, as the server last said; a song
    // not answered yet shows as queued.
    @Synchronized
    fun pending(): Map<String, Upgrade> =
        asking.mapValues { (id, title) -> Upgrade(id = id, title = title, state = UpgradeStage.Queued.wire) } + following

    @Synchronized
    fun isPending(): Boolean = asking.isNotEmpty() || following.isNotEmpty()

    // Takes songs to send: those not being looked for already, at most a
    // batch. The rest are counted for the line.
    @Synchronized
    fun ask(songs: List<UpgradeAsk>): List<UpgradeAsk> {
        val fresh = songs.distinctBy { it.id }.filter { it.id !in asking && it.id !in following }
        val taken = fresh.take(UPGRADE_BATCH)
        over += fresh.size - taken.size
        taken.forEach { asking[it.id] = it.title }
        return taken
    }

    // The server's answer to one song: queued is followed from now on;
    // anything else, or `error` when it could not be asked, is said why.
    @Synchronized
    fun answered(id: String, result: LibraryActionResult?, error: String? = null) {
        val title = asking.remove(id) ?: return
        if (result?.outcome == LibraryActionState.Queued) {
            queued++
            following[id] = Upgrade(id = id, title = title, state = UpgradeStage.Queued.wire)
        } else {
            refused += title to ((error ?: result?.detail)?.let(::sentence) ?: "the server did not say why")
        }
    }

    // The line once every song sent has been answered, or null when there
    // is nothing to say.
    @Synchronized
    fun askedNotice(): String? {
        val parts = buildList {
            if (queued > 0) add("Looking for higher quality for ${songsText(queued)}")
            when (refused.size) {
                0 -> Unit
                1 -> add("Could not look for higher quality for ${refused[0].first}: ${refused[0].second}")
                else -> add("Could not look for higher quality for ${songsText(refused.size)}: ${refused[0].second}")
            }
            if (over > 0) add("At most $UPGRADE_BATCH songs at a time, so ${songsText(over)} were left out")
        }
        queued = 0
        over = 0
        refused.clear()
        return parts.joinToString(". ").ifEmpty { null }
    }

    // Takes on the songs the server is still on from before, such as from
    // a run of the app that ended while they were looked for. Nothing is
    // said about them until they are done.
    @Synchronized
    fun adopt(list: List<Upgrade>) {
        latest(list).values.filter { it.inFlight && it.id !in asking }.forEach { following[it.id] = it }
        waiting = following.values.any { it.stage == UpgradeStage.Waiting }
    }

    // One look at the server's list.
    @Synchronized
    fun seen(list: List<Upgrade>): UpgradeNews {
        val now = latest(list)
        val done = ArrayList<Upgrade>()
        for (id in following.keys.toList()) {
            val entry = now[id]
            if (entry == null) {
                val missed = (misses[id] ?: 0) + 1
                misses[id] = missed
                if (missed >= UPGRADE_MISSES) {
                    following.remove(id)
                    misses.remove(id)
                }
                continue
            }
            misses.remove(id)
            if (entry.inFlight) {
                following[id] = entry
            } else {
                val asked = following.remove(id)
                // The server's title, unless it left it out.
                done += if (entry.title.isBlank()) entry.copy(title = asked?.title.orEmpty()) else entry
            }
        }
        val waitingNow = following.values.any { it.stage == UpgradeStage.Waiting }
        val startsWaiting = waitingNow && !waiting
        waiting = waitingNow

        if (done.any { it.stage == UpgradeStage.Upgraded }) landed = true
        val reload = landed && (!isPending() || clock() - lastReload >= reloadGapMs)
        if (reload) {
            landed = false
            lastReload = clock()
        }
        return UpgradeNews(doneNotice(done, startsWaiting), reload)
    }

    // A different account, or the app's view of the server going away.
    @Synchronized
    fun forget() {
        asking.clear()
        following.clear()
        misses.clear()
        queued = 0
        over = 0
        refused.clear()
        landed = false
        waiting = false
    }
}

// Whether the server is still on it. A state from a newer server counts
// as still on, as the downloads do, so it is asked about again.
private val Upgrade.inFlight: Boolean get() = stage.pending || stage == UpgradeStage.Unknown

// Each song's newest entry: one still on wins over an older finished try.
private fun latest(list: List<Upgrade>): Map<String, Upgrade> =
    list.groupBy { it.id }.mapValues { (_, entries) ->
        entries.firstOrNull { it.inFlight } ?: entries.maxBy { it.updatedAt.orEmpty() }
    }

// The one line for songs just done, in the order the listener most wants
// to hear it: what landed, what was not found, then what went wrong.
internal fun doneNotice(done: List<Upgrade>, startsWaiting: Boolean = false): String? {
    fun of(stage: UpgradeStage) = done.filter { it.stage == stage }
    val upgraded = of(UpgradeStage.Upgraded)
    val missing = of(UpgradeStage.NotFound)
    val failed = of(UpgradeStage.Failed)
    val skipped = of(UpgradeStage.Skipped)
    val rehearsed = of(UpgradeStage.Rehearsed)
    val parts = buildList {
        if (upgraded.isNotEmpty()) add("Found higher quality for ${songsText(upgraded.size)}")
        if (missing.isNotEmpty()) add("No higher quality copy found for: ${titles(missing)}")
        when (failed.size) {
            0 -> Unit
            1 -> add("Could not upgrade ${failed[0].title}: ${why(failed[0])}")
            else -> add("Could not upgrade ${songsText(failed.size)}: ${why(failed[0])}")
        }
        when (skipped.size) {
            0 -> Unit
            1 -> add("Skipped ${skipped[0].title}: ${why(skipped[0])}")
            else -> add("Skipped ${songsText(skipped.size)}: ${why(skipped[0])}")
        }
        if (rehearsed.isNotEmpty()) add("The server only rehearsed, so nothing changed")
        if (startsWaiting) add(WAITING_FOR_SOULSEEK)
    }
    return parts.joinToString(". ").ifEmpty { null }
}

// Up to three titles, then how many more: "Towers, Holocene and 4 more".
private fun titles(songs: List<Upgrade>): String {
    val names = songs.map { it.title.ifBlank { "a song" } }
    if (names.size <= 3) return names.joinToString(", ")
    return names.take(3).joinToString(", ") + " and ${names.size - 3} more"
}

private fun why(upgrade: Upgrade): String = upgrade.detail?.let(::sentence) ?: "the server did not say why"

// The server's words as part of a line: trimmed, without a closing full
// stop, since the line puts its own between the parts.
private fun sentence(text: String): String? = text.trim().trimEnd('.').trim().ifEmpty { null }
