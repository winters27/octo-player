package app.winters.octo.playback

// When to tell a car that a list it keeps open has changed. A list is told
// once for a change of something it reads (carInputsOf); a change while it
// has not been read since is held for that read, so a car is never told
// twice for one look. A change of the library alone waits while a full copy
// of the server runs, and goes when it ends. `notify` answers false when
// nobody has the list open any more, and the list is let go. Used on one
// thread.
class CarNotifier(private val notify: (String) -> Boolean) {
    // The lists cars have open, and for each one told and not read since,
    // whether another change came meanwhile.
    private val open = LinkedHashSet<String>()
    private val told = HashMap<String, Boolean>()
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

    fun changed(kind: CarInput) {
        if (kind == CarInput.Library && syncing) {
            libraryHeld = true
            return
        }
        for (id in open.toList()) {
            val node = parseCarId(id) ?: continue
            if (kind !in carInputsOf(node)) continue
            if (id in told) told[id] = true else tell(id)
        }
    }

    // One list changed on its own: something fetched behind it came back.
    fun changedNode(id: String) {
        if (id !in open) return
        if (id in told) told[id] = true else tell(id)
    }

    // A read of a list begins. Answers whether the car asked for it of its
    // own accord, rather than because it was told of a change.
    fun readStarted(id: String): Boolean = id !in told

    // A read of a list ended, well or not. A change held for it goes now.
    fun readEnded(id: String) {
        when (told[id]) {
            true -> tell(id)
            false -> told.remove(id)
            null -> Unit
        }
    }

    fun syncing(on: Boolean) {
        syncing = on
        if (!on && libraryHeld) {
            libraryHeld = false
            changed(CarInput.Library)
        }
    }

    private fun tell(id: String) {
        if (notify(id)) told[id] = false else closed(id)
    }
}
