package app.winters.octo.ui.online

import app.winters.octo.ui.common.LoadState
import app.winters.octo.ui.common.load

// Runs a read from the server. Nothing back means no server is signed in.
suspend fun <T : Any> loadOnline(block: suspend () -> T?): LoadState<T> = when (val state = load(block)) {
    is LoadState.Ready -> state.data?.let { LoadState.Ready(it) } ?: LoadState.Failed("Sign in to your server to see this.")
    is LoadState.Failed -> state
    LoadState.Loading -> LoadState.Loading
}
