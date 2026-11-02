package app.winters.octo.server

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

// The work's answer, or null at once when a full copy of the library is
// running (`copying`, or `syncing` on) or starts before the work ends. The
// work goes on either way; only the wait for it ends.
internal suspend fun <T> unlessCopying(work: Deferred<T>, copying: Boolean, syncing: StateFlow<Boolean>): T? {
    if (copying || syncing.value) return null
    return coroutineScope {
        val copy = async { syncing.first { it } }
        select<T?> {
            work.onAwait { it }
            copy.onAwait { null }
        }.also { copy.cancel() }
    }
}
