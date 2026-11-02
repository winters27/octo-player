package app.winters.octo.playback

import app.winters.octo.lyrics.NetworkWatch
import app.winters.octo.server.QueueSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// Looks for a queue saved on another device for a car to pick up: when a
// car connects, when it opens Home of its own accord, and when the network
// changes while a car has connected, so one connected in a dead zone finds
// it once the signal is back. Nothing is looked for until a car connects.
@Singleton
class CarResume internal constructor(
    private val check: suspend () -> Unit,
    networkChanges: Flow<Unit>,
    private val scope: CoroutineScope,
) {
    @Inject constructor(queue: QueueSync, network: NetworkWatch) : this(
        check = { queue.checkNow() },
        networkChanges = network.changes,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    @Volatile private var carAsked = false

    init {
        scope.launch { networkChanges.collect { if (carAsked) check() } }
    }

    fun carConnected() {
        carAsked = true
        scope.launch { check() }
    }

    fun homeOpened() {
        if (carAsked) scope.launch { check() }
    }
}
