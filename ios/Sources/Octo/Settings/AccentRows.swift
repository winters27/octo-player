import OctoKit
import SwiftUI

/// Appearance's accent picker: a circle for each accent, in the form the theme showing draws it,
/// and one for the playing cover's. The first is the theme's own color, labeled Theme. The one in
/// use carries a check and a ring, and a line names it and says what it colors in this theme.
struct AccentPickerRow: View {
    @State private var settings = AppSettings.shared
    @State private var theme = ThemeState.shared

    var body: some View {
        let chosen = settings.player.accent
        let showing = theme.active
        RowFrame(entry: SettingsIndex.accentColor) {
            VStack(alignment: .leading, spacing: 0) {
                Text(SettingsIndex.accentColor.title).font(.octoBody).foregroundStyle(OctoColor.textPrimary)
                FlowRow(spacing: 2) {
                    ForEach(AccentChoice.allCases, id: \.self) { choice in
                        AccentSwatch(choice: choice, theme: showing, selected: choice == chosen) { settings.setAccent(choice) }
                    }
                }
                .padding(.top, 8)
                .accessibilityElement(children: .contain)
                .accessibilityLabel(SettingsIndex.accentColor.title)
                Text("\(chosen.label): \(accentHelp(chosen, showing))")
                    .font(.octoCaption)
                    .foregroundStyle(OctoColor.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 6)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .sensoryFeedback(.selection, trigger: chosen)
    }
}

/// One accent to pick, a 44 point target round a colored circle in the theme's form of it. From
/// the album art is drawn as every other accent round its circle.
private struct AccentSwatch: View {
    let choice: AccentChoice
    let theme: ThemeChoice
    let selected: Bool
    let pick: () -> Void

    @Environment(\.octoPalette) private var palette

    var body: some View {
        Button(action: pick) {
            ZStack {
                if selected {
                    Circle().strokeBorder(palette.color(.textPrimary), lineWidth: 2).frame(width: 40, height: 40)
                }
                fill.frame(width: 30, height: 30)
                if selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(Color(argb: palette.accents.onAccent))
                }
            }
            .frame(width: 44, height: 44)
            .contentShape(Circle())
        }
        .buttonStyle(NoHighlight())
        .accessibilityLabel(choice.label)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    @ViewBuilder
    private var fill: some View {
        if choice.argb != nil {
            Circle().fill(Color(argb: themedAccent(theme, choice, nil)))
        } else {
            let spectrum = AccentChoice.fixed.dropFirst().map { Color(argb: themedAccent(theme, $0, nil)) }
            Circle().fill(AngularGradient(colors: spectrum + [spectrum[0]], center: .center))
        }
    }
}

/// Whether Octo's glass takes the accent too, under the picker. Off, the glass stays Apple's own
/// untinted glass; on, it takes the accent in the theme's form. With the theme's own accent there
/// is nothing to tint it with, and the row says so.
struct GlassTintRow: View {
    @State private var settings = AppSettings.shared

    var body: some View {
        let prefs = settings.player
        ToggleRow(SettingsIndex.glassTint, isOn: prefs.glassTint && prefs.accent != .octo, helper: GlassTintWords.helper(prefs.accent),
                  enabled: prefs.accent != .octo, onChange: settings.setGlassTint)
    }
}
