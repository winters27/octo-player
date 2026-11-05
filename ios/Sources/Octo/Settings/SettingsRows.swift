import OctoKit
import SwiftUI
import UIKit

extension Font {
    /// The quiet label size: group titles and small headings.
    static let settingsLabel = Font.system(size: 13.5, weight: .semibold)
}

/// The icons the settings pages use, by what they stand for.
enum SettingsIcon {
    static let playback = "play.circle"
    static let sound = "slider.horizontal.3"
    static let appearance = "paintpalette"
    static let library = "rectangle.stack"
    static let cloud = "cloud"
    static let download = "arrow.down.circle"
    static let audioQuality = "slider.vertical.3"
    static let lyrics = "music.mic"
    static let scrobbling = "chart.line.uptrend.xyaxis"
    static let backup = "archivebox"
    static let info = "info.circle"
    static let phone = "iphone"
    static let radio = "dot.radiowaves.left.and.right"
    static let headphones = "headphones"
    static let cast = "airplay.audio"
    static let queue = "list.bullet"
    static let layout = "rectangle.3.group"
    static let home = "house"
    static let playlists = "music.note.list"
    static let sparkle = "sparkle"
    static let addToLibrary = "plus"
    static let folder = "folder"
    static let timer = "timer"
    static let wifi = "wifi"
    static let storage = "cylinder.split.1x2"
    static let listeners = "person.2.circle"
    static let account = "person.crop.circle"
    static let key = "key"
    static let sync = "arrow.triangle.2.circlepath"
    static let chevron = "chevron.right"
    static let search = "magnifyingglass"
    static let close = "xmark"
    static let check = "checkmark"
    static let liked = "heart.fill"
}

// MARK: Highlight of a searched-for row

/// The row a search result pointed at, lit briefly once its page opens. Shown once, then done,
/// so coming back to the page does not light it again.
struct SettingsHighlight {
    var id: String?
    var onShown: () -> Void = {}
    var scroll: (String) -> Void = { _ in }
}

private struct SettingsHighlightKey: EnvironmentKey {
    static var defaultValue: SettingsHighlight { SettingsHighlight() }
}

extension EnvironmentValues {
    var settingsHighlight: SettingsHighlight {
        get { self[SettingsHighlightKey.self] }
        set { self[SettingsHighlightKey.self] = newValue }
    }
}

/// How long the pointed-at row waits for the page to settle, and stays lit.
private let settleMs = 350
private let holdMs = 900

/// The accent wash a searched-for row wears for a moment.
private struct HighlightWash: ViewModifier {
    let entry: SettingEntry?
    @Environment(\.settingsHighlight) private var highlight
    @State private var glow = 0.0

    func body(content: Content) -> some View {
        Group {
            if let id = entry?.id {
                content.id(id)
            } else {
                content
            }
        }
        .background(OctoColor.accent.opacity(0.14 * glow))
        .task(id: highlight.id) {
            guard let entry, highlight.id == entry.id else { return }
            // Marking it shown clears the page's highlight, so the light runs on a task of its
            // own that this one ending does not stop.
            let scroll = highlight.scroll
            highlight.onShown()
            Task {
                try? await Task.sleep(for: .milliseconds(settleMs))
                withAnimation(.easeInOut(duration: 0.3)) { scroll(entry.id) }
                withAnimation(.easeOut(duration: 0.25)) { glow = 1 }
                try? await Task.sleep(for: .milliseconds(250 + holdMs))
                withAnimation(.easeInOut(duration: 0.7)) { glow = 0 }
            }
        }
    }
}

// MARK: Page frame

/// A settings page: the back button and the large title on one line, and its groups one under
/// another, 24 apart. `highlight` is the row a search result pointed at.
struct SettingsPageFrame<Content: View>: View {
    let title: String
    var highlight: String?
    @ViewBuilder let content: () -> Content
    /// Lit once per visit from search, not again on coming back to the page.
    @State private var shown = false

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    PageHeader(title)
                    VStack(spacing: 24) { content() }
                }
                .padding(.top, pageTopGap)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .scrollIndicators(.hidden)
            .barClearance()
            .environment(\.settingsHighlight, SettingsHighlight(
                id: shown ? nil : highlight,
                onShown: { shown = true },
                scroll: { id in proxy.scrollTo(id, anchor: .center) }
            ))
        }
        .detailPage(ownBack: true)
        .background { PageBackdrop(page: .settingsPage, route: nil) }
    }
}

// MARK: Group

/// Rows in one raised panel, a hairline between each. A row that draws nothing takes no place
/// and gets no line. The lines start under the text, so rows with icon tiles pass a deeper
/// inset. A title sits above the panel as a quiet capitalized label, led by the group's icon
/// (a brand mark keeps its own colors).
struct SettingsGroup<Content: View, Mark: View>: View {
    var title: String?
    var footer: String?
    var inset: CGFloat = 16
    @ViewBuilder var mark: () -> Mark
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let title {
                HStack(spacing: 7) {
                    mark()
                    Text(title.uppercased())
                        .font(.settingsLabel)
                        .tracking(0.6)
                        .foregroundStyle(OctoColor.textMuted)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(title)
                .accessibilityAddTraits(.isHeader)
            }
            Group(subviews: content()) { rows in
                if !rows.isEmpty {
                    VStack(spacing: 0) {
                        ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                            if index > 0 { Hairline(inset: inset) }
                            row
                        }
                    }
                    .raisedPanel()
                }
            }
            if let footer {
                Text(footer)
                    .font(.octoCaption)
                    .foregroundStyle(OctoColor.textMuted)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, 16)
    }
}

extension SettingsGroup where Mark == EmptyView {
    init(title: String? = nil, footer: String? = nil, inset: CGFloat = 16, @ViewBuilder content: @escaping () -> Content) {
        self.init(title: title, footer: footer, inset: inset, mark: { EmptyView() }, content: content)
    }
}

extension SettingsGroup where Mark == AnyView {
    /// A group led by a tinted icon.
    init(title: String, icon: String, footer: String? = nil, inset: CGFloat = 16, @ViewBuilder content: @escaping () -> Content) {
        self.init(title: title, footer: footer, inset: inset, mark: {
            AnyView(Image(systemName: icon).font(.system(size: 13)).frame(width: 15, height: 15).foregroundStyle(OctoColor.textMuted))
        }, content: content)
    }
}

/// One physical pixel, starting under the rows' text.
private struct Hairline: View {
    let inset: CGFloat
    @Environment(\.displayScale) private var scale

    var body: some View {
        Rectangle()
            .fill(OctoInkColor.quaternary)
            .frame(height: 1 / scale)
            .padding(.leading, inset)
    }
}

extension View {
    /// The raised panel settings rows sit in: a surface a step above the page, a faint rim and a
    /// light along its top edge, and a soft shadow under it.
    func raisedPanel(cornerRadius: CGFloat = 20) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        return self
            .background(OctoColor.surfaceRaised, in: shape)
            .clipShape(shape)
            .overlay(shape.strokeBorder(OctoColor.textPrimary.opacity(0.06), lineWidth: 1))
            .overlay(alignment: .top) {
                shape.stroke(OctoColor.textPrimary.opacity(0.06), lineWidth: 1).offset(y: 1).mask(shape).allowsHitTesting(false)
            }
            .octoShadow(0.20, radius: 1, y: 1)
            .octoShadow(0.12, radius: 3, y: 2)
    }
}

// MARK: Rows

/// What every row shares: its height and padding, and the brief light when search pointed at it.
struct RowFrame<Content: View>: View {
    var entry: SettingEntry?
    var minHeight: CGFloat = 56
    @ViewBuilder var content: () -> Content

    var body: some View {
        HStack(spacing: 12) { content() }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: minHeight, alignment: .leading)
            .contentShape(Rectangle())
            .modifier(HighlightWash(entry: entry))
    }
}

/// A title with an optional line under it, taking the row's spare width.
private struct RowLabel: View {
    let title: String
    var below: String?
    var enabled = true
    /// The title's and the line under it; nil takes the theme's primary and muted words.
    var titleColor: Color?
    var belowColor: Color?
    /// Every answer's own line, when the line under the title is the chosen answer's: the room under the
    /// title fits the tallest, so the row keeps one height and its title one place.
    var belowAnswers: [String] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.octoBody)
                .foregroundStyle(!enabled ? AnyShapeStyle(OctoColor.textMuted)
                                 : titleColor.map { AnyShapeStyle($0) } ?? AnyShapeStyle(OctoColor.textPrimary))
                .fixedSize(horizontal: false, vertical: true)
            if let below {
                ZStack(alignment: .topLeading) {
                    ForEach(Array(belowAnswers.enumerated()), id: \.offset) { _, line in
                        Text(line).font(.octoCaption).fixedSize(horizontal: false, vertical: true).hidden().accessibilityHidden(true)
                    }
                    Text(below).font(.octoCaption)
                        .foregroundStyle(belowColor.map { AnyShapeStyle($0) } ?? AnyShapeStyle(OctoColor.textMuted))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Which share of a row's spare width a part may take: the title 1, the value 0.8, and nothing set
/// for parts that keep their own size (a chevron, a spinner).
struct RowShare: LayoutValueKey {
    static let defaultValue: CGFloat? = nil
}

/// Every value a row can show, when the one shown is among them, for the narrower value its layout may pick.
struct RowAnswers: LayoutValueKey {
    static let defaultValue: [String] = []
}

/// A row's title and value side by side in the room the fixed parts leave. The value takes only
/// the room its words need, up to 0.8 to the title's 1 (or all the room the title leaves when the title
/// fits on one line beside it), as the other apps divide it, and the title takes the rest. Each wraps
/// within its width, so neither is ever cut short.
struct TitleValueLayout: Layout {
    var spacing: CGFloat = 12

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let widths = widths(proposal.width, subviews)
        var height: CGFloat = 0
        for (index, part) in subviews.enumerated() {
            height = max(height, part.sizeThatFits(ProposedViewSize(width: widths[index], height: nil)).height)
        }
        let used = widths.reduce(0, +) + spacing * CGFloat(max(subviews.count - 1, 0))
        return CGSize(width: proposal.width ?? used, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let widths = widths(bounds.width, subviews)
        var x = bounds.minX
        for (index, part) in subviews.enumerated() {
            let size = part.sizeThatFits(ProposedViewSize(width: widths[index], height: nil))
            part.place(at: CGPoint(x: x, y: bounds.midY), anchor: .leading, proposal: ProposedViewSize(width: widths[index], height: size.height))
            x += widths[index] + spacing
        }
    }

    private func widths(_ total: CGFloat?, _ subviews: Subviews) -> [CGFloat] {
        let ideal = subviews.map { $0.sizeThatFits(.unspecified).width }
        guard let total else { return ideal }
        let shares = subviews.map { $0[RowShare.self] }
        let fixed = zip(shares, ideal).reduce(CGFloat(0)) { $0 + ($1.0 == nil ? $1.1 : 0) }
        let spare = max(0, total - fixed - spacing * CGFloat(max(subviews.count - 1, 0)))
        let whole = shares.compactMap { $0 }.reduce(0, +)
        guard whole > 0 else { return zip(shares, ideal).map { $0 == nil ? $1 : 0 } }
        // A value (a share under 1) takes what its words need, up to its share, or up to all the room the
        // titles leave when they fit on one line beside it; titles share the rest.
        let titlesNeed = zip(shares, ideal).reduce(CGFloat(0)) { $0 + (($1.0 ?? 0) >= 1 ? $1.1 : 0) }
        let values: [CGFloat?] = zip(shares, ideal).map { share, width in
            guard let share, share < 1 else { return nil }
            return min(width, max(spare * share / whole, spare - titlesNeed))
        }
        let titleShares = zip(shares, values).reduce(CGFloat(0)) { $0 + ($1.1 == nil ? ($1.0 ?? 0) : 0) }
        let left = spare - values.compactMap { $0 }.reduce(0, +)
        let wide = subviews.indices.map { index in
            guard let share = shares[index] else { return ideal[index] }
            return values[index] ?? (titleShares > 0 ? left * share / titleShares : 0)
        }
        return narrower(wide, subviews, shares) ?? wide
    }

    /// The same row with its value narrower, its widest answers wrapping to two lines at a space: as wide as
    /// its 2nd widest answer, then its 3rd, and so on, taking the first step that takes a line off the title's
    /// side with the row no taller. Only for a row whose value shown is one of its answers (`RowAnswers` is
    /// empty otherwise), so it never depends on the answer shown: nothing moves between answers, and no wide
    /// empty gap sits beside a short value.
    private func narrower(_ wide: [CGFloat], _ subviews: Subviews, _ shares: [CGFloat?]) -> [CGFloat]? {
        guard let value = shares.firstIndex(where: { ($0 ?? 1) < 1 }) else { return nil }
        let font = Self.readingFont
        let answers = Array(Set(subviews[value][RowAnswers.self])).map { ($0, Self.oneLine($0, font)) }.sorted { $0.1 > $1.1 }
        let titles = shares.indices.filter { (shares[$0] ?? 0) >= 1 }
        guard answers.count >= 2, !titles.isEmpty else { return nil }
        func widths(at step: Double) -> [CGFloat] {
            var widths = wide
            widths[value] = CGFloat(step)
            for index in titles { widths[index] += (wide[value] - CGFloat(step)) / CGFloat(titles.count) }
            return widths
        }
        guard let step = narrowValueWidth(
            wide: Double(wide[value]), ones: answers.map { Double($0.1) }, twos: answers.map { Double(Self.twoLines($0.0, font)) },
            title: { Double(height(widths(at: $0), subviews, titles)) },
            value: { Double(height(widths(at: $0), subviews, subviews.indices.filter { !titles.contains($0) })) }
        ) else { return nil }
        return widths(at: step)
    }

    /// How tall the parts at `indices` stand at these widths.
    private func height(_ widths: [CGFloat], _ subviews: Subviews, _ indices: [Int]) -> CGFloat {
        indices.map { subviews[$0].sizeThatFits(ProposedViewSize(width: widths[$0], height: nil)).height }.max() ?? 0
    }

    /// UIKit's twin of `.octoBodySmall`, the font a value is drawn in.
    private static var readingFont: UIFont {
        UIFontMetrics(forTextStyle: .subheadline).scaledFont(for: .systemFont(ofSize: 14, weight: .regular))
    }

    private static func oneLine(_ text: String, _ font: UIFont) -> CGFloat {
        ceil((text as NSString).size(withAttributes: [.font: font]).width) + 1
    }

    /// The narrowest width that holds the text on two lines, broken at a space.
    private static func twoLines(_ text: String, _ font: UIFont) -> CGFloat {
        let words = text.split(separator: " ").map(String.init)
        guard words.count > 1 else { return oneLine(text, font) }
        return (1..<words.count).map { cut in
            max(oneLine(words[..<cut].joined(separator: " "), font), oneLine(words[cut...].joined(separator: " "), font))
        }.min() ?? oneLine(text, font)
    }
}

/// A row's current value on its right: quieter than the title, kept to the end of the line,
/// at most two lines, in the width its row gives it. Given `answers`, every one is laid under it
/// unseen, so the value is as wide as the widest and the row never changes height between them.
struct Reading: View {
    let value: String
    var answers: [String] = []
    var enabled = true

    var body: some View {
        ZStack(alignment: .trailing) {
            ForEach(Array(answers.enumerated()), id: \.offset) { _, answer in
                Text(answer).font(.octoBodySmall).multilineTextAlignment(.trailing).lineLimit(2).hidden().accessibilityHidden(true)
            }
            Text(value)
                .font(.octoBodySmall)
                .foregroundStyle(enabled ? OctoColor.textSecondary : OctoColor.textMuted)
                .multilineTextAlignment(.trailing)
                .lineLimit(2)
                .truncationMode(.tail)
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
    }
}

/// A setting that is on or off. The whole row flips it, and it reads once as a switch.
struct ToggleRow: View {
    let entry: SettingEntry?
    var title: String?
    let isOn: Bool
    var helper: String?
    var enabled = true
    let onChange: (Bool) -> Void

    init(_ entry: SettingEntry?, title: String? = nil, isOn: Bool, helper: String? = nil, enabled: Bool = true,
         onChange: @escaping (Bool) -> Void) {
        self.entry = entry
        self.title = title
        self.isOn = isOn
        self.helper = helper
        self.enabled = enabled
        self.onChange = onChange
    }

    var body: some View {
        RowFrame(entry: entry) {
            RowLabel(title: title ?? entry?.title ?? "", below: helper, enabled: enabled)
            Toggle("", isOn: Binding(get: { isOn }, set: { onChange($0) }))
                .labelsHidden()
                .tint(OctoColor.accent)
                .disabled(!enabled)
                // The row takes every tap, so one tap flips it once.
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .onTapGesture { if enabled { onChange(!isOn) } }
        .sensoryFeedback(.selection, trigger: isOn)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title ?? entry?.title ?? "")
        .accessibilityHint(helper ?? "")
        .accessibilityValue(isOn ? "On" : "Off")
        .accessibilityAddTraits(.isToggle)
        .accessibilityAction { if enabled { onChange(!isOn) } }
    }
}

/// A setting with a few answers: its title on the left, the current one on the right, and a
/// chevron. Tapping it opens the answers as a system menu beside the row.
struct ChoiceRow: View {
    let entry: SettingEntry?
    var title: String?
    let value: String
    var helper: String?
    /// What the row can show on its right, when the menu words its answers longer; the menu's labels otherwise.
    var values: [String]?
    var enabled = true
    let question: SettingsQuestion

    init(_ entry: SettingEntry?, title: String? = nil, value: String, helper: String? = nil, values: [String]? = nil, enabled: Bool = true,
         question: SettingsQuestion) {
        self.entry = entry
        self.title = title
        self.value = value
        self.helper = helper
        self.values = values
        self.enabled = enabled
        self.question = question
    }

    /// What the row can show on its right: `values`, or the menu's labels.
    private var shownAnswers: [String] { values ?? question.answers.map(\.label) }

    /// The answers' own lines, when the line under the title is the chosen answer's.
    private var answerLines: [String] {
        let lines = question.answers.compactMap(\.detail)
        guard let helper, lines.contains(helper) else { return [] }
        return lines
    }

    var body: some View {
        Menu {
            QuestionMenuItems(question: question)
        } label: {
            RowFrame(entry: entry) {
                TitleValueLayout {
                    RowLabel(title: title ?? entry?.title ?? "", below: helper, enabled: enabled, belowAnswers: answerLines).layoutValue(key: RowShare.self, value: 1)
                    Reading(value: value, answers: shownAnswers, enabled: enabled)
                        .layoutValue(key: RowShare.self, value: 0.8)
                        .layoutValue(key: RowAnswers.self, value: shownAnswers.contains(value) ? shownAnswers : [])
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.system(size: 13, weight: .semibold))
                        .frame(width: 18, height: 18)
                        .foregroundStyle(OctoColor.textMuted)
                        .opacity(enabled ? 1 : 0.5)
                        .accessibilityHidden(true)
                }
            }
        }
        .menuStyle(.button)
        .buttonStyle(SettingsRowPress())
        .menuOrder(.fixed)
        .disabled(!enabled)
        .accessibilityLabel(title ?? entry?.title ?? "")
        .accessibilityValue(value)
        .accessibilityHint(helper ?? "")
    }
}

/// A setting along a range: its title and value, and the line to drag.
struct SliderRow: View {
    let entry: SettingEntry?
    var title: String?
    let value: String
    let fraction: Double
    let onSeek: (Double) -> Void

    init(_ entry: SettingEntry?, title: String? = nil, value: String, fraction: Double, onSeek: @escaping (Double) -> Void) {
        self.entry = entry
        self.title = title
        self.value = value
        self.fraction = fraction
        self.onSeek = onSeek
    }

    var body: some View {
        RowFrame(entry: entry) {
            VStack(spacing: 0) {
                HStack(alignment: .firstTextBaseline) {
                    Text(title ?? entry?.title ?? "")
                        .font(.octoBody)
                        .foregroundStyle(OctoColor.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(value).font(.octoBodySmall.monospacedDigit()).foregroundStyle(OctoColor.textSecondary).fixedSize()
                }
                JewelSlider(fraction: fraction, label: title ?? entry?.title ?? "", valueText: value, onSeek: onSeek)
            }
        }
    }
}

/// Something to do, or another page to open: its title, and a chevron, a word on the right, or
/// a spinner while it works.
struct ActionRow: View {
    let entry: SettingEntry?
    var title: String?
    var helper: String?
    var trailing: String?
    var chevron: Bool
    var busy = false
    var enabled = true
    var destructive = false
    let action: () -> Void

    init(_ entry: SettingEntry?, title: String? = nil, helper: String? = nil, trailing: String? = nil, chevron: Bool? = nil,
         busy: Bool = false, enabled: Bool = true, destructive: Bool = false, action: @escaping () -> Void) {
        self.entry = entry
        self.title = title
        self.helper = helper
        self.trailing = trailing
        self.chevron = chevron ?? (trailing == nil)
        self.busy = busy
        self.enabled = enabled
        self.destructive = destructive
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            RowFrame(entry: entry) {
                TitleValueLayout {
                    RowLabel(title: title ?? entry?.title ?? "", below: helper, enabled: enabled,
                             titleColor: destructive ? OctoColor.error : nil)
                        .layoutValue(key: RowShare.self, value: 1)
                    if busy {
                        ProgressView().controlSize(.small).tint(OctoColor.accent).frame(width: 18, height: 18)
                    } else if let trailing {
                        Reading(value: trailing, enabled: enabled).layoutValue(key: RowShare.self, value: 0.8)
                    }
                    if chevron && !busy {
                        Image(systemName: SettingsIcon.chevron)
                            .font(.system(size: 14, weight: .semibold))
                            .frame(width: 20, height: 20)
                            .foregroundStyle(OctoColor.textMuted)
                            .opacity(enabled ? 1 : 0.5)
                            .accessibilityHidden(true)
                    }
                }
            }
        }
        .buttonStyle(SettingsRowPress())
        .disabled(!enabled || busy)
    }
}

/// A fact to read: a title and its value.
struct InfoRow: View {
    let entry: SettingEntry?
    var title: String?
    let value: String
    var helper: String?

    init(_ entry: SettingEntry?, title: String? = nil, value: String, helper: String? = nil) {
        self.entry = entry
        self.title = title
        self.value = value
        self.helper = helper
    }

    var body: some View {
        RowFrame(entry: entry) {
            TitleValueLayout {
                RowLabel(title: title ?? entry?.title ?? "", below: helper).layoutValue(key: RowShare.self, value: 1)
                Reading(value: value).layoutValue(key: RowShare.self, value: 0.8)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// A few words inside a group: what a part does, or what went wrong.
struct NoteRow: View {
    let text: String
    var entry: SettingEntry?
    /// The note's color; nil takes the theme's muted words.
    var color: AnyShapeStyle?

    init(_ text: String, entry: SettingEntry? = nil) {
        self.text = text
        self.entry = entry
    }

    init<S: ShapeStyle>(_ text: String, entry: SettingEntry? = nil, color: S) {
        self.text = text
        self.entry = entry
        self.color = AnyShapeStyle(color)
    }

    var body: some View {
        RowFrame(entry: entry, minHeight: 0) {
            Text(text).font(.octoCaption)
                .foregroundStyle(color ?? AnyShapeStyle(OctoColor.textMuted))
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// A row that opens a page on the tab's stack, drawn like an action row with a chevron.
struct LinkRow<Value: Hashable>: View {
    let entry: SettingEntry?
    var title: String?
    var helper: String?
    let value: Value

    init(_ entry: SettingEntry?, title: String? = nil, helper: String? = nil, to value: Value) {
        self.entry = entry
        self.title = title
        self.helper = helper
        self.value = value
    }

    var body: some View {
        NavigationLink(value: value) {
            RowFrame(entry: entry) {
                RowLabel(title: title ?? entry?.title ?? "", below: helper)
                Image(systemName: SettingsIcon.chevron)
                    .font(.system(size: 14, weight: .semibold))
                    .frame(width: 20, height: 20)
                    .foregroundStyle(OctoColor.textMuted)
                    .accessibilityHidden(true)
            }
        }
        .buttonStyle(SettingsRowPress())
    }
}

/// A way into a page: its icon, title, and one line on how it is set now. It opens a settings
/// page on the tab's stack, or runs `action` when given one instead.
struct CategoryRow: View {
    let icon: String
    let title: String
    let summary: String
    var route: CategoryDestination?
    var action: (() -> Void)?
    /// Marks a server update waiting on the page the row opens.
    var badged = false

    init(icon: String, title: String, summary: String, to route: SettingsRoute, badged: Bool = false) {
        self.icon = icon
        self.title = title
        self.summary = summary
        self.route = .settings(route)
        self.badged = badged
    }

    init(icon: String, title: String, summary: String, to route: Route) {
        self.icon = icon
        self.title = title
        self.summary = summary
        self.route = .app(route)
    }

    init(icon: String, title: String, summary: String, action: @escaping () -> Void) {
        self.icon = icon
        self.title = title
        self.summary = summary
        self.action = action
    }

    var body: some View {
        if case .settings(let route) = route {
            NavigationLink(value: route) { label }.buttonStyle(SettingsRowPress())
        } else if case .app(let route) = route {
            NavigationLink(value: route) { label }.buttonStyle(SettingsRowPress())
        } else {
            Button { action?() } label: { label }.buttonStyle(SettingsRowPress())
        }
    }

    private var label: some View {
        Group {
            RowFrame(entry: nil) {
                IconTile(icon: icon)
                VStack(alignment: .leading, spacing: 0) {
                    Text(title).font(.octoBody).foregroundStyle(OctoColor.textPrimary).fixedSize(horizontal: false, vertical: true)
                    if !summary.isEmpty {
                        Text(summary).font(.octoCaption).foregroundStyle(OctoColor.textMuted).lineLimit(1).truncationMode(.tail)
                    }
                }
                .padding(.leading, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
                if badged { ServerUpdateDot() }
                Image(systemName: SettingsIcon.chevron)
                    .font(.system(size: 14, weight: .semibold))
                    .frame(width: 20, height: 20)
                    .foregroundStyle(OctoColor.textMuted)
                    .accessibilityHidden(true)
            }
        }
    }
}

/// Where a category row goes: a settings page, or a page of the app's own routes.
enum CategoryDestination {
    case settings(SettingsRoute)
    case app(Route)
}

/// A row's icon on a small tinted tile, so a list of pages reads at a glance.
struct IconTile: View {
    let icon: String

    var body: some View {
        Image(systemName: icon)
            .font(.system(size: 17))
            .frame(width: 20, height: 20)
            .foregroundStyle(OctoColor.accent)
            .frame(width: 34, height: 34)
            .background(OctoColor.accent.opacity(0.14), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .accessibilityHidden(true)
    }
}

/// The inset for a group of rows with icon tiles: the lines start under the titles.
let iconRowInset: CGFloat = 66

/// A row under the finger takes a faint light, at once.
struct SettingsRowPress: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(OctoColor.textPrimary.opacity(configuration.isPressed ? 0.06 : 0))
            .contentShape(Rectangle())
    }
}

/// ListenBrainz's mark in its own navy and orange, never tinted.
struct ListenBrainzMark: View {
    var size: CGFloat = 15

    var body: some View {
        Canvas { context, box in
            let s = box.width / 30
            var left = Path()
            left.move(to: CGPoint(x: 14.5 * s, y: 1 * s))
            left.addLine(to: CGPoint(x: 2.5 * s, y: 8 * s))
            left.addLine(to: CGPoint(x: 2.5 * s, y: 22 * s))
            left.addLine(to: CGPoint(x: 14.5 * s, y: 29 * s))
            left.closeSubpath()
            var right = Path()
            right.move(to: CGPoint(x: 15.5 * s, y: 1 * s))
            right.addLine(to: CGPoint(x: 27.5 * s, y: 8 * s))
            right.addLine(to: CGPoint(x: 27.5 * s, y: 22 * s))
            right.addLine(to: CGPoint(x: 15.5 * s, y: 29 * s))
            right.closeSubpath()
            context.fill(left, with: .color(Color(hex: 0x353070)))
            context.fill(right, with: .color(Color(hex: 0xEB743B)))
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
