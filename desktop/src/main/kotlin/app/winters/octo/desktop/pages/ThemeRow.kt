package app.winters.octo.desktop.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FrameSize
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoTooltip
import app.winters.octo.design.SeparatorColor
import app.winters.octo.design.SettingsSize
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.themes
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.ResolvedPalette
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.matchHelp
import app.winters.octo.theme.resolvePalette
import kotlinx.coroutines.flow.MutableStateFlow

// The Theme card's rows: the five themes as small pages to pick from, and
// Match the system under them.
@Composable
internal fun ThemeRows(app: AppState, settings: AppSettings) {
    val themes = settings.appearance.themes
    val showing by app.palette.collectAsState()
    ThemeRow(themes.match, themes.light, themes.dark, showing.theme, settings.appearance.accent) { choice -> app.settings.pickTheme(choice) }
    val heard by (app.portal?.answered ?: Heard).collectAsState()
    val unsaid = app.os == DesktopOs.Linux && app.portal != null && !heard
    SwitchRow(MATCH_THE_SYSTEM, matchHelp(themes) + if (unsaid) " $NO_DESKTOP_ANSWER" else "", themes.match) { on ->
        app.settings.setThemeMatch(on, app.theme.deviceDark.value)
    }
}

// Nothing to wait for when there is no portal to ask.
private val Heard = MutableStateFlow(true)

const val THEME_CHOICE = "Theme"
const val MATCH_THE_SYSTEM = "Match the system"

private const val NO_DESKTOP_ANSWER = "Your desktop hasn't said whether it's light or dark, so Octo uses the night theme."

// One row of five theme cards, each a small page in the theme's own colors
// over its name. The one showing sits in an accent ring with its name on the
// accent; with Match on, the Day and Night themes carry a tag.
@Composable
internal fun ThemeRow(
    match: Boolean,
    day: ThemeChoice,
    night: ThemeChoice,
    shown: ThemeChoice,
    accent: AccentChoice,
    onPick: (ThemeChoice) -> Unit,
) {
    Row(
        Modifier.settingsRow(THEME_CHOICE).fillMaxWidth().padding(horizontal = RowInset, vertical = Space.L).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        ThemeChoice.entries.forEach { choice ->
            val tag = when {
                !match -> null
                choice == day -> "Day"
                choice == night -> "Night"
                else -> null
            }
            OctoTooltip(choice.label, Modifier.weight(1f)) {
                ThemeCard(choice, resolvePalette(choice, accent, null, false), choice == shown, tag, { onPick(choice) })
            }
        }
    }
}

// What the tests call a theme card.
internal fun themeTag(choice: ThemeChoice) = "theme:${choice.name}"

@Composable
private fun ThemeCard(choice: ThemeChoice, look: ResolvedPalette, chosen: Boolean, tag: String?, onPick: () -> Unit) {
    val shape = Corner.PanelShape
    val ring = if (chosen) OctoColors.Accent else SeparatorColor
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(themeTag(choice))
            .ringOutside(Corner.Panel, OctoColors.TextPrimary)
            .clip(shape)
            .border(if (chosen) SettingsSize.PictureRing else FrameSize.Hairline, ring, shape)
            .hoverLift(shape, lifted = false)
            .selectable(selected = chosen, interactionSource = null, indication = null, role = Role.RadioButton, onClick = onPick)
            .semantics { contentDescription = choice.label },
    ) {
        Box(Modifier.fillMaxWidth().height(SettingsSize.PictureThumb)) {
            ThemePage(look)
            if (tag != null) Txt(tag, DesktopType.meta.copy(fontWeight = FontWeight.Medium), Color(look.text), Modifier.align(Alignment.TopEnd).padding(Space.S))
        }
        Row(
            Modifier.fillMaxWidth().background(if (chosen) OctoColors.Accent else Color.Transparent).padding(horizontal = Space.M, vertical = Space.S),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(choice.label, NameWords, if (chosen) OctoColors.OnAccent else OctoColors.TextPrimary, Modifier.weight(1f), maxLines = 2)
            if (chosen) ChosenCheck(OctoColors.OnAccent)
        }
    }
}

private val NameWords = DesktopType.meta.copy(fontWeight = FontWeight.Medium)

// A page in `look`: its page color, a card on it with two lines of words,
// and three dots in its accent, its complement and its third color.
@Composable
private fun ThemePage(look: ResolvedPalette) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(look.page))
        val unit = size.height / PAGE_UNITS
        val left = 12 * unit
        val corner = CornerRadius(5 * unit)
        drawRoundRect(Color(look.card), Offset(left, 12 * unit), Size(size.width - 2 * left, 34 * unit), corner)
        drawRoundRect(Color(look.edge), Offset(left, 12 * unit), Size(size.width - 2 * left, 34 * unit), corner, style = Stroke(unit))
        drawRoundRect(Color(look.text), Offset(left + 8 * unit, 20 * unit), Size(size.width * 0.34f, 5 * unit), CornerRadius(2.5f * unit), alpha = 0.85f)
        drawRoundRect(Color(look.secondary), Offset(left + 8 * unit, 31 * unit), Size(size.width * 0.22f, 4 * unit), CornerRadius(2 * unit), alpha = 0.7f)
        val dots = listOf(look.accents.accent, look.complement, look.third)
        val radius = 5 * unit
        val gap = 4 * unit
        val start = (size.width - (dots.size * 2 * radius + (dots.size - 1) * gap)) / 2 + radius
        dots.forEachIndexed { i, dot -> drawCircle(Color(dot), radius, Offset(start + i * (2 * radius + gap), 62 * unit)) }
    }
}

// The page's height in the drawing's own units, which its parts are
// measured in.
private const val PAGE_UNITS = 78f
