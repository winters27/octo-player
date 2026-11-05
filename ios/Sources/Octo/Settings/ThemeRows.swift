import OctoKit
import SwiftUI

/// Appearance's theme picker: a card for each theme, three on the first line and two on the
/// second, each a small page in the theme's colors over its name. The theme showing carries a
/// ring and a name strip in the accent with a check; while Match the system is on, the two slot
/// themes carry a Day or Night tag.
struct ThemePickerRow: View {
    @State private var settings = AppSettings.shared
    @State private var theme = ThemeState.shared

    var body: some View {
        let themes = settings.player.themes
        let showing = theme.active
        RowFrame(entry: SettingsIndex.theme) {
            VStack(alignment: .leading, spacing: 10) {
                Text(SettingsIndex.theme.title).font(.octoBody).foregroundStyle(OctoColor.textPrimary)
                Grid(horizontalSpacing: 10, verticalSpacing: 10) {
                    GridRow {
                        ForEach(ThemeChoice.allCases.prefix(3), id: \.self) { card($0, themes, showing) }
                    }
                    GridRow {
                        ForEach(ThemeChoice.allCases.dropFirst(3), id: \.self) { card($0, themes, showing) }
                        Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])
                    }
                }
                .accessibilityElement(children: .contain)
                .accessibilityLabel(SettingsIndex.theme.title)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .sensoryFeedback(.selection, trigger: showing)
    }

    private func card(_ choice: ThemeChoice, _ themes: ThemeSettings, _ showing: ThemeChoice) -> some View {
        let tag: String? = themes.match ? (choice == themes.light ? "Day" : choice == themes.dark ? "Night" : nil) : nil
        return ThemeCard(choice: choice, selected: choice == showing, tag: tag) { settings.pickTheme(choice) }
    }
}

/// Match the system, under the picker, with the line naming the day and night themes.
struct ThemeMatchRow: View {
    @State private var settings = AppSettings.shared

    var body: some View {
        let themes = settings.player.themes
        ToggleRow(SettingsIndex.themeMatch, isOn: themes.match, helper: matchHelp(themes), onChange: settings.setThemeMatch)
    }
}

/// One theme to pick: a small page in its colors (its page, a card, and dots in its accent,
/// highlight and placeholder colors) over its name.
private struct ThemeCard: View {
    let choice: ThemeChoice
    let selected: Bool
    let tag: String?
    let pick: () -> Void

    @Environment(\.octoPalette) private var palette

    private static let looks: [ThemeChoice: ResolvedPalette] =
        Dictionary(uniqueKeysWithValues: ThemeChoice.allCases.map { ($0, resolvePalette($0, .octo, nil, false)) })

    var body: some View {
        let look = Self.looks[choice]!
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
        Button(action: pick) {
            VStack(spacing: 0) {
                miniPage(look)
                HStack(spacing: 4) {
                    if selected {
                        Image(systemName: "checkmark")
                            .font(.system(size: 11, weight: .bold))
                            .accessibilityHidden(true)
                    }
                    Text(choice.label)
                        .font(.octoLabel)
                        .lineLimit(2)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundStyle(selected ? Color(argb: palette.accents.onAccent) : palette.color(.textPrimary))
                .frame(maxWidth: .infinity, minHeight: 30)
                .padding(.horizontal, 6)
                .padding(.vertical, 4)
                .background(selected ? Color(argb: palette.accents.accent) : palette.color(.backgroundTertiary))
            }
            .clipShape(shape)
            .overlay(shape.strokeBorder(selected ? Color(argb: palette.accents.accent) : palette.color(.edge), lineWidth: selected ? 2 : 1))
            .contentShape(shape)
        }
        .buttonStyle(NoHighlight())
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tag.map { "\(choice.label), \($0)" } ?? choice.label)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    private func miniPage(_ look: ResolvedPalette) -> some View {
        ZStack(alignment: .topTrailing) {
            Color(argb: look.page)
            VStack(alignment: .leading, spacing: 6) {
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .fill(Color(argb: look.card))
                    .frame(height: 18)
                HStack(spacing: 4) {
                    ForEach(Array([look.accents.accent, look.complement, look.third].enumerated()), id: \.offset) { _, dot in
                        Circle().fill(Color(argb: dot)).frame(width: 8, height: 8)
                    }
                }
            }
            .padding(8)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            if let tag {
                Text(tag)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(Color(argb: look.text))
                    .padding(.horizontal, 5)
                    .padding(.vertical, 1)
                    .background(Color(argb: look.card), in: Capsule())
                    .padding(5)
            }
        }
        .frame(height: 56)
    }
}
