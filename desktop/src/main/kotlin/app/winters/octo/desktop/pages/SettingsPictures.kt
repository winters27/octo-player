package app.winters.octo.desktop.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import app.winters.octo.animated.AnimatedArtSetting
import app.winters.octo.covers.PlaylistCoverStyle
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FrameSize
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconSize
import app.winters.octo.design.LocalOctoSurfaces
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.SeparatorColor
import app.winters.octo.design.SettingsSize
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.design.glassFilm
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.settings.AmbienceStyle

// Visual settings chosen by picture: the window's background, playlist
// covers and animated art. Each choice is a small drawing of what it does,
// the chosen one on the accent fill, ringed in the accent, with a check by
// its name.

// A setting chosen by picture: its name and line, then its choices side by
// side, at least three across so every picture is the same size.
@Composable
internal fun <T> PictureChoiceRow(
    title: String,
    caption: String?,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    picture: @Composable (T) -> Unit,
) {
    Column(Modifier.settingsRow(title).fillMaxWidth().padding(horizontal = RowInset, vertical = Space.L)) {
        Txt(title, RowTitleWords, OctoColors.TextPrimary, maxLines = 2)
        if (caption != null) Txt(caption, DesktopType.meta, OctoColors.TextMuted, Modifier.padding(top = Space.Xxs), maxLines = 3)
        Row(Modifier.fillMaxWidth().padding(top = Space.L).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Space.L)) {
            options.forEach { option ->
                PictureChoice(label(option), option == selected, { onSelect(option) }, Modifier.weight(1f)) { picture(option) }
            }
            repeat((PICTURES_ACROSS - options.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
        }
    }
}

private const val PICTURES_ACROSS = 3

// One choice: its drawing and its name. Chosen, it sits on the accent fill
// inside an accent ring, with a check after its name. The keyboard's ring
// is white and just outside the tile, so it never reads as a second choice.
@Composable
internal fun PictureChoice(text: String, chosen: Boolean, onPick: () -> Unit, modifier: Modifier = Modifier, picture: @Composable () -> Unit) {
    val ring = if (chosen) OctoColors.Accent else SeparatorColor
    val fill = if (chosen) OctoColors.AccentSelected else glassFilm(RESTING_SHADE)
    Column(
        modifier
            .testTag(pictureTag(text))
            .ringOutside(Corner.Panel, OctoColors.TextPrimary)
            .clip(Corner.PanelShape)
            .background(fill, Corner.PanelShape)
            .border(if (chosen) SettingsSize.PictureRing else FrameSize.Hairline, ring, Corner.PanelShape)
            .hoverLift(Corner.PanelShape, lifted = false)
            .selectable(selected = chosen, interactionSource = null, indication = null, role = Role.RadioButton, onClick = onPick)
            .semantics { contentDescription = text }
            .padding(Space.S),
    ) {
        Box(Modifier.fillMaxWidth().height(SettingsSize.PictureThumb).clip(Corner.ArtMShape)) { picture() }
        Row(
            Modifier.fillMaxWidth().padding(start = Space.Xs, end = Space.Xs, top = Space.M, bottom = Space.Xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(text, PictureWords, OctoColors.TextPrimary, Modifier.weight(1f))
            if (chosen) ChosenCheck(OctoColors.TextPrimary)
        }
    }
}

// The check after a chosen tile's name, in the name's color: the icon on a
// dark theme; on a light one drawn as a 2 px stroke, which a thin icon line
// on a light tile does not match.
@Composable
internal fun ChosenCheck(color: Color) {
    if (!LocalOctoSurfaces.current.light) {
        Glyph(OctoIcons.Check, size = IconSize.Table, tint = color)
        return
    }
    Canvas(Modifier.size(IconSize.Table)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.20f, h * 0.52f)
            lineTo(w * 0.42f, h * 0.73f)
            lineTo(w * 0.80f, h * 0.30f)
        }
        drawPath(path, color, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// What the tests call a picture choice.
internal fun pictureTag(text: String) = "picture:$text"

private val PictureWords = DesktopType.meta.copy(fontWeight = FontWeight.Medium)

private const val RESTING_SHADE = 0.2f

// The colors the drawings borrow from an example cover.
private class Example(val page: Color, ink: Color) {
    val blue = Color(0xFF5B7FA3)
    val rose = Color(0xFFB0627D)
    val amber = Color(0xFFD7A35E)
    val line = ink.copy(alpha = 0.12f)
    val bar = ink.copy(alpha = 0.10f)
}

@Composable
private fun example() = Example(OctoColors.Background, OctoColors.TextPrimary)

// The window's background: plain, a glow across the top, or the cover's
// colors behind everything.
@Composable
internal fun AmbiencePicture(style: AmbienceStyle?) {
    val ink = example()
    Canvas(Modifier.fillMaxSize()) {
        drawRect(ink.page)
        when (style) {
            null -> Unit
            AmbienceStyle.Glow -> {
                drawRect(Brush.horizontalGradient(listOf(ink.blue, ink.rose, ink.amber)), size = Size(size.width, size.height * 0.62f), alpha = 0.6f)
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, ink.page), startY = size.height * 0.08f, endY = size.height * 0.62f), size = Size(size.width, size.height * 0.62f))
            }
            AmbienceStyle.Immersive -> {
                glowAt(ink.blue, 0.25f, 0.4f, 0.6f)
                glowAt(ink.rose, 0.7f, 0.6f, 0.6f)
                glowAt(ink.amber, 0.6f, 0.15f, 0.5f)
            }
        }
        windowParts(ink)
    }
}

// How playlists are drawn: names on painted art, or a mosaic of album covers.
@Composable
internal fun PlaylistCoverPicture(style: PlaylistCoverStyle) {
    val ink = example()
    Canvas(Modifier.fillMaxSize()) {
        drawRect(ink.page)
        val side = size.height * 0.72f
        val gap = size.height * 0.1f
        val top = (size.height - side) / 2
        val first = (size.width - side * 2 - gap) / 2
        listOf(ink.blue to ink.rose, ink.amber to ink.blue).forEachIndexed { i, (a, b) ->
            val left = first + i * (side + gap)
            when (style) {
                PlaylistCoverStyle.Designed -> {
                    drawRoundRect(Brush.linearGradient(listOf(a, b), Offset(left, top), Offset(left + side, top + side)), Offset(left, top), Size(side, side), CornerRadius(side * 0.08f))
                    drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(left + side * 0.12f, top + side * 0.70f), Size(side * 0.56f, side * 0.09f), CornerRadius(side * 0.05f))
                }
                PlaylistCoverStyle.Mosaic -> {
                    val half = side / 2
                    listOf(a, b, ink.rose, ink.amber).forEachIndexed { at, color ->
                        drawRect(color, Offset(left + (at % 2) * half, top + (at / 2) * half), Size(half, half), alpha = if (at % 3 == 0) 1f else 0.75f)
                    }
                }
            }
        }
    }
}

// Where animated art plays: in the cover's square, filling the player, or
// not at all (the still cover).
@Composable
internal fun AnimatedArtPicture(setting: AnimatedArtSetting) {
    val ink = example()
    Canvas(Modifier.fillMaxSize()) {
        drawRect(ink.page)
        val art = Brush.linearGradient(listOf(ink.blue, ink.rose, ink.amber), Offset.Zero, Offset(size.width, size.height))
        when (setting) {
            AnimatedArtSetting.FullScreen -> {
                drawRect(art, alpha = 0.85f)
                motionLines(size.width * 0.1f, size.height * 0.2f, size.width * 0.8f)
            }
            AnimatedArtSetting.Square, AnimatedArtSetting.Off -> {
                val side = size.height * 0.58f
                val left = (size.width - side) / 2
                val top = size.height * 0.1f
                if (setting == AnimatedArtSetting.Square) {
                    drawRoundRect(art, Offset(left, top), Size(side, side), CornerRadius(side * 0.08f))
                    motionLines(left + side * 0.15f, top + side * 0.3f, side * 0.7f)
                } else {
                    drawRoundRect(ink.rose, Offset(left, top), Size(side, side), CornerRadius(side * 0.08f), alpha = 0.8f)
                }
            }
        }
        val unit = size.height / THUMB_UNITS
        drawRoundRect(ink.bar, Offset(8 * unit, size.height - 14 * unit), Size(size.width - 16 * unit, 6 * unit), CornerRadius(3 * unit))
    }
}

// The thumbnail's height in the drawing's own units, which its parts are
// measured in.
private const val THUMB_UNITS = 78f

// A soft round light of `color` centered at a share of the picture.
private fun DrawScope.glowAt(color: Color, x: Float, y: Float, reach: Float) {
    val center = Offset(size.width * x, size.height * y)
    val radius = size.width * reach
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.75f), Color.Transparent), center, radius), radius, center)
}

// A page's words and the player bar, as faint shapes.
private fun DrawScope.windowParts(ink: Example) {
    val unit = size.height / THUMB_UNITS
    val left = 8 * unit
    drawRoundRect(ink.line, Offset(left, 14 * unit), Size(size.width * 0.46f, 5 * unit), CornerRadius(3 * unit))
    drawRoundRect(ink.line, Offset(left, 26 * unit), Size(size.width * 0.30f, 5 * unit), CornerRadius(3 * unit))
    drawRoundRect(ink.bar, Offset(left, size.height - 18 * unit), Size(size.width - 16 * unit, 10 * unit), CornerRadius(5 * unit))
}

// Three short arcs of light across art, for art that moves.
private fun DrawScope.motionLines(left: Float, top: Float, width: Float) {
    val unit = size.height / THUMB_UNITS
    repeat(3) { i ->
        drawRoundRect(
            Color.White.copy(alpha = 0.35f - i * 0.08f),
            Offset(left + i * width * 0.12f, top + i * 7 * unit),
            Size(width * (0.7f - i * 0.15f), 2.5f * unit),
            CornerRadius(1.25f * unit),
        )
    }
}
