package app.winters.octo.testing

import androidx.paging.CombinedLoadStates
import androidx.paging.ItemSnapshotList
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors

// A screen's hold on a paged list, without Compose: its pages collected
// into a presenter on a thread of its own, read by place as a lazy list
// reads them (a read asks Paging for the pages around that place), with a
// wait until no page loads.
class Presented<T : Any>(pages: Flow<PagingData<T>>, scope: CoroutineScope) : AutoCloseable {
    private val thread = Executors.newSingleThreadExecutor()
    private val main = thread.asCoroutineDispatcher()
    @Volatile private var updates = 0L

    val presenter = object : PagingDataPresenter<T>(main) {
        override suspend fun presentPagingDataEvent(event: PagingDataEvent<T>) = Unit
    }

    private val job = scope.launch(main) {
        presenter.addOnPagesUpdatedListener { updates++ }
        pages.collectLatest { presenter.collectFrom(it) }
    }

    suspend fun size(): Int = withContext(main) { presenter.size }

    // The item at `index`, asking for the pages around it.
    suspend fun get(index: Int): T? = withContext(main) { presenter[index] }

    suspend fun peek(index: Int): T? = withContext(main) { presenter.peek(index) }

    suspend fun snapshot(): ItemSnapshotList<T> = withContext(main) { presenter.snapshot() }

    // The items read, in place.
    suspend fun loaded(): List<T> = snapshot().items

    // Waits until no page is loading and none has landed for a few polls,
    // and `until` holds, `seconds` at most.
    suspend fun settle(seconds: Long = 10, until: suspend () -> Boolean = { true }) = withTimeout(seconds * 1_000) {
        var quiet = 0
        var seen = -1L
        while (quiet < 4) {
            delay(15)
            val (busy, now) = withContext(main) { loading(presenter.loadStateFlow.value) to updates }
            quiet = if (!busy && now == seen && until()) quiet + 1 else 0
            seen = now
        }
    }

    private fun loading(states: CombinedLoadStates?): Boolean =
        states == null || states.refresh is LoadState.Loading || states.append is LoadState.Loading || states.prepend is LoadState.Loading

    // The collection stops before its thread does, so nothing it was about
    // to run is dropped and it never outlives the list it shows.
    override fun close() {
        runBlocking { job.cancelAndJoin() }
        thread.shutdown()
    }
}
