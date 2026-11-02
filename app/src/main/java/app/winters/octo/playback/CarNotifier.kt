package app.winters.octo.playback

// A read of a list a car has open: whether the car asked for it of its own
// accord, rather than because it was told of a change, and when it began.
class CarRead internal constructor(val id: String, val ownAccord: Boolean, internal val at: Long)

// When to tell a car that a list it keeps open has changed. A list is told
// once for a change of something it reads (carInputsOf); a change while it
// has not been read since is held for that read, so a car is never told
// twice for one look. A change of the library alone waits while a full copy
// of the server runs, and goes when it ends. `notify` answers false when
// nobody has the list open any more, and the list is let go. Used on one
// thread.
class CarNotifier(private val notify: (String) -> Boolean) {
    // A list told of a change and not read since: when it was told, and
    // whether another change came meanwhile.
    private class Told(val at: Long, val again: Boolean)

    private val open = LinkedHashSet<String>()
    private val told = HashMap<String, Told>()
    private var clock = 0L
    private var syncing = false
    private var libraryHeld = false

    fun opened(id: String) {
        open += id
    }

    fun closed(id: String) {
        open -= id
        told.remove(id)
    }

    val openIds: Set<String> get() = open.toSet()

    fun changed(kind: CarInput) = changedAny(setOf(kind))

    // Everything changed at once: each open list is told once.
    fun changedAll() = changedAny(CarInput.entries.toSet())

    private fun changedAny(kinds: Set<CarInput>) {
        var any = kinds
        if (CarInput.Library in kinds && syncing) {
            libraryHeld = true
            any = kinds - CarInput.Library
        }
        for (id in open.toList()) {
            val node = parseCarId(id) ?: continue
            if (carInputsOf(node).none { it in any }) continue
            heard(id)
        }
    }

    // One list changed on its own: something fetched behind it came back.
    fun changedNode(id: String) {
        if (id in open) heard(id)
    }

    // A read of a list begins.
    fun readStarted(id: String): CarRead = CarRead(id, ownAccord = id !in told, at = ++clock)

    // A read of a list ended, well or not. A change it was told of before
    // the read began is answered by it, and one held meanwhile goes now. A
    // tell sent after the read began waits for the read that tell brings.
    fun readEnded(read: CarRead) {
        val mark = told[read.id] ?: return
        if (mark.at >= read.at) return
        if (mark.again) tell(read.id) else told.remove(read.id)
    }

    fun syncing(on: Boolean) {
        syncing = on
        if (!on && libraryHeld) {
            libraryHeld = false
            changed(CarInput.Library)
        }
    }

    private fun heard(id: String) {
        val mark = told[id]
        if (mark != null) told[id] = Told(mark.at, again = true) else tell(id)
    }

    private fun tell(id: String) {
        if (notify(id)) told[id] = Told(clock, again = false) else closed(id)
    }
}
