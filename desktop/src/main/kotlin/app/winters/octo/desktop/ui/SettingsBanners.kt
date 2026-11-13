package app.winters.octo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.winters.octo.design.AccentAction
import app.winters.octo.design.ControlHeight
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FrameSize
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconAction
import app.winters.octo.design.IconSize
import app.winters.octo.design.LocalOctoSurfaces
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.desktop.settings.SettingsHold
import app.winters.octo.desktop.settings.SettingsStore

// What Octo says when its settings file cannot be written or read.
internal const val SETTINGS_HELD_KEPT = "Octo can't save its settings right now. Your changes are kept and saved as soon as it can."
internal const val SETTINGS_HELD_UNKEPT = "Octo can't save its settings right now. Changes you make now may not be kept."
internal const val SETTINGS_READ_ONLY = "Settings are locked: changes you make now will not be saved."
internal const val SETTINGS_NOT_SAVED = "Settings not saved"
internal const val SETTINGS_TRY_AGAIN = "Try again"

internal fun settingsSetAsideWords(name: String) = "Octo couldn't read its settings file, so it started fresh. The old file is kept as $name"

// The words for a hold on the settings file, or null while it is free.
internal fun settingsHoldWords(hold: SettingsHold): String? = when (hold) {
    SettingsHold.Free -> null
    SettingsHold.Kept -> SETTINGS_HELD_KEPT
    SettingsHold.Unkept -> SETTINGS_HELD_UNKEPT
}

// One banner over the pages.
internal sealed interface SettingsBanner {
    val words: String

    // A session on the defaults that saves nothing.
    data object ReadOnly : SettingsBanner {
        override val words = SETTINGS_READ_ONLY
    }

    // The file is held; Try again writes it now.
    data class Held(val hold: SettingsHold) : SettingsBanner {
        override val words = settingsHoldWords(hold).orEmpty()
    }

    // An unreadable file was set aside and Octo started fresh; dismissed
    // once read.
    data class SetAside(val name: String) : SettingsBanner {
        override val words = settingsSetAsideWords(name)
    }
}

// The banners a store's state calls for, in the order they show.
internal fun settingsBanners(readOnly: Boolean, hold: SettingsHold, setAside: String?): List<SettingsBanner> = buildList {
    if (readOnly) add(SettingsBanner.ReadOnly)
    if (hold != SettingsHold.Free) add(SettingsBanner.Held(hold))
    setAside?.let { add(SettingsBanner.SetAside(it)) }
}

// The settings file's banners, across the top of every page of the main
// window while they apply: nothing at all while the file is fine. `side` is
// how far in the words start, the page's own margin.
@Composable
internal fun SettingsBanners(settings: SettingsStore, modifier: Modifier = Modifier, side: Dp = Space.Xl) {
    val hold by settings.hold.collectAsState()
    val aside by settings.setAside.collectAsState()
    val banners = settingsBanners(settings.readOnly, hold, aside)
    if (banners.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        banners.forEach { banner ->
            when (banner) {
                SettingsBanner.ReadOnly -> BannerBar(banner.words, OctoIcons.Lock, side)
                is SettingsBanner.Held -> BannerBar(banner.words, OctoIcons.Info, side, action = SETTINGS_TRY_AGAIN, onAction = settings::tryAgain)
                is SettingsBanner.SetAside -> BannerBar(banner.words, OctoIcons.Info, side, onClose = settings::dismissSetAside)
            }
        }
    }
}

// A banner: a bar across the page on the raised surface, a hairline under
// it, so it reads as the window speaking rather than one of the page's
// cards; a caution glyph, the words, then its action (the app's accent
// action, as a notice's Undo) or a cross to close it.
@Composable
private fun BannerBar(words: String, icon: ImageVector, side: Dp, action: String? = null, onAction: () -> Unit = {}, onClose: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().background(OctoColors.SurfaceRaised)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = ControlHeight.L + Space.M * 2)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = side, end = side, top = Space.S, bottom = Space.S),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.M),
        ) {
            Glyph(icon, size = IconSize.Toolbar, tint = cautionInk())
            Txt(words, DesktopType.table, OctoColors.TextPrimary, Modifier.weight(1f), maxLines = 3)
            if (action != null) AccentAction(action, onAction)
            if (onClose != null) IconAction(OctoIcons.Close, "Dismiss", onClose, size = ControlHeight.S, iconSize = IconSize.Inline, tint = OctoColors.TextSecondary)
        }
        Box(Modifier.fillMaxWidth().height(FrameSize.Hairline).background(OctoColors.Edge))
    }
}

// The caution amber, taken toward the words' ink on a light theme so the
// glyph keeps 3:1 against the bar.
@Composable
private fun cautionInk(): Color = if (LocalOctoSurfaces.current.light) lerp(OctoColors.SignalOrange, OctoColors.TextPrimary, CAUTION_ON_LIGHT) else OctoColors.SignalOrange

private const val CAUTION_ON_LIGHT = 0.4f
