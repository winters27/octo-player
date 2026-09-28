package app.winters.octo.ui.livelists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.winters.octo.design.ArtworkShape
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.livelists.LiveList
import app.winters.octo.livelists.LiveListStarter
import app.winters.octo.ui.playlist.PlaylistLine

// A live list's own picture: the rules glyph lit in the accent on a faint
// wash of it, so a live list reads apart from a playlist at a glance.
@Composable
fun LiveMarkTile(size: Dp, modifier: Modifier = Modifier, shape: Shape = ArtworkShape) {
    Box(modifier.size(size).clip(shape).background(OctoColors.Accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(painterResource(OctoIcons.Filter), contentDescription = null, tint = OctoColors.Accent, modifier = Modifier.size(size * 0.42f))
    }
}

// A live list among the playlists: its mark, its name, and "Live list".
@Composable
fun LiveListLine(list: LiveList, onClick: () -> Unit, onLongClick: () -> Unit) {
    PlaylistLine(list.name, LIVE_LIST, onClick, onLongClick = onLongClick) { LiveMarkTile(56.dp) }
}

// The line for making a live list, under New playlist, saying what one is.
@Composable
fun NewLiveListLine(onClick: () -> Unit) {
    PlaylistLine("New live list", "Songs picked by rules, always up to date", onClick) { LiveMarkTile(56.dp) }
}

const val LIVE_LIST = "Live list"

// A ready-made live list, offered while the listener has none.
@Composable
internal fun StarterLine(starter: LiveListStarter, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LiveMarkTile(44.dp)
        Column(Modifier.weight(1f)) {
            Text(starter.name, style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(starter.detail, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
