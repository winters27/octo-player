package app.winters.octo.ui.common

import android.util.Log
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.data.LibraryRefresh
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RefreshViewModel @Inject constructor(
    private val library: LibraryRefresh,
    private val feedback: Feedback,
) : ViewModel() {
    var refreshing by mutableStateOf(false)
        private set

    // Rescans the phone and syncs the server, with the page's own reload
    // alongside. A second pull while one runs is ignored. A failed server
    // copy is said, since the pull asked for it.
    fun refresh(own: suspend () -> Unit) {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            try {
                coroutineScope {
                    launch { own() }
                    library.refresh()?.let(feedback::show)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Octo", "refresh failed: ${e.javaClass.simpleName}")
            } finally {
                refreshing = false
            }
        }
    }
}

// Pull down on a library page to refresh it: the phone is rescanned, the
// server synced, and `onRefresh` reloads whatever else the page has from
// the server. Drawn like Home's pull. It fills the page, standing in for
// the page's outer Box.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(
    modifier: Modifier = Modifier,
    onRefresh: suspend () -> Unit = {},
    vm: RefreshViewModel = hiltViewModel(),
    content: @Composable BoxScope.() -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = vm.refreshing,
        onRefresh = { vm.refresh(onRefresh) },
        modifier = modifier.fillMaxSize(),
        content = content,
    )
}
