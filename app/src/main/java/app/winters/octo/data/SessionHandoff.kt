package app.winters.octo.data

import javax.inject.Inject
import javax.inject.Singleton

// What happens as the server in use changes. Before another server (or
// none) is in use, `leaving` puts the old one's things away while it is
// still the one in use: what was playing counts for it, and its queue is
// kept for when it comes back. The switch sets it at start.
@Singleton
class SessionHandoff @Inject constructor() {
    fun interface Leaving {
        // `to` is the id of the server about to be in use, or null for none.
        suspend fun leave(from: Session, to: String?)
    }

    @Volatile var leaving: Leaving? = null

    suspend fun leave(from: Session, to: String?) {
        leaving?.leave(from, to)
    }
}
