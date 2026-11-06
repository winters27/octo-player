package app.winters.octo.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.ThemeStore
import app.winters.octo.theme.activeTheme
import app.winters.octo.theme.matchHelp
import app.winters.octo.theme.resolvePalette
import app.winters.octo.ui.settings.rows.RowFrame
import app.winters.octo.ui.settings.rows.SwitchRow

// How many theme cards sit in a row.
private const val CARDS_PER_ROW = 3

private val CardShape = RoundedCornerShape(12.dp)
private val MiniPageHeight = 56.dp

// Appearance's theme card: a small page for each theme, three to a row, the
// one showing ringed in the accent with its name on an accent strip and a
// check, and under them the switch that follows the phone's light and dark.
// `onNameLayout` hears how each name was laid out.
@Composable
fun ThemePicker(
    store: ThemeStore,
    deviceDark: Boolean,
    onNameLayout: (ThemeChoice, TextLayoutResult) -> Unit = { _, _ -> },
) {
    val settings by store.settings.collectAsState()
    val active = activeTheme(settings, deviceDark)
    Column {
        RowFrame(SettingsIndex.Theme) {
            Column(Modifier.weight(1f)) {
                Text(SettingsIndex.Theme.title, style = OctoType.body, color = OctoColors.TextPrimary)
                Column(
                    Modifier.fillMaxWidth().padding(top = 12.dp).selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeChoice.entries.chunked(CARDS_PER_ROW).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { theme ->
                                ThemeCard(
                                    theme = theme,
                                    selected = theme == active,
                                    tag = when {
                                        !settings.match -> null
                                        theme == settings.light -> "Day"
                                        theme == settings.dark -> "Night"
                                        else -> null
                                    },
                                    onPick = { store.pick(theme) },
                                    onNameLayout = onNameLayout,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(CARDS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        SwitchRow(
            SettingsIndex.MatchSystem,
            checked = settings.match,
            onChange = { store.setMatch(it, deviceDark) },
            helper = matchHelp(settings),
        )
    }
}

// A check drawn as a stroke, the way the accent swatches draw theirs.
@Composable
private fun CheckMark(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val check = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.52f)
            lineTo(size.width * 0.42f, size.height * 0.76f)
            lineTo(size.width * 0.84f, size.height * 0.28f)
        }
        drawPath(check, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// One theme: its page in its own colors with a card on it and three dots
// (accent, second and third colors), and its name under.
@Composable
private fun ThemeCard(
    theme: ThemeChoice,
    selected: Boolean,
    tag: String?,
    onPick: () -> Unit,
    onNameLayout: (ThemeChoice, TextLayoutResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = remember(theme) { resolvePalette(theme, AccentChoice.Octo, null, false) }
    val ring = if (selected) OctoColors.Accent else OctoColors.Edge
    Column(
        modifier
            .clip(CardShape)
            .border(if (selected) 2.dp else 1.dp, ring, CardShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onPick)
            .semantics { contentDescription = theme.label },
    ) {
        Box(Modifier.fillMaxWidth().height(MiniPageHeight).background(Color(palette.page))) {
            Box(
                Modifier
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp)
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(palette.card))
                    .border(1.dp, Color(palette.edge), RoundedCornerShape(6.dp)),
            ) {
                if (tag != null) {
                    Text(
                        tag,
                        style = OctoType.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                        color = Color(palette.text),
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                    )
                }
            }
            Row(
                Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(palette.accents.accent, palette.complement, palette.third).forEach { dot ->
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(dot)))
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) OctoColors.Accent else OctoColors.SurfaceRaised)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (selected) CheckMark(OctoColors.OnAccent, Modifier.size(14.dp))
            Text(
                theme.label,
                style = OctoType.caption.copy(fontWeight = FontWeight.SemiBold),
                color = if (selected) OctoColors.OnAccent else OctoColors.TextPrimary,
                maxLines = 2,
                onTextLayout = { onNameLayout(theme, it) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
