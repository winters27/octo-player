import OctoKit
import SwiftUI

/// The tokens the library pages use beyond the shared palette.
enum LibraryLook {
    /// The small bold words of buttons and chips.
    static let label = Font.system(size: 13.5, weight: .semibold)
    static let labelMedium = Font.system(size: 13.5, weight: .medium)
}

/// Play and Shuffle across a detail page: two glass capsules of equal width. Play has the lifted
/// glass, Shuffle the resting glass. While loading, Play shows a spinner in place of its icon.
struct PlayShuffle: View {
    var enabled = true
    var loading = false
    let play: () -> Void
    let shuffle: () -> Void

    var body: some View {
        GlassEffectContainer(spacing: 12) {
            HStack(spacing: 12) {
                capsule("Play", icon: .play, lifted: true, spinner: loading, action: play)
                capsule("Shuffle", icon: .shuffle, lifted: false, spinner: false, action: shuffle)
            }
        }
        .disabled(!enabled || loading)
        .opacity(enabled || loading ? 1 : 0.4)
    }

    private func capsule(_ word: String, icon: OctoIcon, lifted: Bool, spinner: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if spinner {
                    Spinner(size: 18).frame(width: 22, height: 22)
                } else {
                    IconView(icon, size: 22)
                }
                Text(word).font(.octoBody)
            }
            .foregroundStyle(OctoColor.textPrimary)
            .frame(maxWidth: .infinity)
            .frame(height: 46)
            .octoGlass(Capsule(), interactive: true, lifted: lifted)
            .frame(minHeight: 48)
            .contentShape(Capsule())
        }
        .buttonStyle(PressScale(scale: 0.96))
    }
}

/// A round glass button with an icon: 34 across in a 48 target, lit while `lit`.
struct GlassIconButton: View {
    let icon: OctoIcon
    let label: String
    var lit = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            IconView(icon, size: 17, color: OctoColor.textPrimary)
                .frame(width: 34, height: 34)
                .octoGlass(Circle(), interactive: true, lifted: lit)
                .frame(width: 48, height: 48)
                .contentShape(Circle())
                .animation(.easeInOut(duration: 0.16), value: icon)
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(label)
    }
}

/// A quiet action: an icon and a word in the secondary color, no glass. As a switch it turns
/// to the primary words while on.
struct QuietAction: View {
    let icon: OctoIcon
    let text: String
    var enabled = true
    var checked: Bool?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            QuietActionLabel(icon: icon, text: text, lit: checked == true)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.6)
        .accessibilityAddTraits(checked != nil ? .isToggle : [])
        .accessibilityValue(checked == nil ? "" : (checked == true ? "On" : "Off"))
    }
}

/// A quiet action's icon and words, also the label of a quiet menu button.
struct QuietActionLabel: View {
    let icon: OctoIcon
    let text: String
    var lit = false

    var body: some View {
        HStack(spacing: 6) {
            IconView(icon, size: 18)
            Text(text).font(LibraryLook.label).lineLimit(1)
        }
        .foregroundStyle(lit ? AnyShapeStyle(OctoColor.textPrimary) : AnyShapeStyle(OctoColor.textSecondary))
        .padding(.horizontal, 10)
        .padding(.vertical, 12)
        .contentShape(Capsule())
    }
}

/// The plain icon in the primary ink in a 48 circle, no glass.
struct QuietIconAction: View {
    let icon: OctoIcon
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            IconView(icon, size: 22, color: OctoInkColor.primary)
                .frame(width: 48, height: 48)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// Centered spinner in the accent color, for a list before its first read.
struct LibrarySpinner: View {
    var body: some View {
        LoadingSpinner(size: 28)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 40)
    }
}

/// A muted note under a page title, like an empty list's.
struct MutedNote: View {
    let text: String
    var horizontal: CGFloat = 20
    var vertical: CGFloat = 8

    init(_ text: String, horizontal: CGFloat = 20, vertical: CGFloat = 8) {
        self.text = text
        self.horizontal = horizontal
        self.vertical = vertical
    }

    var body: some View {
        Text(text)
            .font(.octoBodySmall)
            .foregroundStyle(OctoColor.textMuted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, horizontal)
            .padding(.vertical, vertical)
    }
}

/// "Nothing here yet" and the ways to get music in.
struct EmptyLibraryNote: View {
    var text = "Nothing here yet"

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(text).font(.octoBodySmall).foregroundStyle(OctoColor.textMuted)
            LibrarySourceActions()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
    }
}

/// Ways to get music in: "Allow access" while the phone's music is out of reach ("Open
/// settings" once the system no longer asks), and "Sign in to a server" while none is in use.
/// They wrap 12 apart; nothing shows when neither applies.
struct LibrarySourceActions: View {
    @Environment(Session.self) private var session
    @Environment(Router.self) private var router
    @Environment(DeviceLibrary.self) private var device

    var body: some View {
        let askAccess = device.access != .granted
        let signIn = session.client == nil
        if askAccess || signIn {
            FlowRow(spacing: 12) {
                if askAccess {
                    GlazeButton(accessButtonLabel(deniedForever: device.access == .deniedForever)) {
                        if device.access == .deniedForever { device.openSettings() } else { device.requestAccess() }
                    }
                }
                if signIn {
                    GlazeButton("Sign in to a server") { router.signIn = .signIn }
                }
            }
        }
    }
}

/// The order a list is in, as a small glass capsule: the sort mark, what it goes by, and an
/// arrow for which way it runs. A tap opens the system menu: the two directions on top, then the
/// options. Picking another option gives it in its own starting direction.
struct SortButton: View {
    let list: SortList
    @Environment(LibraryState.self) private var library

    var body: some View {
        let order = library.order(list)
        Menu {
            SortMenuItems(list: list)
        } label: {
            HStack(spacing: 7) {
                IconView(.sort, size: 16, color: OctoInkColor.primary)
                Text(order.by.label)
                    .font(LibraryLook.labelMedium)
                    .foregroundStyle(OctoColor.textPrimary)
                    .lineLimit(1)
                IconView(.ascending, size: 14, color: OctoInkColor.primary)
                    .rotationEffect(.degrees(sortArrowTurn(descending: order.descending)))
                    .animation(.interpolatingSpring(stiffness: 300, damping: 2 * 0.7 * sqrt(300)), value: order.descending)
            }
            .padding(.leading, 12)
            .padding(.trailing, 11)
            .frame(height: 34)
            .octoGlass(Capsule(), interactive: true)
            .frame(minHeight: 48)
            .contentShape(Capsule())
        }
        .menuOrder(.fixed)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Sorted by \(order.by.label), \(sortScale(order.by).label(descending: order.descending))")
        .accessibilityHint("Change the order")
        .accessibilityAddTraits(.isButton)
    }
}

/// What the sort menu holds: the two directions, the option's own starting one first, then the
/// options. Another option comes in its own starting direction; another direction reorders at
/// once.
struct SortMenuItems: View {
    let list: SortList
    @Environment(LibraryState.self) private var library

    var body: some View {
        let order = library.order(list)
        Picker("Direction", selection: Binding(get: { order.descending },
                                               set: { library.setOrder(list, ListOrder(order.by, descending: $0)) })) {
            ForEach(directionChoices(order.by), id: \.descending) { choice in
                Label(choice.label, systemImage: choice.descending ? "arrow.down" : "arrow.up").tag(choice.descending)
            }
        }
        .pickerStyle(.palette)
        Picker("Sort by", selection: Binding(get: { order.by.id }, set: { id in
            guard let option = list.options.first(where: { $0.id == id }), option.id != order.by.id else { return }
            library.setOrder(list, order.picking(option))
        })) {
            ForEach(list.options, id: \.id) { option in Text(option.label).tag(option.id) }
        }
        .pickerStyle(.inline)
    }
}

/// Sort as a row in More, once the line has no room for the button: "Sort: Date liked", opening
/// the same choices.
struct SortMoreRow: View {
    let list: SortList
    @Environment(LibraryState.self) private var library

    var body: some View {
        Menu {
            SortMenuItems(list: list)
        } label: {
            Label("Sort: \(library.order(list).by.label)", systemImage: OctoIcon.sort.symbol)
        }
        .menuOrder(.fixed)
    }
}

extension LineOverflow {
    /// A list's Sort button, the first thing to move into More.
    static func sort(_ list: SortList, priority: Int = 1) -> LineOverflow {
        LineOverflow("sort", priority: priority) { SortButton(list: list) } row: { SortMoreRow(list: list) }
    }
}

/// A line above a list inside a page: an optional name on the left and the sort button at the
/// right.
struct SortBar: View {
    var name: String?
    let list: SortList

    var body: some View {
        HStack {
            if let name {
                Text(name).font(.octoSection).padding(.vertical, 10).accessibilityAddTraits(.isHeader)
            }
            Spacer(minLength: 8)
            SortButton(list: list)
        }
        .padding(.horizontal, 20)
    }
}

/// A run's letter, as a small dark pill that sticks to the top while its rows scroll.
struct LetterHeading: View {
    let letter: Character

    var body: some View {
        HStack {
            Text(String(letter))
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(OctoColor.textMuted)
                .octoOverArt()
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .frame(minWidth: 26, minHeight: 26)
                .background(.black.opacity(0.55), in: Capsule())
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .accessibilityAddTraits(.isHeader)
    }
}

/// The letter rail along the right edge of a long list ordered by name. Touching or dragging
/// jumps the list to that letter with a light tick, and a glass bubble shows the letter.
struct LetterRail: View {
    let stops: [RailStop]
    let jump: (Int) -> Void
    @State private var touched: Int?
    @State private var shown: Character = "A"
    @State private var tick = 0

    var body: some View {
        GeometryReader { box in
            let slot = box.size.height / CGFloat(max(stops.count, 1))
            ZStack(alignment: .topTrailing) {
                VStack(spacing: 0) {
                    ForEach(Array(stops.enumerated()), id: \.offset) { index, stop in
                        Text(String(stop.letter))
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(touched == index ? AnyShapeStyle(OctoColor.textPrimary) : AnyShapeStyle(OctoColor.textMuted))
                            .frame(width: 24, height: slot)
                    }
                }
                .frame(width: 24)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        let index = min(max(Int(value.location.y / box.size.height * CGFloat(stops.count)), 0), stops.count - 1)
                        guard stops.indices.contains(index), index != touched else { return }
                        touched = index
                        shown = stops[index].letter
                        tick += 1
                        jump(stops[index].index)
                    }
                    .onEnded { _ in touched = nil })
                if let touched {
                    Text(String(shown))
                        .font(.octoTitle)
                        .foregroundStyle(OctoColor.textPrimary)
                        .frame(width: 64, height: 64)
                        .octoGlass(Circle())
                        .offset(x: -(24 + 16), y: slot * (CGFloat(touched) + 0.5) - 32)
                        .transition(.asymmetric(
                            insertion: .scale(scale: 0.85).combined(with: .opacity).animation(.easeOut(duration: 0.12)),
                            removal: .scale(scale: 0.85).combined(with: .opacity).animation(.easeIn(duration: 0.18))))
                        .allowsHitTesting(false)
                }
            }
            .animation(.easeOut(duration: 0.12), value: touched == nil)
        }
        .frame(width: 24)
        .sensoryFeedback(.selection, trigger: tick)
        .accessibilityElement()
        .accessibilityLabel("Letters. Drag to jump through the list.")
        .accessibilityAdjustableAction { direction in
            let next = (touched ?? 0) + (direction == .increment ? 1 : -1)
            guard stops.indices.contains(next) else { return }
            touched = next
            jump(stops[next].index)
        }
    }
}

/// The favorite heart on album and artist pages: it beats once when added.
struct FavoriteHeart: View {
    let on: Bool
    let toggle: () -> Void
    @Environment(\.octoPalette) private var palette

    var body: some View {
        Button(action: toggle) {
            IconView(on ? .liked : .like, size: 24, color: on ? palette.color(.highlight) : palette.color(.textPrimary).opacity(0.6))
                .shadow(color: on ? palette.color(.highlight).opacity(0.5) : .clear, radius: 6)
                .iconAccent(on ? .pulse : nil, trigger: on)
                .frame(width: 44, height: 44)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Favorite")
        .accessibilityValue(on ? "In favorites" : "Not in favorites")
    }
}

/// A few choices side by side, as the system's segmented control.
struct GlassSegments<Value: Hashable>: View {
    let options: [(value: Value, label: String)]
    @Binding var selection: Value

    var body: some View {
        Picker("", selection: $selection) {
            ForEach(options, id: \.value) { option in Text(option.label).tag(option.value) }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .segmentedAccent()
    }
}
