import Observation
import OctoKit
import SwiftUI
import UIKit

/// The theme showing and every color it draws, read wherever the app draws. Its palette is
/// computed from `AppSettings.shared`, the phone's light or dark mode and the playing cover's
/// color, never copied, so a change anywhere redraws what shows it. It is also the one writer of
/// the windows' light or dark style.
@MainActor
@Observable
final class ThemeState {
    static let shared = ThemeState()

    /// Whether the phone is in dark mode, as its window scene says.
    private(set) var deviceDark: Bool
    /// The playing cover's most vivid color, or nil while nothing with a cover plays.
    private(set) var artSeed: UInt32?
    /// Whether the full player is open; the message window then stays over the art.
    private(set) var playerOpen = false

    @ObservationIgnored private var cached: (key: PaletteKey, palette: ResolvedPalette)?
    @ObservationIgnored private weak var scene: UIWindowScene?
    @ObservationIgnored private weak var toastWindow: UIWindow?
    @ObservationIgnored private var traitRegistration: (any UITraitChangeRegistration)?
    @ObservationIgnored private var launchCovered = false

    private init() {
        deviceDark = UITraitCollection.current.userInterfaceStyle != .light
    }

    /// The four stored theme fields.
    var themes: ThemeSettings { AppSettings.shared.player.themes }

    /// The theme showing: with Match the system on, the slot for the phone's mode.
    var active: ThemeChoice { activeTheme(themes, deviceDark: deviceDark) }

    /// Every color the theme showing draws with the accent chosen.
    var resolved: ResolvedPalette {
        let prefs = AppSettings.shared.player
        let key = PaletteKey(theme: active, choice: prefs.accent, art: prefs.accent == .fromArt ? artSeed : nil, glass: prefs.glassTint)
        if let cached, cached.key == key { return cached.palette }
        let palette = resolvePalette(key.theme, key.choice, key.art, key.glass)
        cached = (key, palette)
        return palette
    }

    /// The palette for the message window: over the art while the full player is open.
    var toastPalette: ResolvedPalette { playerOpen ? overArtPalette(resolved) : resolved }

    /// The playing cover's color changed.
    func see(art: UInt32?) {
        guard art != artSeed else { return }
        artSeed = art
    }

    /// The full player opened or closed.
    func setPlayerOpen(_ open: Bool) {
        guard open != playerOpen else { return }
        playerOpen = open
        styleWindows()
    }

    /// Follows the phone's light or dark mode through `scene`, and styles its windows.
    func attach(_ scene: UIWindowScene) {
        if self.scene !== scene {
            self.scene = scene
            deviceChanged(scene.traitCollection.userInterfaceStyle != .light)
            traitRegistration = scene.registerForTraitChanges([UITraitUserInterfaceStyle.self]) { (scene: UIWindowScene, _: UITraitCollection) in
                MainActor.assumeIsolated {
                    ThemeState.shared.deviceChanged(scene.traitCollection.userInterfaceStyle != .light)
                }
            }
        }
        styleWindows()
    }

    /// The message window, which goes dark while the full player is open.
    func adoptToast(_ window: UIWindow) {
        toastWindow = window
        styleWindows()
    }

    private func deviceChanged(_ dark: Bool) {
        guard dark != deviceDark else { return }
        deviceDark = dark
        styleWindows()
    }

    /// Sets each window of the scene light or dark: the theme's kind, or the phone's own while
    /// Match the system is on; the message window dark while the full player is open.
    func styleWindows() {
        guard let scene else { return }
        let settings = themes
        let style: UIUserInterfaceStyle = settings.match ? .unspecified : (active.light ? .light : .dark)
        for window in scene.windows {
            let wanted: UIUserInterfaceStyle = window === toastWindow && playerOpen ? .dark : style
            if window.overrideUserInterfaceStyle != wanted { window.overrideUserInterfaceStyle = wanted }
        }
    }

    /// Whether the launch screen's Octo page still has to fade into the theme's: true once, on
    /// the first look, when the theme's page differs from Octo's.
    func takeLaunchCover() -> Bool {
        guard !launchCovered else { return false }
        launchCovered = true
        return resolved.page != ThemeChoice.octo.base.page
    }

    private struct PaletteKey: Equatable {
        let theme: ThemeChoice
        let choice: AccentChoice
        let art: UInt32?
        let glass: Bool
    }
}

/// Keeps the app drawn in the theme and accent Appearance chose: hands `ThemeState` the playing
/// cover's brightest color while the accent follows it, provides the palette, tints the system's
/// own controls, keeps the windows' style and fades the launch screen into the theme's page.
/// Placed once, at the root.
struct KeepTheme: ViewModifier {
    @Environment(Player.self) private var player
    @Environment(ArtworkColorStore.self) private var colorStore
    @State private var settings = AppSettings.shared
    @State private var theme = ThemeState.shared

    func body(content: Content) -> some View {
        let prefs = settings.player
        // The cover's colors are only asked for while the accent follows them.
        let art: UInt32? = prefs.accent == .fromArt ? coverSeed(player.current?.coverArt) : nil
        let palette = theme.resolved
        content
            .environment(\.octoPalette, palette)
            .tint(palette.color(.accent))
            .overlay { LaunchFade() }
            .background { ThemeWindowProbe() }
            .onChange(of: art, initial: true) { _, seed in theme.see(art: seed) }
            .onChange(of: WindowStyleAsk(themes: prefs.themes, active: theme.active), initial: true) { _, _ in theme.styleWindows() }
    }

    /// The cover's most vivid color, or none for music without artwork or before it is read.
    private func coverSeed(_ coverId: String?) -> UInt32? {
        guard let coverId, let colors = colorStore.colors(for: coverId), colors != .quiet, colors.mesh.count > 1 else { return nil }
        return 0xFF00_0000 | colors.mesh[1]
    }

    private struct WindowStyleAsk: Equatable {
        let themes: ThemeSettings
        let active: ThemeChoice
    }
}

/// Octo's launch page laid over the first frame, fading into the theme's page in 0.25 s. Drawn
/// only on a cold launch into a theme whose page is not Octo's.
private struct LaunchFade: View {
    @State private var opacity: Double = ThemeState.shared.takeLaunchCover() ? 1 : 0

    var body: some View {
        Color(argb: ThemeChoice.octo.base.page)
            .opacity(opacity)
            .ignoresSafeArea()
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .task {
                guard opacity > 0 else { return }
                withAnimation(.linear(duration: 0.25)) { opacity = 0 }
            }
    }
}

/// Hands `ThemeState` the window scene once the root is in a window.
private struct ThemeWindowProbe: UIViewRepresentable {
    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ view: ProbeView, context: Context) {}

    final class ProbeView: UIView {
        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard let scene = window?.windowScene else { return }
            ThemeState.shared.attach(scene)
        }
    }
}

extension View {
    /// Draws the app in the chosen theme and accent. Placed once, at the root.
    func keepsTheme() -> some View { modifier(KeepTheme()) }
}
