package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.winters.octo.design.LineSlider
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.OctoType
import app.winters.octo.design.SliderLook
import app.winters.octo.design.Txt
import app.winters.octo.design.glassPanel

// The parts the Settings and Sound pages are made of.

private val CardShape = RoundedCornerShape(16.dp)

// A group of settings on a glass card.
@Composable
internal fun SettingsCard(title: String, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(bottom = 18.dp).glassPanel(CardShape).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(title, OctoType.section, modifier = Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

// A fact and its value.
@Composable
internal fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Txt(label, OctoType.bodySmall, OctoColors.TextMuted, Modifier.width(160.dp))
        Txt(value, OctoType.bodySmall, maxLines = 2, modifier = Modifier.weight(1f))
    }
}

// A setting that is on or off, with a line about what it does.
@Composable
internal fun SwitchLine(title: String, detail: String, on: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Txt(label, OctoType.bodySmall, modifier = Modifier.width(150.dp))
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
