package app.winters.octo.desktop.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.ui.LocalKeyColour
import app.winters.octo.desktop.ui.keyRim
import app.winters.octo.design.LineSlider
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.OctoType
import app.winters.octo.design.SeparatorColor
import app.winters.octo.design.SliderLook
import app.winters.octo.design.Txt

// The parts the Settings and Sound pages are made of.

private val CardShape = RoundedCornerShape(16.dp)

// A group of settings: its name above, then its lines on a glass card
// lifted off the page, a hairline between each line, as the phone's
// settings group them.
@Composable
internal fun SettingsCard(title: String, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val key = LocalKeyColour.current
    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(bottom = 28.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(title, OctoType.section, modifier = Modifier.weight(1f))
            trailing()
        }
        Column(
            Modifier.fillMaxWidth().settingsSurface(CardShape, key).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

// The card's surface: a clearer glass than the page's other panels, with
// a contour, a soft shadow, light along the top, and a rim with a fifth of
// the key colour in it.
internal fun Modifier.settingsSurface(shape: Shape, key: Color): Modifier = this
    .dropShadow(shape, Shadow(radius = 0.dp, spread = 0.5.dp, color = Color.Black.copy(alpha = 0.40f)))
    .dropShadow(shape, Shadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.20f), offset = DpOffset(0.dp, 6.dp)))
    .dropShadow(shape, Shadow(radius = 20.dp, color = Color.Black.copy(alpha = 0.10f)))
    .clip(shape)
    .background(CardFill)
    .innerShadow(shape, Shadow(radius = 0.dp, spread = 1.dp, color = keyRim(key, alpha = 0.10f)))
    .innerShadow(shape, Shadow(radius = 0.dp, color = Color.White.copy(alpha = 0.08f), offset = DpOffset(0.dp, 1.dp)))

private val CardFill = Color.White.copy(alpha = 0.06f)

// A line of a card: a hairline above it unless it is the card's first,
// and with `hover`, a faint wash of the key colour under the pointer.
@Composable
internal fun Modifier.cardLine(hover: Boolean = false): Modifier {
    var first by remember { mutableStateOf(false) }
    val key = LocalKeyColour.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    return this
        .onPlaced { first = it.positionInParent().y < 1f }
        .then(if (hover) Modifier.hoverable(interaction) else Modifier)
        .drawBehind {
            if (hover && hovered) {
                // A little past the line's ends, inside the card's margin.
                val out = 10.dp.toPx()
                drawRoundRect(key.copy(alpha = 0.10f), Offset(-out, 2.dp.toPx()), Size(size.width + out * 2, size.height - 4.dp.toPx()), CornerRadius(10.dp.toPx()))
            }
            if (!first) drawRect(SeparatorColor, Offset(0f, -3.dp.toPx()), Size(size.width, 1f))
        }
}

// A fact and its value.
@Composable
internal fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().cardLine().padding(vertical = 10.dp)) {
        Txt(label, OctoType.bodySmall, OctoColors.TextMuted, Modifier.width(160.dp))
        Txt(value, OctoType.bodySmall, maxLines = 2, modifier = Modifier.weight(1f))
    }
}

// A setting that is on or off, with a line about what it does. The whole
// line flips it.
@Composable
internal fun SwitchLine(title: String, detail: String, on: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .cardLine(hover = true)
            .toggleable(value = on, role = Role.Switch, onValueChange = change)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, OctoType.bodySmall)
            Txt(detail, OctoType.caption, OctoColors.TextMuted, maxLines = 2)
        }
        OctoSwitch(on, change)
    }
}

// A value on a line: its name, the line, and what it reads now. `live`
// sends the value while dragging; `onRelease` hears when a change is done.
@Composable
internal fun SliderLine(
    label: String,
    reading: String,
    fraction: Float,
    onChange: (Float) -> Unit,
    live: Boolean = true,
    onRelease: () -> Unit = {},
    wheelStep: Float? = null,
) {
    Row(Modifier.fillMaxWidth().cardLine().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Txt(label, OctoType.bodySmall, modifier = Modifier.width(176.dp))
        LineSlider(
            fraction = { fraction },
            onSeek = onChange,
            modifier = Modifier.weight(1f),
            live = live,
            onRelease = onRelease,
            wheelStep = wheelStep,
            look = SliderLook.Jewel,
        )
        Txt(reading, OctoType.caption.copy(fontFeatureSettings = "tnum"), OctoColors.TextSecondary, Modifier.width(72.dp))
    }
}
