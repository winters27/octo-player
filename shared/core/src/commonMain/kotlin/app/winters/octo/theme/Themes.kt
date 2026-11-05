package app.winters.octo.theme

import kotlinx.serialization.Serializable

// The look the whole app is drawn in. Stored by name, so a name never
// changes once shipped; `light` says which Match slot a theme fills.
@Serializable
enum class ThemeChoice(val label: String, val light: Boolean) {
    Octo("Octo", false),
    Moss("Moss", false),
    Dusk("Dusk", false),
    Daylight("Daylight", true),
    Paper("Paper", true);

    companion object {
        // The theme stored under `name`, or null for an unknown or newer one.
        fun named(name: String?): ThemeChoice? = entries.firstOrNull { it.name == name }
    }
}

// What is stored: the theme shown while Match is off, the light and dark
// themes Match uses, and whether Match is on.
data class ThemeSettings(
    val theme: ThemeChoice = ThemeChoice.Octo,
    val light: ThemeChoice = ThemeChoice.Daylight,
    val dark: ThemeChoice = ThemeChoice.Octo,
    val match: Boolean = false,
)

// The stored names read back. An unknown name, or a theme of the wrong
// kind in a slot, reads as that field's default.
fun themeSettings(theme: String?, light: String?, dark: String?, match: Boolean) = ThemeSettings(
    theme = ThemeChoice.named(theme) ?: ThemeChoice.Octo,
    light = ThemeChoice.named(light)?.takeIf { it.light } ?: ThemeChoice.Daylight,
    dark = ThemeChoice.named(dark)?.takeIf { !it.light } ?: ThemeChoice.Octo,
    match = match,
)

// The theme showing: with Match on, the slot for the device's mode.
fun activeTheme(settings: ThemeSettings, deviceDark: Boolean): ThemeChoice = when {
    !settings.match -> settings.theme
    deviceDark -> settings.dark
    else -> settings.light
}

// Picking a theme makes it the theme and fills the slot of its kind, in
// one change, so the two never disagree.
fun pickTheme(settings: ThemeSettings, choice: ThemeChoice): ThemeSettings =
    if (choice.light) settings.copy(theme = choice, light = choice) else settings.copy(theme = choice, dark = choice)

// Match on or off. Turning it off keeps the theme that is showing.
fun setMatch(settings: ThemeSettings, on: Boolean, deviceDark: Boolean): ThemeSettings =
    if (on) settings.copy(match = true) else settings.copy(theme = activeTheme(settings, deviceDark), match = false)

// The line under the Match switch.
fun matchHelp(settings: ThemeSettings): String =
    "By day: ${settings.light.label}. By night: ${settings.dark.label}."
