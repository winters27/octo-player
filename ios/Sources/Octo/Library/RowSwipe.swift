import OctoKit
import SwiftUI
import UIKit

/// What swiping a row does: right plays the song next or adds it to the queue, as Settings say;
/// left takes the row away.
enum RowSwipe {
    case none
    case queue(SwipeAction, () -> Void)
    case remove(() -> Void)
}

extension View {
    /// Lets the row be swiped sideways. The row follows the finger over a wash that says what
    /// letting go will do; past a third of the width, or flung, it acts. Play next and Add to queue spring back;
    /// remove slides the row away.
    func rowSwipe(_ swipe: RowSwipe, enabled: Bool) -> some View {
        modifier(RowSwipeModifier(swipe: swipe, enabled: enabled))
    }
}

private struct RowSwipeModifier: ViewModifier {
    let swipe: RowSwipe
    let enabled: Bool
    @State private var offset: CGFloat = 0
    @State private var width: CGFloat = 0
    @State private var confirmed = 0

    private var right: Bool { if case .queue = swipe { true } else { false } }
    private var left: Bool { if case .remove = swipe { true } else { false } }
    private var queueAction: SwipeAction { if case .queue(let action, _) = swipe { action } else { .playNext } }

    func body(content: Content) -> some View {
        if case .none = swipe {
            content
        } else {
            content
                .background(offset < 0 ? AnyShapeStyle(OctoColor.background) : AnyShapeStyle(Color.clear),
                            in: RoundedRectangle(cornerRadius: OctoRadius.row))
                .offset(x: offset)
                .background { wash }
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
                .gesture(SidePan(allowRight: enabled && right, allowLeft: enabled && left, changed: { dx in
                    offset = right ? max(0, dx) : min(0, dx)
                }, ended: finish))
                .sensoryFeedback(.success, trigger: confirmed)
                #if DEBUG
                .modifier(SwipeDriveMark(run: enabled && right ? { if case .queue(_, let run) = swipe { run() } } : nil, peek: {
                    withAnimation(.easeOut(duration: 0.2)) { offset = width / 3 }
                    Task { @MainActor in
                        try? await Task.sleep(for: .seconds(4))
                        withAnimation(.spring) { offset = 0 }
                    }
                }))
                #endif
        }
    }

    @ViewBuilder
    private var wash: some View {
        if offset > 0 {
            // Only over the strip the row has moved off, its far edge fading so the words never
            // end in a hard cut where the row still covers them.
            HStack(spacing: 10) {
                IconView(queueAction == .addToQueue ? .addToQueue : .playNext, size: 20)
                Text(queueAction.label).font(.octoBodySmall).lineLimit(1).fixedSize()
            }
            .foregroundStyle(OctoColor.textPrimary)
            .padding(.leading, 24)
            .frame(width: offset, alignment: .leading)
            .frame(maxHeight: .infinity)
            .background(OctoColor.accent.opacity(0.22))
            .mask(LinearGradient(stops: [
                .init(color: .primary, location: 0),
                .init(color: .primary, location: max(0, 1 - 24 / max(offset, 1))),
                .init(color: .clear, location: 1),
            ], startPoint: .leading, endPoint: .trailing))
            .clipShape(RoundedRectangle(cornerRadius: OctoRadius.row))
            .frame(maxWidth: .infinity, alignment: .leading)
        } else if offset < 0 {
            HStack {
                Spacer()
                IconView(.delete, size: 20).accessibilityLabel("Remove")
            }
            .foregroundStyle(OctoColor.textPrimary)
            .padding(.horizontal, 24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(OctoColor.error.opacity(0.25), in: RoundedRectangle(cornerRadius: OctoRadius.row))
        }
    }

    private func finish(_ dx: CGFloat, _ velocity: CGFloat) {
        let far = abs(dx) > max(width, 1) / 3 || abs(velocity) > 800
        switch swipe {
        case .queue(_, let run) where far && dx > 0:
            confirmed += 1
            run()
            withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { offset = 0 }
        case .remove(let run) where far && dx < 0:
            confirmed += 1
            withAnimation(.easeOut(duration: 0.18)) { offset = -width }
            Task {
                try? await Task.sleep(for: .milliseconds(180))
                run()
                offset = 0
            }
        default:
            withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { offset = 0 }
        }
    }
}

/// A sideways pan that only starts when the finger moves across more than down, in a direction
/// the row allows, so the list still scrolls.
private struct SidePan: UIGestureRecognizerRepresentable {
    var allowRight: Bool
    var allowLeft: Bool
    var changed: (CGFloat) -> Void
    var ended: (CGFloat, CGFloat) -> Void

    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator { Coordinator() }

    func makeUIGestureRecognizer(context: Context) -> UIPanGestureRecognizer {
        let pan = UIPanGestureRecognizer()
        pan.delegate = context.coordinator
        return pan
    }

    func updateUIGestureRecognizer(_ recognizer: UIPanGestureRecognizer, context: Context) {
        context.coordinator.allowRight = allowRight
        context.coordinator.allowLeft = allowLeft
    }

    func handleUIGestureRecognizerAction(_ recognizer: UIPanGestureRecognizer, context: Context) {
        let dx = recognizer.translation(in: recognizer.view).x
        switch recognizer.state {
        case .began, .changed: changed(dx)
        case .ended: ended(dx, recognizer.velocity(in: recognizer.view).x)
        default: ended(0, 0)
        }
    }

    @MainActor
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var allowRight = false
        var allowLeft = false

        func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
            guard let pan = recognizer as? UIPanGestureRecognizer else { return false }
            let velocity = pan.velocity(in: pan.view)
            guard abs(velocity.x) > abs(velocity.y) * 1.5 else { return false }
            return velocity.x > 0 ? allowRight : allowLeft
        }
    }
}

extension View {
    /// While the list is picking songs, going back stops the picking instead of leaving the
    /// page. Goes inside `selectableSongs`, which hands down the picks.
    func backStopsPicking() -> some View { modifier(BackStopsPicking()) }
}

private struct BackStopsPicking: ViewModifier {
    @Environment(\.songSelection) private var selection

    func body(content: Content) -> some View {
        let picking = selection?.active == true
        let selection = selection
        return content.backAction(picking ? { selection?.clear() } : nil)
    }
}
