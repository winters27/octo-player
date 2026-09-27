package app.winters.octo.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.lyrics.signedTiming
import app.winters.octo.lyrics.timingLabel

// One lyrics timing to set: its name with the timing now beside it, a line
// on what it applies to, then Earlier and Later a step a tap, and Reset.
@Composable
fun TimingAdjuster(
    title: String,
    about: String,
    offsetMs: Long,
    limitMs: Long,
    onStep: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = OctoType.label,
                color = OctoColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                signedTiming(offsetMs),
                style = OctoType.section.copy(fontFeatureSettings = "tnum"),
                color = OctoColors.TextPrimary,
                modifier = Modifier.padding(start = 12.dp).semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "$title: ${timingLabel(offsetMs)}"
                },
            )
        }
        Text(
            about,
            style = OctoType.caption,
            color = OctoColors.TextMuted,
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 10.dp),
        )
        TimingButtons(offsetMs, limitMs, onStep, onReset)
    }
}

// Earlier and Later a step a tap, and Reset under them, dimmed while there
// is nothing to reset. Earlier always shows the words sooner.
@Composable
fun TimingButtons(offsetMs: Long, limitMs: Long, onStep: (Int) -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlazeButton("Earlier", onClick = { onStep(-1) }, modifier = Modifier.weight(1f), enabled = offsetMs > -limitMs)
            GlazeButton("Later", onClick = { onStep(1) }, modifier = Modifier.weight(1f), enabled = offsetMs < limitMs)
        }
        val moved = offsetMs != 0L
        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
            Text(
                "Reset",
                style = OctoType.label,
                color = OctoColors.TextSecondary,
                maxLines = 1,
                modifier = Modifier
                    .alpha(if (moved) 1f else 0.4f)
                    .clip(CircleShape)
                    .clickable(enabled = moved, role = Role.Button, onClick = onReset)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
