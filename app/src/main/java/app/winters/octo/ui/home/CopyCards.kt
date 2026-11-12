package app.winters.octo.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.winters.octo.design.AccentButton
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.Spinner
import app.winters.octo.design.glassPanel
import app.winters.octo.player.capMiddle
import app.winters.octo.player.rememberCapHeightPx
import app.winters.octo.server.COPY_DONE_TITLE
import app.winters.octo.server.COPY_FAILED_TITLE
import app.winters.octo.server.COPY_NOTICE_ASK
import app.winters.octo.server.COPY_SAVING_TEXT
import app.winters.octo.server.COPY_SAVING_TITLE
import app.winters.octo.server.COPY_STEPS
import app.winters.octo.server.CopyOutcome
import app.winters.octo.server.CopyRunState
import app.winters.octo.server.CopyStep
import app.winters.octo.server.StepState
import app.winters.octo.server.copyCount
import app.winters.octo.server.copyDescription
import app.winters.octo.server.copyDoing
import app.winters.octo.server.copyDoneText
import app.winters.octo.server.copyHeroText
import app.winters.octo.server.copyStepLabel
import app.winters.octo.server.copyStoppedAt
import app.winters.octo.server.copyTitle
import app.winters.octo.server.stepState
import app.winters.octo.ui.common.CopyRing
import app.winters.octo.ui.common.InCaption
import app.winters.octo.ui.common.QuietButton
import app.winters.octo.ui.common.QuietButtonInset
import app.winters.octo.ui.common.QuietButtonSpill
import kotlin.math.roundToInt

// Digits that keep their width, so a count ticking up never shifts.
private val Counted = OctoType.caption.copy(fontFeatureSettings = "tnum")

// Centered words broken into even lines, never one word left alone.
private val Balanced = OctoType.bodySmall.copy(lineBreak = LineBreak.Heading)

private val CardShape = RoundedCornerShape(20.dp)

@Composable
private fun Modifier.copyPanel() = padding(horizontal = 20.dp).padding(bottom = 12.dp).fillMaxWidth().glassPanel(CardShape)

// A first copy on its way, filling an empty Home: the ring around the
// cover that just came, what it is doing, and its steps. `onAsk` offers a
// notification for while it runs, when one would not show yet.
@Composable
internal fun CopyHero(card: HomeCopyCard.Coming, onAsk: (() -> Unit)?) {
    val run = card.run
    val saving = run?.step == CopyStep.Save
    val still = LocalReduceMotion.current
    // The ask line sits a little closer to the card's foot, and closes
    // softly once answered.
    val bottom by animateDpAsState(if (onAsk != null) 14.dp else 20.dp, if (still) snap() else spring(), label = "copy hero foot")
    Column(Modifier.copyPanel().padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = bottom), horizontalAlignment = Alignment.CenterHorizontally) {
        CopyRing(run, 112.dp)
        Spacer(Modifier.height(16.dp))
        CopyTitle(if (saving) COPY_SAVING_TITLE else copyTitle(arriving = true), run, card.server, OctoType.headline, TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            if (saving) COPY_SAVING_TEXT else copyHeroText(card.server),
            style = Balanced,
            color = OctoColors.TextMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        CopySteps(run, card.server, Modifier.fillMaxWidth())
        AnimatedVisibility(
            onAsk != null,
            enter = if (still) EnterTransition.None else expandVertically() + fadeIn(),
            exit = if (still) ExitTransition.None else shrinkVertically() + fadeOut(),
        ) {
            NoticeAsk(onAsk ?: {}, Modifier.fillMaxWidth().padding(top = 22.dp))
        }
    }
}

// The same above the shelves, while the phone's own music already shows.
@Composable
internal fun CopyBanner(card: HomeCopyCard.Coming) {
    val run = card.run
    val saving = run?.step == CopyStep.Save
    Row(Modifier.copyPanel().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        CopyRing(run, 56.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CopyTitle(if (saving) COPY_SAVING_TITLE else copyTitle(arriving = true), run, card.server, OctoType.body, TextAlign.Start)
            Text(
                run?.let(::copyDescription) ?: copyHeroText(card.server),
                style = Counted.copy(fontSize = OctoType.bodySmall.fontSize),
                color = OctoColors.TextMuted,
            )
        }
    }
}

// The music arrived: how much, and from where, while likes and playlists
// finish. Dismiss, on the title's line, puts it away. Once the steps after
// the copy end, only the card's foot closes; nothing above it moves.
@Composable
internal fun CopyDone(card: HomeCopyCard.Here, onDismiss: () -> Unit) {
    val still = LocalReduceMotion.current
    val titleCap = rememberCapHeightPx(OctoType.body)
    val captionCap = rememberCapHeightPx(OctoType.caption)
    Row(Modifier.copyPanel().padding(20.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(OctoColors.AccentTonal),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(OctoIcons.Check), contentDescription = null, tint = OctoColors.Accent, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row {
                Text(
                    COPY_DONE_TITLE,
                    style = OctoType.body,
                    color = OctoColors.TextPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .alignBy { capMiddle(it[FirstBaseline].toFloat(), titleCap).roundToInt() }
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                Box(Modifier.alignBy { it.measuredHeight / 2 }.lineHigh(QuietButtonSpill).offset(x = QuietButtonInset)) {
                    QuietButton("Dismiss", onClick = onDismiss)
                }
            }
            Text(copyDoneText(card.songs, card.albums, card.server), style = OctoType.bodySmall, color = OctoColors.TextMuted)
            AnimatedVisibility(
                card.settling,
                enter = if (still) EnterTransition.None else fadeIn(),
                exit = if (still) ExitTransition.None else fadeOut() + shrinkVertically(),
            ) {
                Row(Modifier.padding(top = 4.dp)) {
                    Spinner(Modifier.alignBy { it.measuredHeight / 2 }, size = 12.dp, color = OctoColors.TextMuted)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        copyDoing(CopyStep.Settle, card.server),
                        style = OctoType.caption,
                        color = OctoColors.TextMuted,
                        modifier = Modifier.alignBy { capMiddle(it[FirstBaseline].toFloat(), captionCap).roundToInt() },
                    )
                }
            }
            if (!card.settling && card.stepsProblem != null) {
                Text(card.stepsProblem, style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// Lays a control out one line tall, its padding spilling evenly above and below.
private fun Modifier.lineHigh(spill: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = spill.roundToPx()
    layout(placeable.width, (placeable.height - 2 * shift).coerceAtLeast(0)) { placeable.place(0, -shift) }
}

// The first copy stopped before its music arrived: why, where, and Try again.
@Composable
internal fun CopyFailed(card: HomeCopyCard.Stopped, onTryAgain: () -> Unit) {
    val outcome = card.outcome
    val where = copyStoppedAt(outcome.step, outcome.done)
    if (card.big) {
        Column(Modifier.copyPanel().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CopyRing(null, 112.dp, stopped = true)
            Spacer(Modifier.height(16.dp))
            Text(
                COPY_FAILED_TITLE,
                style = OctoType.headline,
                color = OctoColors.TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(6.dp))
            Text(listOfNotNull(outcome.reason, where).joinToString(" "), style = Balanced, color = OctoColors.TextMuted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            CopySteps(null, outcome.server, Modifier.fillMaxWidth(), stopped = outcome)
            Spacer(Modifier.height(18.dp))
            AccentButton("Try again", onClick = onTryAgain)
        }
        return
    }
    Column(Modifier.copyPanel().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CopyRing(null, 56.dp, stopped = true)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    COPY_FAILED_TITLE,
                    style = OctoType.body,
                    color = OctoColors.TextPrimary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(listOfNotNull(outcome.reason, where).joinToString(" "), style = OctoType.bodySmall, color = OctoColors.TextMuted)
            }
        }
        AccentButton("Try again", onClick = onTryAgain, size = ButtonSize.Medium, modifier = Modifier.padding(start = 70.dp, top = 12.dp))
    }
}

// The card's title, which says aloud what the copy is doing whenever the
// step changes (never on every count).
@Composable
private fun CopyTitle(title: String, run: CopyRunState?, server: String, style: TextStyle, align: TextAlign) {
    val doing = copyDoing(run?.step ?: CopyStep.Connect, server)
    Text(
        title,
        style = style,
        color = OctoColors.TextPrimary,
        textAlign = align,
        modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = "$title. $doing"
        },
    )
}

// The copy's steps, each done, under way or still to come, with the count
// of the one under way at its end (or under its name when both do not
// fit). Read as one line. `stopped` marks where a failed copy stopped.
@Composable
internal fun CopySteps(run: CopyRunState?, server: String, modifier: Modifier = Modifier, stopped: CopyOutcome.Failed? = null) {
    val at = run?.step ?: stopped?.step ?: CopyStep.Connect
    val description = when {
        run != null -> copyDescription(run)
        stopped != null -> listOfNotNull(COPY_FAILED_TITLE, copyStoppedAt(stopped.step, stopped.done)).joinToString(". ")
        else -> copyDoing(CopyStep.Connect, server)
    }
    Column(modifier.clearAndSetSemantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (step in COPY_STEPS) {
            val state = stepState(step, at)
            val count = if (state == StepState.Now && run != null) copyCount(run) else null
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepMark(state, moving = run != null, stopped = stopped != null)
                Spacer(Modifier.width(12.dp))
                LabelAndCount(
                    label = {
                        Text(
                            copyStepLabel(step, server),
                            style = OctoType.bodySmall,
                            color = when (state) {
                                StepState.Now -> OctoColors.TextPrimary
                                StepState.Done -> OctoColors.TextSecondary
                                StepState.Later -> OctoColors.TextMuted
                            },
                        )
                    },
                    count = count?.let { { Text(it, style = Counted, color = OctoColors.TextMuted, maxLines = 1) } },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// A step's mark: a check once done; a small turning arc while under way (a
// still dot with no copy running, a faint one where a copy stopped); a
// ring still to come.
@Composable
private fun StepMark(state: StepState, moving: Boolean, stopped: Boolean) {
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        when (state) {
            StepState.Done -> Icon(painterResource(OctoIcons.Check), contentDescription = null, tint = OctoColors.Accent, modifier = Modifier.size(16.dp))
            StepState.Now -> when {
                stopped -> Box(Modifier.size(7.dp).clip(CircleShape).background(OctoColors.TextMuted))
                moving -> Spinner(size = 14.dp, color = OctoColors.Accent)
                else -> Box(Modifier.size(7.dp).clip(CircleShape).background(OctoColors.Accent))
            }
            StepState.Later -> {
                val ring = OctoColors.TextMuted.copy(alpha = 0.5f)
                Canvas(Modifier.size(12.dp)) { drawCircle(ring, style = Stroke(1.5.dp.toPx())) }
            }
        }
    }
}

// A label with a count at the end of its line, or under it when the two
// do not fit side by side (large text on a narrow phone).
@Composable
private fun LabelAndCount(label: @Composable () -> Unit, count: (@Composable () -> Unit)?, modifier: Modifier = Modifier) {
    Layout(
        content = {
            label()
            count?.invoke()
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0)
        val labelPlace = measurables[0].measure(loose)
        val countPlace = measurables.getOrNull(1)?.measure(loose)
        val gap = 12.dp.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else labelPlace.width + gap + (countPlace?.width ?: 0)
        val sideBySide = countPlace == null || labelPlace.width + gap + countPlace.width <= width
        val height = when {
            countPlace == null -> labelPlace.height
            sideBySide -> maxOf(labelPlace.height, countPlace.height)
            else -> labelPlace.height + 2.dp.roundToPx() + countPlace.height
        }
        layout(width.coerceIn(constraints.minWidth, if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity), height) {
            labelPlace.placeRelative(0, (height - labelPlace.height).coerceAtLeast(0).let { if (sideBySide) it / 2 else 0 })
            if (countPlace != null) {
                if (sideBySide) {
                    countPlace.placeRelative(width - countPlace.width, (height - countPlace.height) / 2)
                } else {
                    countPlace.placeRelative(0, labelPlace.height + 2.dp.roundToPx())
                }
            }
        }
    }
}

// One quiet line offering a notification while the copy runs; Allow is
// set at the line's own size and ends on the content's edge.
@Composable
internal fun NoticeAsk(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(COPY_NOTICE_ASK, style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.weight(1f))
        Box(Modifier.offset(x = QuietButtonInset)) { QuietButton("Allow", enabled = true, style = InCaption, onClick = onAllow) }
    }
}
