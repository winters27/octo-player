package app.winters.octo.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.winters.octo.data.userMessage
import app.winters.octo.design.AccentButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import kotlin.coroutines.cancellation.CancellationException

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Failed(val message: String) : LoadState<Nothing>
    data class Ready<T>(val data: T) : LoadState<T>
}

// Runs a read and turns any failure into a message for the screen.
suspend fun <T> load(block: suspend () -> T): LoadState<T> = try {
    LoadState.Ready(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    LoadState.Failed(e.userMessage())
}

@Composable
fun <T> LoadStateContent(
    state: LoadState<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        LoadState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = OctoColors.Accent, modifier = Modifier.size(28.dp))
        }
        is LoadState.Failed -> Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    state.message,
                    style = OctoType.bodySmall,
                    color = OctoColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                AccentButton("Try again", onClick = onRetry)
            }
        }
        is LoadState.Ready -> content(state.data)
    }
}
