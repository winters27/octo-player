package app.winters.octo.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.playlists.ImportReport

// The kinds of file offered when picking a playlist to import. Phones label
// M3U files in several ways, and some not at all.
val PlaylistFileTypes = arrayOf(
    "audio/x-mpegurl",
    "audio/mpegurl",
    "application/x-mpegurl",
    "application/vnd.apple.mpegurl",
    "text/plain",
    "application/octet-stream",
)

// Where importing a playlist file is up to.
sealed interface ImportState {
    data object Working : ImportState

    data class Done(val report: ImportReport) : ImportState

    data object Failed : ImportState
}

// The line under "New playlist" for making one from an M3U file.
@Composable
fun ImportPlaylistLine(onClick: () -> Unit) {
    PlaylistLine("Import a playlist file", "M3U or M3U8", onClick) {
        IconTile(painterResource(OctoIcons.Folder), OctoColors.BackgroundTertiary, 56.dp)
    }
}

// How the last import went, quietly, with the lines it could not find
// behind a tap.
@Composable
fun ImportNote(state: ImportState) {
    var open by remember(state) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        val text = when (state) {
            ImportState.Working -> "Importing…"
            ImportState.Failed -> "That file couldn't be read."
            is ImportState.Done -> importSummary(state.report)
        }
        Text(text, style = OctoType.bodySmall, color = OctoColors.TextMuted)
        val missed = (state as? ImportState.Done)?.report?.missed.orEmpty()
        if (missed.isNotEmpty()) {
            Text(
                if (open) "Hide songs not found" else "Show ${missed.size} not found",
                style = OctoType.caption,
                color = OctoColors.TextSecondary,
                modifier = Modifier
                    .clickable(role = Role.Button) { open = !open }
                    .semantics { stateDescription = if (open) "Shown" else "Hidden" }
                    .padding(vertical = 8.dp),
            )
            if (open) {
                missed.forEach { line ->
                    Text(line, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// "Imported 42 of 45 songs into "Road trip"", or why nothing was.
internal fun importSummary(report: ImportReport): String = when {
    report.total == 0 -> "\"${report.name}\" has no songs in it."
    report.matched == 0 -> "None of the ${report.total} songs in \"${report.name}\" are in your library."
    else -> "Imported ${report.matched} of ${if (report.total == 1) "1 song" else "${report.total} songs"} into \"${report.name}\""
}
