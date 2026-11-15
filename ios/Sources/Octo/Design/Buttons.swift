import OctoKit
import SwiftUI

/// A control pressed in: it shrinks to `scale` over the press time, after the motion scale.
struct PressScale: ButtonStyle {
    var scale = OctoPress.scale
    @Environment(\.motionScale) private var motion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? motion.scale(scale) : 1)
            .animation(.octoTween(OctoDuration.press, scale: motion), value: configuration.isPressed)
    }
}

/// A large surface held down, like a row or a card: it gives a little, to 0.985, over half the
/// squeeze time, and springs back.
struct SqueezeStyle: ButtonStyle {
    @Environment(\.motionScale) private var motion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? motion.scale(OctoPress.largeScale) : 1)
            .animation(.octoTween(OctoDuration.squeeze / 2, scale: motion), value: configuration.isPressed)
    }
}

/// A button's words, with an optional icon 8 points before them. While `loading` the words
/// keep their room, so the width never jumps, and a spinner turns in their place.
struct ButtonLabel: View {
    let text: String
    var icon: OctoIcon?
    var size: ButtonSize = .large
    var loading = false
    /// The words and icon; nil takes the theme's words.
    var color: Color?
    @Environment(\.octoPalette) private var palette
    /// The icon grows and shrinks with the text size, as the words beside it do.
    @ScaledMetric(relativeTo: .footnote) private var iconScale: CGFloat = 1

    var body: some View {
        let ink = color ?? palette.color(.textPrimary)
        ZStack {
            HStack(spacing: 8) {
                if let icon { IconView(icon, size: CGFloat(size.iconSize) * iconScale, color: ink) }
                Text(text)
                    .font(.octo(CGFloat(size.fontSize), .semibold, .footnote))
                    .foregroundStyle(ink)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .opacity(loading ? 0 : 1)
            if loading { Spinner(size: CGFloat(size.iconSize), color: ink) }
        }
        .padding(.horizontal, CGFloat(size.padding))
    }
}

/// The filled button: a glass capsule in the accent's darker fill, white words. Large by
/// default (50 tall, 40 dense). Disabled it shows at half strength and cannot be tapped; it
/// cannot be tapped while loading either. `bright` fills it with the accent itself, dark words
/// on it, for the one main button of a sheet.
struct AccentButton: View {
    let text: String
    var icon: OctoIcon?
    var size: ButtonSize = .large
    var dense = false
    var tonal = false
    var bright = false
    var enabled = true
    var loading = false
    var fillWidth = false
    let action: () -> Void
    @Environment(\.octoPalette) private var palette

    init(_ text: String, icon: OctoIcon? = nil, size: ButtonSize = .large, dense: Bool = false, tonal: Bool = false,
         bright: Bool = false, enabled: Bool = true, loading: Bool = false, fillWidth: Bool = false, action: @escaping () -> Void) {
        self.text = text
        self.icon = icon
        self.size = size
        self.dense = dense
        self.tonal = tonal
        self.bright = bright
        self.enabled = enabled
        self.loading = loading
        self.fillWidth = fillWidth
        self.action = action
    }

    var body: some View {
        let fill = bright ? palette.accents.accent : tonal ? palette.accents.tonal : palette.accents.fill
        Button(action: action) {
            ButtonLabel(text: text, icon: icon, size: size, loading: loading, color: Color(argb: contentArgbFor(fill)))
                .frame(maxWidth: fillWidth ? .infinity : nil)
                .frame(height: CGFloat(dense ? size.dense : size.height))
                .contentShape(Capsule())
        }
        .buttonStyle(PressScale())
        .octoGlass(Capsule(), film: Color(argb: fill), interactive: true)
        .opacity(enabled ? 1 : OctoInkColor.disabled)
        .disabled(!enabled || loading)
    }
}

/// The quieter button: lit glass with the theme's words. `tint` lays a fifth of a color into its film.
struct GlazeButton: View {
    let text: String
    var icon: OctoIcon?
    var size: ButtonSize = .large
    var dense = false
    var tint: Color?
    var enabled = true
    var loading = false
    var fillWidth = false
    let action: () -> Void

    init(_ text: String, icon: OctoIcon? = nil, size: ButtonSize = .large, dense: Bool = false, tint: Color? = nil,
         enabled: Bool = true, loading: Bool = false, fillWidth: Bool = false, action: @escaping () -> Void) {
        self.text = text
        self.icon = icon
        self.size = size
        self.dense = dense
        self.tint = tint
        self.enabled = enabled
        self.loading = loading
        self.fillWidth = fillWidth
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            ButtonLabel(text: text, icon: icon, size: size, loading: loading)
                .frame(maxWidth: fillWidth ? .infinity : nil)
                .frame(height: CGFloat(dense ? size.dense : size.height))
                .contentShape(Capsule())
        }
        .buttonStyle(PressScale())
        .octoGlass(Capsule(), film: tint.map { $0.opacity(0.2) }, interactive: true)
        .opacity(enabled ? 1 : OctoInkColor.disabled)
        .disabled(!enabled || loading)
    }
}

/// A question's two answers, the main one first and the other (Cancel) second: side by side while
/// both fit at their own widths, the main one widened toward the other's width as far as the row
/// allows, so it never reads as the lesser answer; stacked at the row's full width, the main one on
/// top, where they do not fit side by side (long words, or large text on a narrow phone), so neither
/// is cut. Each answer is drawn to the width it is given (`fillWidth`).
struct AnswerRow: Layout {
    var gap: CGFloat = 10

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard subviews.count == 2 else { return .zero }
        let placed = placement(proposal, subviews)
        let main = subviews[0].sizeThatFits(ProposedViewSize(width: placed.main, height: nil))
        let other = subviews[1].sizeThatFits(ProposedViewSize(width: placed.other, height: nil))
        if placed.stacked { return CGSize(width: placed.main, height: main.height + gap + other.height) }
        return CGSize(width: placed.main + gap + placed.other, height: max(main.height, other.height))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.count == 2 else { return }
        let placed = placement(proposal, subviews)
        let mainOffer = ProposedViewSize(width: placed.main, height: nil)
        let otherOffer = ProposedViewSize(width: placed.other, height: nil)
        let main = subviews[0].sizeThatFits(mainOffer)
        let other = subviews[1].sizeThatFits(otherOffer)
        if placed.stacked {
            subviews[0].place(at: CGPoint(x: bounds.minX, y: bounds.minY), proposal: mainOffer)
            subviews[1].place(at: CGPoint(x: bounds.minX, y: bounds.minY + main.height + gap), proposal: otherOffer)
        } else {
            let height: CGFloat = max(main.height, other.height)
            subviews[0].place(at: CGPoint(x: bounds.minX, y: bounds.minY + (height - main.height) / 2), proposal: mainOffer)
            subviews[1].place(at: CGPoint(x: bounds.minX + placed.main + gap, y: bounds.minY + (height - other.height) / 2),
                              proposal: otherOffer)
        }
    }

    /// Where the two go in the width offered, from each one's own width.
    private func placement(_ proposal: ProposedViewSize, _ subviews: Subviews) -> (stacked: Bool, main: CGFloat, other: CGFloat) {
        let room: Double? = proposal.width.flatMap { $0.isFinite ? Double($0) : nil }
        let placed = answerPlacement(main: Double(subviews[0].sizeThatFits(.unspecified).width),
                                     other: Double(subviews[1].sizeThatFits(.unspecified).width),
                                     gap: Double(gap), room: room)
        return (placed.stacked, CGFloat(placed.main), CGFloat(placed.other))
    }
}

/// Lesser ways stacked on the row's trailing edge as one block: each as wide as the widest, never
/// wider than the row, so their left edges line up too. Each is drawn to the width it is given
/// (`fillWidth`).
struct TrailingColumn: Layout {
    var gap: CGFloat = 12

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = columnWidth(proposal, subviews)
        let heights = subviews.map { $0.sizeThatFits(ProposedViewSize(width: width, height: nil)).height }
        let height = heights.reduce(0, +) + gap * CGFloat(max(subviews.count - 1, 0))
        return CGSize(width: proposal.width ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let width = columnWidth(proposal, subviews)
        let offer = ProposedViewSize(width: width, height: nil)
        var y = bounds.minY
        for subview in subviews {
            subview.place(at: CGPoint(x: bounds.maxX - width, y: y), proposal: offer)
            y += subview.sizeThatFits(offer).height + gap
        }
    }

    private func columnWidth(_ proposal: ProposedViewSize, _ subviews: Subviews) -> CGFloat {
        let widest = subviews.map { $0.sizeThatFits(.unspecified).width }.max() ?? 0
        guard let room = proposal.width else { return widest }
        return min(widest, room)
    }
}

/// A small text button for a lesser action beside a title, like "See all" or "Clear all": no
/// border and no fill. `font` sets it in the words beside it instead, as under a list row's own
/// lines.
struct QuietButton: View {
    let text: String
    var font: Font = .octoLabel
    let action: () -> Void

    init(_ text: String, font: Font = .octoLabel, action: @escaping () -> Void) {
        self.text = text
        self.font = font
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(font)
                .foregroundStyle(OctoColor.textSecondary)
                .lineLimit(1)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .contentShape(Capsule())
        }
        .buttonStyle(QuietPress())
    }
}

/// The platform's press highlight, kept to a capsule.
private struct QuietPress: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(Capsule().fill(OctoColor.textPrimary.opacity(configuration.isPressed ? 0.10 : 0)))
            .animation(.easeOut(duration: 0.1), value: configuration.isPressed)
    }
}

/// A chrome button pressed: its glass pops to 1.05 while its icon dips to 0.96.
private struct ChromePress: ButtonStyle {
    var lone: Bool
    var active: Bool
    @Environment(\.motionScale) private var motion

    func makeBody(configuration: Configuration) -> some View {
        let pressed = configuration.isPressed
        configuration.label
            .scaleEffect(pressed ? motion.scale(OctoPress.scale) : 1)
            .background {
                if !lone {
                    if active {
                        GlazeSelected(shape: Capsule())
                    } else if pressed {
                        Circle().fill(OctoColor.textPrimary.opacity(0.10))
                    }
                }
            }
            .scaleEffect(pressed && lone ? motion.scale(OctoPress.pop) : 1)
            .animation(.octoTween(OctoDuration.press, scale: motion), value: pressed)
    }
}

/// A round chrome button: 38 by 38 alone, 38 by 30 inside a group, with an 18 point icon.
/// Alone it wears its own glass and shadow; `active` adds the darker pill inside.
struct ChromeButton: View {
    let icon: OctoIcon
    let label: String
    var active = false
    var inGroup = false
    var accent: IconAccent?
    let action: () -> Void
    @State private var plays = 0

    init(_ icon: OctoIcon, label: String, active: Bool = false, inGroup: Bool = false, accent: IconAccent? = nil,
         action: @escaping () -> Void) {
        self.icon = icon
        self.label = label
        self.active = active
        self.inGroup = inGroup
        self.accent = accent
        self.action = action
    }

    var body: some View {
        Button {
            plays += 1
            action()
        } label: {
            IconView(icon, size: 18, color: OctoColor.textPrimary)
                .iconAccent(accent, trigger: plays)
                .frame(width: inGroup ? 30 : 38, height: 38)
                .background {
                    if active && !inGroup { GlazeSelected(shape: Capsule()).padding(3) }
                }
                .contentShape(Capsule())
        }
        .buttonStyle(ChromePress(lone: !inGroup, active: active && inGroup))
        .modifier(LoneChrome(lone: !inGroup))
        .accessibilityLabel(label)
    }
}

private struct LoneChrome: ViewModifier {
    let lone: Bool

    func body(content: Content) -> some View {
        if lone {
            content.octoGlass(Capsule(), interactive: true).chromeShadow()
        } else {
            content
        }
    }
}

/// A row of chrome buttons in one glass capsule, at least 38 tall, the buttons 4 points from its
/// ends and 4 apart.
struct ChromeGroup<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        HStack(spacing: 4) { content }
            .padding(.horizontal, 4)
            .frame(minHeight: 38)
            .octoGlass(Capsule())
            .chromeShadow()
    }
}

/// The turning arc: 270 degrees, once every 900 ms, its stroke 12% of its size.
struct Spinner: View {
    var size: CGFloat = 18
    /// The arc's color; nil takes the theme's words.
    var color: Color?
    @Environment(\.octoPalette) private var palette

    var body: some View {
        TimelineView(.animation) { time in
            let turn = time.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 0.9) / 0.9
            Circle()
                .trim(from: 0, to: 0.75)
                .stroke(color ?? palette.color(.textPrimary), style: StrokeStyle(lineWidth: size * 0.12, lineCap: .round))
                .rotationEffect(.degrees(turn * 360))
                .padding(size * 0.06)
        }
        .frame(width: size, height: size)
        .accessibilityLabel("Loading")
    }
}

/// The waiting spinner for a page loading: an accent arc that grows and shrinks as it turns.
struct LoadingSpinner: View {
    var size: CGFloat = 28
    /// The arc's color; nil takes the accent.
    var color: Color?
    @Environment(\.octoPalette) private var palette

    var body: some View {
        let color = color ?? palette.color(.accent)
        TimelineView(.animation) { time in
            let t = time.date.timeIntervalSinceReferenceDate
            // One grow-and-shrink every 1.333 seconds, the whole arc turning meanwhile.
            let cycle = t.truncatingRemainder(dividingBy: 1.333) / 1.333
            let grow = OctoEasing.standard.at(min(1, cycle * 2))
            let shrink = OctoEasing.standard.at(max(0, cycle * 2 - 1))
            let head = 0.05 + 0.70 * grow
            let tail = 0.70 * shrink
            Circle()
                .trim(from: tail, to: max(tail + 0.02, head))
                .stroke(color, style: StrokeStyle(lineWidth: 4 * size / 40, lineCap: .round))
                .rotationEffect(.degrees(t.truncatingRemainder(dividingBy: 1.568) / 1.568 * 360 - 90))
        }
        .frame(width: size, height: size)
        .accessibilityLabel("Loading")
    }
}

extension View {
    /// Plays an icon's small gesture each time `trigger` changes: 300 ms, strongest 45% of the
    /// way through, still with Reduce motion.
    func iconAccent<T: Equatable>(_ accent: IconAccent?, trigger: T) -> some View {
        modifier(IconAccentPlayer(accent: accent, trigger: trigger))
    }

    /// A glow behind an icon while `lit`: a wide soft copy and a close one, fading in and out
    /// over 300 ms.
    func iconGlow(_ lit: Bool, color: Color? = nil) -> some View {
        modifier(IconGlow(lit: lit, color: color))
    }
}

private struct IconAccentPlayer<T: Equatable>: ViewModifier {
    let accent: IconAccent?
    let trigger: T
    @State private var started: Date?
    @Environment(\.motionScale) private var motion

    func body(content: Content) -> some View {
        if let accent {
            TimelineView(.animation(paused: started == nil)) { time in
                let length = max(0.001, motion.seconds(accentDuration))
                let progress = started.map { time.date.timeIntervalSince($0) / length } ?? 1
                let pose = accentPose(accent, progress, distance: motion.distance)
                content
                    .scaleEffect(pose.scale)
                    .rotationEffect(.degrees(pose.rotation))
                    .offset(x: pose.dx, y: pose.dy)
            }
            .onChange(of: trigger) {
                started = Date()
                Task {
                    try? await Task.sleep(for: .seconds(motion.seconds(accentDuration) + 0.05))
                    started = nil
                }
            }
        } else {
            content
        }
    }
}

private struct IconGlow: ViewModifier {
    let lit: Bool
    let color: Color?
    @Environment(\.motionScale) private var motion
    @Environment(\.octoPalette) private var palette

    func body(content: Content) -> some View {
        content
            .background {
                ZStack {
                    content.blur(radius: 10).opacity(0.6)
                    content.blur(radius: 3).opacity(0.7)
                }
                .foregroundStyle(color ?? palette.color(.textPrimary))
                .opacity(lit ? 1 : 0)
                .animation(.octoTween(OctoDuration.neutral, scale: motion), value: lit)
                .allowsHitTesting(false)
            }
    }
}
