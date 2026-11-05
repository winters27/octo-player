import Observation
import OctoKit
import SwiftUI
import UIKit

/// Where the message pill is in its window, so every other touch passes through to the app.
@MainActor
@Observable
final class ToastSpot {
    @ObservationIgnored var frame: CGRect?
    /// The place the pill has just left, still taking taps for a moment.
    @ObservationIgnored var lateTap = LateTapGuard()
}

/// The message line's own window, just above the app's, so a message shows over the system
/// sheets, popovers, menus and questions the app opens. It takes a touch only on the pill (and,
/// for a moment after it leaves, on its place), and the status bar follows the app under it.
@MainActor
final class ToastWindow {
    private static var shared: ToastWindow?
    private let window: ToastPassWindow

    private init(scene: UIWindowScene, below: UIWindow, router: Router) {
        let spot = ToastSpot()
        let host = ToastController(rootView: AnyView(
            ThemedToast()
                .environment(router)
                .environment(spot)
        ))
        host.below = below
        host.view.backgroundColor = .clear
        window = ToastPassWindow(windowScene: scene)
        window.spot = spot
        window.windowLevel = UIWindow.Level(rawValue: UIWindow.Level.normal.rawValue + 0.5)
        window.backgroundColor = .clear
        window.rootViewController = host
        window.isHidden = false
        ThemeState.shared.adoptToast(window)
    }

    /// Puts the window up once, over the window `below` of the app.
    static func install(over below: UIWindow, router: Router) {
        guard shared == nil, let scene = below.windowScene else { return }
        shared = ToastWindow(scene: scene, below: below, router: router)
    }
}

/// The message line in the theme's colors, or over the art while the full player is open. The
/// window's light or dark style is set by `ThemeState`.
private struct ThemedToast: View {
    @State private var theme = ThemeState.shared

    var body: some View {
        ToastHost()
            .environment(\.octoPalette, theme.toastPalette)
            .tint(theme.toastPalette.color(.accent))
    }
}

/// Lets every touch through but those on the message pill.
private final class ToastPassWindow: UIWindow {
    weak var spot: ToastSpot?

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        if let frame = spot?.frame, frame.contains(point) { return super.hitTest(point, with: event) }
        // Just after the pill has gone, a tap on its place is taken here and does nothing.
        if spot?.lateTap.takes(point) == true { return rootViewController?.view }
        return nil
    }
}

/// Hosts the message line, leaving the status bar's look to whatever the app shows under it.
private final class ToastController: UIHostingController<AnyView> {
    weak var below: UIWindow?

    /// The app's front screen: its root, or what it presents on top.
    private var front: UIViewController? {
        var top = below?.rootViewController
        while let next = top?.presentedViewController, !next.isBeingDismissed { top = next }
        return top
    }

    override var childForStatusBarStyle: UIViewController? { front }
    override var childForStatusBarHidden: UIViewController? { front }
}

/// Puts the message line's window up once the shell is in a window.
struct ToastWindowProbe: UIViewRepresentable {
    let router: Router

    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.router = router
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ view: ProbeView, context: Context) {}

    final class ProbeView: UIView {
        var router: Router?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard let window, let router else { return }
            ToastWindow.install(over: window, router: router)
        }
    }
}

#if DEBUG
extension ToastWindow {
    /// What the message window does with a tap on a pill's place 0.3 s and 0.7 s after the pill
    /// has gone, in words.
    static func lateTapCheck(router: Router) async -> String {
        guard let window = shared?.window, let spot = window.spot else { return "No message window" }
        router.show("Checking a late tap")
        while spot.frame == nil { try? await Task.sleep(for: .milliseconds(50)) }
        while spot.frame != nil { try? await Task.sleep(for: .milliseconds(50)) }
        guard let place = spot.lateTap.frame else { return "No place kept" }
        let point = CGPoint(x: place.midX, y: place.midY)
        try? await Task.sleep(for: .milliseconds(300))
        let early = window.hitTest(point, with: nil) != nil
        try? await Task.sleep(for: .milliseconds(400))
        let late = window.hitTest(point, with: nil) != nil
        return "Late tap at 0.3 s: \(early ? "taken" : "passed"). At 0.7 s: \(late ? "taken" : "passed")."
    }
}
#endif
