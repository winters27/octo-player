import OctoKit
import SwiftUI

/// The message line: a small glass pill just above the bar, in the bar's glass. It grows in
/// from a little smaller and lower and fades; a newer message swaps its words in at once. It
/// stays 3.5 s (6 s with an action) once it has come in, then fades away. Tapping a message with
/// no action puts it away. It keeps its place whether the bar shows or not, and the keyboard
/// does not move it. It is drawn in a window of its own (see `ToastWindow`), so it shows over
/// sheets, popovers and menus too.
struct ToastHost: View {
    @Environment(Router.self) private var router
    @Environment(ToastSpot.self) private var spot: ToastSpot?
    @Environment(\.accessibilityReduceMotion) private var calm
    @State private var shown: Router.Toast?
    @State private var progress: Double = 0

    var body: some View {
        GeometryReader { box in
            ZStack(alignment: .bottom) {
                Color.clear
                if let toast = shown {
                    ToastPill(toast: toast) { router.dismiss(toast) }
                        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { spot?.frame = $0 }
                        .onDisappear {
                            if let frame = spot?.frame { spot?.lateTap.start(at: frame) }
                            spot?.frame = nil
                        }
                        .opacity(min(1, max(0, progress)))
                        .scaleEffect(calm ? 1 : 0.92 + 0.08 * progress, anchor: .bottom)
                        .offset(y: calm ? 0 : (1 - progress) * 10)
                        .frame(maxWidth: 520)
                        .padding(.horizontal, 20)
                        .padding(.bottom, box.safeAreaInsets.bottom + ShellMeasures.aboveBar)
                }
            }
            .ignoresSafeArea(.container, edges: .bottom)
        }
        .ignoresSafeArea(.keyboard)
        .task(id: router.toast?.id) { await follow() }
    }

    private func follow() async {
        guard let toast = router.toast else {
            guard shown != nil else { return }
            withAnimation(calm ? .linear(duration: 0.12) : .octoTween(fixed: 160, OctoEasing.accelerate)) { progress = 0 }
            try? await Task.sleep(for: .milliseconds(calm ? 120 : 160))
            if router.toast == nil { shown = nil }
            return
        }
        shown = toast
        AccessibilityNotification.Announcement(toast.text).post()
        withAnimation(calm ? .linear(duration: 0.12) : .octoSpring(0.85, 500)) { progress = 1 }
        // Counted from when the entrance has settled.
        do {
            try await Task.sleep(for: .milliseconds(calm ? 120 : 250))
            try await Task.sleep(for: .seconds(toast.seconds))
        } catch {
            return
        }
        router.dismiss(toast)
    }
}

/// One message: an optional icon, the words, and an optional action as a small accent button.
private struct ToastPill: View {
    let toast: Router.Toast
    let dismiss: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            if let icon = toast.icon { IconView(icon, size: 18, color: OctoColor.textPrimary) }
            Text(toast.text)
                .font(.octo(14, .medium, .subheadline))
                .foregroundStyle(OctoColor.textPrimary)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.vertical, 12)
            if let action = toast.action {
                Button {
                    toast.perform?()
                    dismiss()
                } label: {
                    Text(action)
                        .font(.octo(14, .semibold, .subheadline))
                        .foregroundStyle(OctoColor.onAccent)
                        .lineLimit(1)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 34)
                }
                .buttonStyle(ToastActionPress())
            }
        }
        .padding(.leading, toast.icon != nil ? 16 : 20)
        .padding(.trailing, toast.action != nil ? 0 : 20)
        .frame(minHeight: 48)
        .floatingGlass(Capsule())
        .contentShape(Capsule())
        .onTapGesture { if toast.action == nil { dismiss() } }
        .accessibilityElement(children: .combine)
    }
}

/// The action as a button: a capsule of the accent with the page's dark ink, which every accent
/// keeps at AA, sitting in from the pill's end as far as from its top and bottom. Its fill dims a
/// little under the finger. The capsule and the space around it, up to the pill's round end,
/// take the tap.
private struct ToastActionPress: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(OctoColor.accent.opacity(configuration.isPressed ? 0.82 : 1), in: Capsule())
            .animation(.easeOut(duration: 0.1), value: configuration.isPressed)
            .padding(7)
            .contentShape(Capsule())
    }
}
