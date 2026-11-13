package app.winters.octo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.winters.octo.design.ArrowKeys
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FocusVisibility
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconSize
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.ProvideOctoTheme
import app.winters.octo.design.ProvideWindowLook
import app.winters.octo.design.SettingsSize
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.desktop.pages.AccentPill
import app.winters.octo.desktop.pages.RowAction
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.HeldAtStart
import app.winters.octo.desktop.settings.SettingsOpen
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.system.OctopusArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// What the window says for each thing that can hold Octo at the start.
internal const val SETTINGS_LOCKED_TITLE = "Settings are locked"
internal const val SETTINGS_LOCKED_TEXT = "Another program is using Octo's settings file. Octo opens as soon as it is free."
internal const val SETTINGS_NO_ACCESS_TITLE = "Settings can't be read"
internal const val SETTINGS_NO_ACCESS_TEXT = "Octo isn't allowed to read its settings file. Give your account access to it, and Octo opens by itself."
internal const val SETTINGS_DAMAGED_TITLE = "Settings file is damaged"
internal const val SETTINGS_DAMAGED_TEXT = "Octo can't read its settings file or rename it. Move it out of its folder, and Octo starts fresh."
internal const val SETTINGS_OPEN_WITHOUT = "Open without my settings"

internal fun settingsHeldTitle(why: HeldAtStart): String = when (why) {
    HeldAtStart.InUse -> SETTINGS_LOCKED_TITLE
    HeldAtStart.NoAccess -> SETTINGS_NO_ACCESS_TITLE
    HeldAtStart.Damaged -> SETTINGS_DAMAGED_TITLE
}

internal fun settingsHeldText(why: HeldAtStart): String = when (why) {
    HeldAtStart.InUse -> SETTINGS_LOCKED_TEXT
    HeldAtStart.NoAccess -> SETTINGS_NO_ACCESS_TEXT
    HeldAtStart.Damaged -> SETTINGS_DAMAGED_TEXT
}

// Whether the window names the file: only when the person has to do
// something to it.
internal fun settingsHeldNamesFile(why: HeldAtStart): Boolean = why != HeldAtStart.InUse

// How often the window looks at the file again by itself.
internal const val LOCKED_RETRY_MS = 1_000L

// While the settings file holds Octo from the start (another program has it,
// this account may not read it, or it is damaged and cannot be moved aside),
// Octo waits in this small window, in an application of its own that runs
// before the app's: it never opens on its defaults while the file is there.
// It looks again every second by itself, and on Try again; the first read
// that works gives the real settings. Open without my settings gives a
// session on the defaults that saves nothing. Null when the window is
// closed, which quits.
fun waitForSettings(held: SettingsOpen.Held): SettingsStore? {
    var opened: SettingsStore? = null
    application(exitProcessOnExit = false) {
        val state = rememberWindowState(size = DpSize(LockedWidth, Dp.Unspecified), position = WindowPosition(Alignment.Center))
        Window(onCloseRequest = ::exitApplication, state = state, title = "Octo", resizable = false) {
            LaunchedEffect(Unit) {
                val icons = withContext(Dispatchers.IO) { runCatching { OctopusArt.windowIcons() }.getOrDefault(emptyList()) }
                if (icons.isNotEmpty()) window.iconImages = icons
            }
            ProvideOctoTheme(paletteFor(AppSettings(), deviceDark = false)) {
                ProvideWindowLook(reduceMotion = false, focus = remember { FocusVisibility() }, arrows = remember { ArrowKeys() }) {
                    // The window wraps its words, so it fits them again
                    // when they change.
                    SettingsLocked(held, onHeldChange = { window.pack() }) { store ->
                        opened = store
                        exitApplication()
                    }
                }
            }
        }
    }
    return opened
}

// As wide as a sheet, and its words inside its margins.
private val LockedWidth = SettingsSize.Sheet
private val LockedTextWidth = LockedWidth - Space.Xl * 2

// The window's page: its title and words for what holds Octo, the file when
// the person has to act on it, and its two ways on. `onHeldChange` hears
// what holds Octo once it is drawn, and again whenever that changes;
// `onOpen` hears once, with the real settings or the session that saves
// nothing.
@Composable
internal fun SettingsLocked(
    held: SettingsOpen.Held,
    retryEveryMs: Long = LOCKED_RETRY_MS,
    onHeldChange: (HeldAtStart) -> Unit = {},
    onOpen: (SettingsStore) -> Unit,
) {
    var waiting by remember { mutableStateOf(held) }
    var done by remember { mutableStateOf(false) }
    fun open(store: SettingsStore) {
        if (done) return
        done = true
        onOpen(store)
    }
    // A look takes a moment (a small file, or a refusal at once), so it runs
    // on this thread, where the button's look runs too.
    fun look() {
        if (done) return
        when (val next = waiting.retry()) {
            is SettingsOpen.Opened -> open(next.store)
            is SettingsOpen.Held -> waiting = next
        }
    }
    LaunchedEffect(Unit) {
        while (!done) {
            delay(retryEveryMs)
            look()
        }
    }
    val why = waiting.why
    LaunchedEffect(why) {
        withFrameNanos { }
        onHeldChange(why)
    }
    Column(
        Modifier.fillMaxWidth().background(OctoColors.Background).padding(horizontal = Space.Xl, vertical = Space.Xl),
        verticalArrangement = Arrangement.spacedBy(Space.L),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            // Down a touch, onto the middle of the title's capitals.
            Glyph(if (why == HeldAtStart.Damaged) OctoIcons.Info else OctoIcons.Lock, Modifier.offset(y = Space.Xxs), size = IconSize.Toolbar, tint = OctoColors.Accent)
            Txt(settingsHeldTitle(why), DesktopType.section, OctoColors.TextPrimary, Modifier.semantics { heading() })
        }
        // The words a sentence to a line, so no line ends on a word or two,
        // with the file close under them, marked as a place by its folder.
        Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Txt(settingsHeldText(why).replace(". ", ".\n"), DesktopType.table, OctoColors.TextSecondary, Modifier.width(LockedTextWidth), maxLines = 6)
            if (settingsHeldNamesFile(why)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                    Glyph(OctoIcons.Folder, size = IconSize.Mark, tint = OctoColors.TextMuted)
                    SelectionContainer {
                        Txt(waiting.file.path, DesktopType.meta, OctoColors.TextMuted, Modifier.width(LockedTextWidth - IconSize.Mark - Space.S), maxLines = 6)
                    }
                }
            }
        }
        Row(Modifier.padding(top = Space.S), horizontalArrangement = Arrangement.spacedBy(Space.M), verticalAlignment = Alignment.CenterVertically) {
            AccentPill(SETTINGS_TRY_AGAIN, ::look)
            RowAction(SETTINGS_OPEN_WITHOUT, { open(SettingsStore.readOnly()) })
        }
    }
}
