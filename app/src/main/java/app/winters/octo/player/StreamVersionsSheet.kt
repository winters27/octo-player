package app.winters.octo.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.design.GlassPopup
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoDuration
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoInk
import app.winters.octo.design.OctoType
import app.winters.octo.design.SelectedFill
import app.winters.octo.design.Spinner
import app.winters.octo.design.motionScale
import app.winters.octo.design.octoTween
import app.winters.octo.subsonic.PLAYS_FROM_STREAM
import app.winters.octo.subsonic.STREAM_VERSION_AUTO
import app.winters.octo.subsonic.StreamVersion
import app.winters.octo.subsonic.StreamVersionPlaying
import app.winters.octo.ui.common.FormDivider
import app.winters.octo.ui.common.FormError
import app.winters.octo.ui.common.FormGroupCard
import app.winters.octo.ui.common.FormNote
import app.winters.octo.ui.common.QuietButton
import app.winters.octo.ui.common.QuietButtonInset
import app.winters.octo.ui.menu.SongMenuState
import app.winters.octo.versions.StreamVersionWords
import app.winters.octo.versions.StreamVersionsSheetState
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val RowShape = RoundedCornerShape(14.dp)
private val PillShape = RoundedCornerShape(50)

// How tall the soft edges of the list are.
private val ListFade = 28.dp

// What a fade's mask draws where the list shows: only its alpha counts.
private val Shown = Color.Black

// The slot at a row's end that the playing row's check and a checked row's
// spinner share, so their right edges and middles meet.
private val MarkSlot = 18.dp

// The playing row's note: who picked it, read as a tag, not one more fact.
private val NoteStyle = OctoType.caption.copy(fontWeight = FontWeight.SemiBold)

// Try another version: the YouTube videos the song playing, one found
// online, can stream from. The top line names the sheet, with Done on its
// capitals, and the song sits under it. The video playing sits in the
// darker pill with a check, and says who picked it; the other versions
// follow on a raised card, each a tap to play it from its start. The list
// scrolls between the top and the footer, a layer of its own that stays:
// what a choice does, and Go back to Octo's pick while a person's pick
// stands. It closes when the player moves to another song.
@Composable
fun StreamVersionsHost(menu: SongMenuState, backdrop: HazeState, model: StreamVersionsViewModel = hiltViewModel()) {
    val asked = menu.versionsFor
    LaunchedEffect(asked) {
        if (asked != null) {
            model.open(asked)
            menu.versionsTaken()
        }
    }
    val state by model.state.collectAsStateWithLifecycle()
    val shown by model.shown.collectAsStateWithLifecycle()
    GlassPopup(
        visible = state !is StreamVersionsSheetState.Closed,
        anchor = null,
        onDismiss = model::close,
        backdrop = backdrop,
        title = StreamVersionWords.TITLE,
        maxWidth = 440.dp,
        heightShare = 0.8f,
    ) {
        VersionsPanel(shown, model)
    }
}

@Composable
private fun VersionsPanel(sheet: StreamVersionsSheetState, model: StreamVersionsViewModel) {
    val song = sheet.song ?: return
    val side = Modifier.padding(horizontal = 16.dp)
    val motion = motionScale()
    val cap = rememberCapHeightPx(OctoType.section)
    // Grows and shrinks smoothly as the list arrives, so the top line
    // never jumps.
    Column(Modifier.fillMaxWidth().animateContentSize(octoTween(motion, OctoDuration.Card)).padding(top = 18.dp, bottom = 10.dp)) {
        // The title, whole, with Done on the middle of its capitals; the
        // song under it.
        Row(side.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                StreamVersionWords.TITLE,
                style = OctoType.section,
                color = OctoColors.TextPrimary,
                modifier = Modifier.weight(1f).alignBy { capMiddle(it[FirstBaseline].toFloat(), cap).roundToInt() }.semantics { heading() },
            )
            Box(Modifier.alignBy { it.measuredHeight / 2 }) { QuietButton(StreamVersionWords.DONE, model::close) }
        }
        Text(
            StreamVersionWords.songLine(song.title, song.artist),
            style = OctoType.formDescription,
            color = OctoColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = side.padding(start = 8.dp, top = 2.dp, bottom = 8.dp),
        )
        val scroll = rememberScrollState()
        Box(Modifier.weight(1f, fill = false)) {
            Column(Modifier.fillMaxWidth().softEdges(scroll).verticalScroll(scroll).padding(bottom = 8.dp)) {
                SheetBody(sheet, model, side)
            }
            MoreBelow(scroll, Modifier.align(Alignment.BottomCenter))
        }
        if (sheet is StreamVersionsSheetState.Shown && sheet.answer.playsFrom == PLAYS_FROM_STREAM) Footer(sheet, model)
    }
}

@Composable
private fun SheetBody(sheet: StreamVersionsSheetState, model: StreamVersionsViewModel, side: Modifier) {
    when (sheet) {
        StreamVersionsSheetState.Closed -> Unit
        is StreamVersionsSheetState.Looking -> Row(
            side.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spinner(size = 16.dp, color = OctoColors.TextSecondary)
            Text(StreamVersionWords.LOOKING, style = OctoType.bodySmall, color = OctoColors.TextSecondary)
        }
        is StreamVersionsSheetState.CouldNotReach -> {
            FormError(StreamVersionWords.stateLine(sheet).orEmpty(), side)
            Box(side.padding(start = 16.dp, top = 4.dp).offset(x = -QuietButtonInset)) { QuietButton(StreamVersionWords.TRY_AGAIN, model::retry) }
        }
        is StreamVersionsSheetState.Unavailable -> FormError(sheet.message, side)
        is StreamVersionsSheetState.Shown -> {
            sheet.playing?.let { playing ->
                SectionTitle(StreamVersionWords.PLAYING_NOW, side)
                PlayingRow(playing, StreamVersionWords.playingNote(playing, sheet.answer.heardVideoId), side)
            }
            if (sheet.picksOpen) {
                SectionTitle(StreamVersionWords.OTHER_VERSIONS, side)
                // The notice over the list sits under its name.
                sheet.notice?.let { FormNote(it, side.padding(bottom = 8.dp)) }
                sheet.emptyText?.let { FormNote(it, side) }
                if (sheet.rows.isNotEmpty()) {
                    FormGroupCard(side) {
                        sheet.rows.forEachIndexed { index, version ->
                            if (index > 0) FormDivider()
                            VersionRow(
                                version,
                                checking = sheet.checking == version.videoId,
                                refusal = sheet.refusal.takeIf { sheet.refused == version.videoId },
                                enabled = sheet.checking == null,
                            ) { model.pick(version.videoId) }
                        }
                    }
                }
            } else {
                // Busy, a file or a download: the server's words, no rows.
                sheet.notice?.let { FormNote(it, side) }
            }
        }
    }
}

// The video playing: the darker pill with a check on the middle of the
// title's capitals, its title (two lines at most), its channel, length and
// kind, then who picked it. Not a button.
@Composable
private fun PlayingRow(playing: StreamVersionPlaying, note: String, modifier: Modifier) {
    val detail = StreamVersionWords.rowDetail(playing.channel, playing.duration, playing.kind)
    val cap = rememberCapHeightPx(OctoType.bodySmall)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RowShape)
            .background(OctoColors.AccentSelected, RowShape)
            .semantics(mergeDescendants = true) { stateDescription = StreamVersionWords.PLAYING_NOW },
        contentAlignment = Alignment.CenterStart,
    ) {
        SelectedFill {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).alignBy { capMiddle(it[FirstBaseline].toFloat(), cap).roundToInt() }) {
                    Text(playing.title ?: playing.videoId, style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (detail.isNotEmpty()) Text(detail, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(note, style = NoteStyle, color = OctoColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(MarkSlot).alignBy { it.measuredHeight / 2 }, contentAlignment = Alignment.Center) {
                    Icon(painterResource(OctoIcons.Check), contentDescription = null, tint = Color.White, modifier = Modifier.size(MarkSlot))
                }
            }
        }
    }
}

// Another version: its title (two lines at most), then its channel, length
// and kind. A tap plays it from its start; while a pick is being checked,
// "Checking" takes that row's detail line and a spinner its end slot, so
// nothing moves, and the others wait, dimmed. The server's words for a pick
// it turned down show under its row as an error.
@Composable
private fun VersionRow(
    version: StreamVersion,
    checking: Boolean,
    refusal: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val detail = StreamVersionWords.rowDetail(version.channel, version.duration, version.kind)
    val cap = rememberCapHeightPx(OctoType.bodySmall)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .alpha(if (enabled || checking) 1f else OctoInk.DisabledAlpha)
            .clip(RowShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = StreamVersionWords.TITLE, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).alignBy { capMiddle(it[FirstBaseline].toFloat(), cap).roundToInt() }) {
                Text(version.title, style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (checking) {
                    Text(StreamVersionWords.CHECKING, style = OctoType.caption, color = OctoColors.TextSecondary, maxLines = 1)
                } else if (detail.isNotEmpty()) {
                    Text(detail, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                refusal?.let { Text(it, style = OctoType.caption, color = OctoColors.ErrorWords, modifier = Modifier.padding(top = 2.dp)) }
            }
            Box(Modifier.size(MarkSlot).alignBy { it.measuredHeight / 2 }, contentAlignment = Alignment.Center) {
                if (checking) Spinner(size = MarkSlot, color = OctoColors.TextSecondary)
            }
        }
    }
}

// The footer, a layer of its own under a full line: what a choice does, and
// the way back to Octo's own pick while a person's pick, or one from
// before, stands, its words on the note's edge. Always in view.
@Composable
private fun Footer(sheet: StreamVersionsSheetState.Shown, model: StreamVersionsViewModel) {
    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(1.dp).background(OctoColors.TextPrimary.copy(alpha = 0.12f)))
    Column(
        Modifier
            .fillMaxWidth()
            .background(OctoColors.TextPrimary.copy(alpha = 0.05f))
            .padding(horizontal = 32.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(StreamVersionWords.NOTE, style = OctoType.caption, color = OctoColors.TextSecondary)
        if (sheet.canUndo) {
            Row(Modifier.offset(x = -QuietButtonInset), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton(StreamVersionWords.GO_BACK, enabled = sheet.checking == null, onClick = model::undo)
                if (sheet.checking == STREAM_VERSION_AUTO) Spinner(size = MarkSlot, color = OctoColors.TextSecondary)
            }
            if (sheet.refused == STREAM_VERSION_AUTO) sheet.refusal?.let { Text(it, style = OctoType.caption, color = OctoColors.ErrorWords) }
        }
    }
}

// Soft edges on the list: what runs past the bottom (or up past the top)
// fades out over ListFade instead of being cut, only while there is more of
// the list that way.
private fun Modifier.softEdges(scroll: ScrollState): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = ListFade.toPx()
        val above = (scroll.value / fade).coerceIn(0f, 1f)
        if (above > 0f) {
            drawRect(
                Brush.verticalGradient(0f to Shown.copy(alpha = 1f - above), 1f to Shown, endY = fade),
                size = Size(size.width, fade),
                blendMode = BlendMode.DstIn,
            )
        }
        val below = ((scroll.maxValue - scroll.value) / fade).coerceIn(0f, 1f)
        if (below > 0f) {
            drawRect(
                Brush.verticalGradient(0f to Shown, 1f to Shown.copy(alpha = 1f - below), startY = size.height - fade, endY = size.height),
                topLeft = Offset(0f, size.height - fade),
                size = Size(size.width, fade),
                blendMode = BlendMode.DstIn,
            )
        }
    }

// A "More below" pill at the list's foot while more of it is below; a tap
// scrolls to its end. Gone once the end shows.
@Composable
private fun MoreBelow(scroll: ScrollState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val motion = motionScale()
    AnimatedVisibility(scroll.canScrollForward, modifier, enter = fadeIn(octoTween(motion, OctoDuration.Card)), exit = fadeOut(octoTween(motion, OctoDuration.Card))) {
        Row(
            Modifier
                .padding(bottom = 4.dp)
                .background(OctoColors.AccentSelected, PillShape)
                .clickable(role = Role.Button, onClickLabel = StreamVersionWords.MORE_BELOW) { scope.launch { scroll.animateScrollTo(scroll.maxValue) } }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(StreamVersionWords.MORE_BELOW, style = OctoType.caption.copy(fontWeight = FontWeight.SemiBold), color = OctoColors.TextPrimary)
            Icon(painterResource(OctoIcons.Collapse), contentDescription = null, tint = OctoColors.TextPrimary, modifier = Modifier.size(14.dp))
        }
    }
}

// A group's name, set as the form groups' titles are, with more room above
// it than between two rows.
@Composable
private fun SectionTitle(text: String, modifier: Modifier) {
    Text(
        text,
        style = OctoType.formGroup,
        color = OctoColors.TextPrimary,
        modifier = modifier.padding(start = 4.dp, end = 14.dp, top = 20.dp, bottom = 9.dp).semantics { heading() },
    )
}
