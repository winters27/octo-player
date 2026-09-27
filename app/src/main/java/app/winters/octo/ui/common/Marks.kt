package app.winters.octo.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.isFind
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.offline.OfflineMarks
import app.winters.octo.offline.OfflineMarksSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

// Which songs are downloaded, and which cannot play with no connection, for
// every song row.
val LocalOfflineMarks = compositionLocalOf { OfflineMarks() }

// How faint a song that cannot play right now is drawn.
const val OutOfReachAlpha = 0.4f

@HiltViewModel
class OfflineMarksViewModel @Inject constructor(source: OfflineMarksSource) : ViewModel() {
    val marks: StateFlow<OfflineMarks> = source.marks
}

// Gives everything inside the marks, kept up to date.
@Composable
fun ProvideOfflineMarks(vm: OfflineMarksViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val marks by vm.marks.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalOfflineMarks provides marks, content = content)
}

// After a song's artist or length: a filled arrow for a song downloaded to
// the phone, the cloud for one that streams, nothing for a phone file.
// A song found online has none: its artwork or download button says it.
@Composable
fun SourceMark(track: TrackEntity) {
    when {
        LocalOfflineMarks.current.isDownloaded(track) -> DownloadedMark()
        !track.onPhone && !isFind(track.id) -> CloudMark()
    }
}

@Composable
fun DownloadedMark() {
    Icon(
        painterResource(OctoIcons.Downloaded),
        contentDescription = "Downloaded",
        tint = OctoColors.TextMuted,
        modifier = Modifier.size(14.dp),
    )
}
