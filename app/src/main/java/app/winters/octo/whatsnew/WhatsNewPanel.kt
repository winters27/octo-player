package app.winters.octo.whatsnew

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.winters.octo.BuildConfig
import app.winters.octo.design.GlassPopup
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.markdown.Block
import app.winters.octo.markdown.MarkdownText
import app.winters.octo.markdown.parseMarkdown
import app.winters.octo.ui.common.LocalHaze
import java.io.IOException

// The release notes as a large glass panel in the middle of the screen:
// the version as a big title, then the notes, which scroll when long. A tap
// outside or back closes it.
@Composable
fun WhatsNewPanel(visible: Boolean, onDismiss: () -> Unit) {
    GlassPopup(
        visible = visible,
        anchor = null,
        onDismiss = onDismiss,
        backdrop = LocalHaze.current,
        title = "What's new",
        maxWidth = 460.dp,
        heightShare = 0.82f,
    ) {
        val context = LocalContext.current
        val notes = remember { readNotes(context) }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 28.dp),
        ) {
            Text("What's new", style = OctoType.label, color = OctoColors.TextSecondary)
            Text(
                "Octo ${BuildConfig.VERSION_NAME}",
                style = OctoType.title,
                color = OctoColors.TextPrimary,
                modifier = Modifier.padding(top = 2.dp, bottom = 18.dp).semantics { heading() },
            )
            MarkdownText(notes)
        }
    }
}

// The notes from the app's assets, read once as the panel opens.
private fun readNotes(context: Context): List<Block> = try {
    parseMarkdown(context.assets.open(WHATS_NEW_FILE).bufferedReader().use { it.readText() })
} catch (e: IOException) {
    Log.w("Octo", "what's new: could not read the notes: ${e.javaClass.simpleName}")
    emptyList()
}
