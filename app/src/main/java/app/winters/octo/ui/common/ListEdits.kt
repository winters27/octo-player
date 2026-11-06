package app.winters.octo.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.player.SwipeAction
import kotlin.math.roundToInt

// What is behind a row as it is swiped away.
private val RemoveFill = OctoColors.Error.copy(alpha = 0.25f)

// Two short lines, the grip a row is dragged by.
@Composable
fun DragHandle(modifier: Modifier) {
    val ink = OctoColors.TextMuted
    Box(
        modifier.size(40.dp).semantics { contentDescription = "Reorder" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 18.dp, height = 8.dp)) {
            val stroke = 2.dp.toPx()
            listOf(stroke / 2, size.height - stroke / 2).forEach { y ->
                drawLine(ink, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
            }
        }
    }
}

// What is behind a song as it is swiped right, to play it next or add it
// to the queue.
private val QueueSwipeFill: Color @Composable @ReadOnlyComposable get() = OctoColors.Accent.copy(alpha = 0.22f)

// Shown behind a row being swiped right, over the strip the row has moved
// off (`revealed`, in pixels): a wash of the accent, and what the swipe does
// at the start. The far edge fades out, so the words never end in a hard
// cut where the row still covers them.
@Composable
fun QueueSwipeBackground(active: Boolean, action: SwipeAction, shape: Shape, revealed: () -> Float) {
    if (!active) return
    val icon = when (action) {
        SwipeAction.PlayNext -> OctoIcons.PlayNext
        SwipeAction.AddToQueue -> OctoIcons.AddToQueue
        SwipeAction.Off -> return
    }
    val fade = with(LocalDensity.current) { QueueSwipeFade.toPx() }
    Box(
        Modifier
            .fillMaxHeight()
            .layout { measurable, constraints ->
                val width = revealed().roundToInt().coerceIn(0, constraints.maxWidth)
                val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                layout(width, placeable.height) { placeable.place(0, 0) }
            }
            .clip(shape)
            .background(QueueSwipeFill)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val start = (size.width - fade).coerceAtLeast(0f)
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = start, endX = size.width),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        Row(
            Modifier.fillMaxHeight().wrapContentWidth(Alignment.Start, unbounded = true).padding(start = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = OctoColors.TextPrimary, modifier = Modifier.size(22.dp))
            Text(action.label, style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 1, softWrap = false)
        }
    }
}

// How far the wash's far edge fades into the row still covering it.
private val QueueSwipeFade = 24.dp

// Shown behind a row being swiped left: a wash of red and a bin at the end.
@Composable
fun RemoveBackground(active: Boolean, shape: Shape) {
    if (!active) return
    Box(
        Modifier.fillMaxSize().background(RemoveFill, shape).padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(painterResource(OctoIcons.Delete), contentDescription = "Remove", tint = OctoColors.TextPrimary, modifier = Modifier.size(22.dp))
    }
}
