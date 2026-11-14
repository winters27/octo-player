package app.winters.octo.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import app.winters.octo.offline.DownloadLookup
import app.winters.octo.offline.OfflineMarks
import app.winters.octo.offline.OfflineMarksSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

// Whether there is a connection, which songs are saved, and when the set of
// downloaded songs last could have changed, for every song row.
val LocalOfflineMarks = compositionLocalOf { OfflineMarks() }

// How a song row asks whether its song is downloaded.
val LocalDownloadLookup = compositionLocalOf<DownloadLookup> {
    object : DownloadLookup {
        override suspend fun isDone(trackId: String) = false
    }
}

// How faint a song that cannot play right now is drawn.
const val OutOfReachAlpha = 0.4f

@HiltViewModel
class OfflineMarksViewModel @Inject constructor(source: OfflineMarksSource) : ViewModel() {
    val marks: StateFlow<OfflineMarks> = source.marks

    val lookup: DownloadLookup = source.lookup
}

// Gives everything inside the marks, kept up to date.
@Composable
fun ProvideOfflineMarks(vm: OfflineMarksViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val marks by vm.marks.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalOfflineMarks provides marks, LocalDownloadLookup provides vm.lookup, content = content)
}

// Whether a song is downloaded to the phone: asked for this song alone, and
// again only when the set of downloaded songs can have changed. A song
// found online never is.
@Composable
fun isDownloaded(track: TrackEntity): Boolean {
    if (isFind(track.id)) return false
    val version = LocalOfflineMarks.current.doneVersion
    val lookup = LocalDownloadLookup.current
    val done by produceState(false, track.id, version, lookup) { value = lookup.isDone(track.id) }
    return done
}

// Whether a song cannot play right now: no connection, and neither on the
// phone, saved from an earlier play nor downloaded. Only a song that could
// be out of reach asks whether it is downloaded.
@Composable
fun isOutOfReach(track: TrackEntity): Boolean {
    if (!LocalOfflineMarks.current.outOfReachUnlessDownloaded(track)) return false
    return !isDownloaded(track)
}

// After a song's artist or length: a filled arrow for a song downloaded to
// the phone, the cloud for one that streams, nothing for a phone file.
// A song found online has none: its artwork or add button says it. A song
// the server owns that the phone's copy lacks streams like any library
// song, so it has the cloud.
@Composable
fun SourceMark(track: TrackEntity) {
    when {
        isDownloaded(track) -> DownloadedMark()
        !track.onPhone && (!isFind(track.id) || track.id in LocalOwnedFinds.current) -> CloudMark()
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
