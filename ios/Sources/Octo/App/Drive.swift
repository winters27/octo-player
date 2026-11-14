#if DEBUG
import Foundation
import os
import QuartzCore
import OctoKit
import SwiftUI
import UIKit

/// Steps named in the OCTO_DRIVE launch variable, run one after another once the app has
/// started, to bring it to a given state from outside. Steps are separated by ";":
///
/// - `wait:SECONDS`
/// - `tab:home`, `tab:search`, `tab:library`, `tab:settings` (the tab in use again goes back to
///   its first page, or scrolls it to the top)
/// - `reselect` taps the tab in use again; `back` goes back once
/// - `scroll:POINTS` scrolls the page in front down by POINTS (400 when left out)
/// - `barplayer` grows the bar's small player; `showtabs` folds it back into the round button
/// - `player` opens the full player, `close` closes it, `lyrics` opens its lyrics
/// - `player:STATE` opens the full player in a state: `player:queue` (Up next), `player:sleep`
///   (the sleep card's popover), `player:sleep:MINUTES` (a countdown started, then the card),
///   `player:speed` (the speed card's popover, from the speed mark), `player:speedpage` (the sleep
///   card opened on its speed page), `player:timing` (lyrics open, then the lyrics timing
///   popover), `player:chooser` (the Choose lyrics sheet), `player:look:flowing`,
///   `player:look:classic` (lyrics open in that style),
///   `player:background:immersive|artwork|color|classic`, `player:album` (the song's album opened
///   from inside the player, as the song menu does, so the player closes with its cover flying
///   home). The song menu and the lyrics menu are
///   system menus, which only a finger opens.
/// - `list:NAME` plays the server playlist with that name
/// - `found:TEXT` plays the songs not in the library that a search for TEXT finds
/// - `search:TEXT` plays the library songs a search for TEXT finds
/// - `pause`
/// - `covertime:N` times fetching N random albums' covers from the server, straight from it
/// - `probe` logs the whole app's frame rate and late frames every two seconds, each turn of the
///   main thread over 20 ms, and the cover's flight between the bar and the player frame by frame
/// - discovery steps: see `DiscoveryDrive`
/// - `toast:TEXT` a plain message; `undo:TEXT` a message with Undo; `done:TEXT` one with a check
/// - `dialog` the system question with Delete and Cancel; `sheet` a system sheet opened from
///   code
/// - `reducemotion:on`, `reducemotion:off` sets Octo's own Reduce motion
/// - `glow:off`, `glow:subtle`, `glow:rich` sets the artwork's glow
/// - `select:N` picks N songs of the queue (or of a sample list), showing the selection bar;
///   `unselect` ends the picking
/// - library steps (see `LibraryDrive`): `page:NAME`, `album:NAME`, `artist:NAME`, `playlist:NAME`,
///   `genre:NAME`, `menu:KIND` (song, album, artist, playlist; `menu:KIND=NAME`), `info:TITLE`,
///   `sort:LIST:OPTION:asc|desc`, `pick:COUNT` (picks songs on the open library page),
///   `songs-search:TEXT`, `songs-filter:CHIPS` (like `songs-filter:lossless,rating4`), and
///   `menu:live` (a live list's options; `menu:live:rename`, `menu:live:delete`)
/// - `sound`, `sound:graphic`, `sound:parametric`, `sound:preset:NAME`, `sound:editor`,
///   `sound:save`, `sound:own:NAME`, `sound:delete:NAME`, `sound:card:NAME`, `sound:eq:on|off`,
///   `sound:each:on|off`, `sound:crossfeed:on|off|PRESET`
///   open the Sound page in that state (see `SoundDrive`)
/// - `settings:PAGE`, `settings:PAGE@ROW`, `settings-search:TEXT`, `popup:ROW`, `whatsnew`,
///   `settings-state:NAME`: see `SettingsNavigator.drive`
/// - `account:WHAT`: the account pages, sheets, sign-in modes and ways in; see `AccountDrive`
/// - `keychainProbe` checks the keychain's in-place update on a scratch item and leaves none behind
///   (see `KeychainProbe`)
/// - `signin:STATE` opens the sign-in page in a sign-in page state, with sample values only:
///   `signin:plain`, `signin:authentik`, `signin:cloudflare`, `signin:saved`, `signin:https`,
///   `signin:join`, `signin:tall` (a sign-in page group taller than the screen at the largest text
///   sizes), each also as `STATE@end`, scrolled to the page's end, and `signin:key` (Add a server
///   scrolled to its end, then API key picked, so the key row comes back into view); see
///   `GatewayDrive`
/// - `servers:rows` opens Server and sync with two sample rows after the kept servers: one a
///   sign-in page turned away, one that needs https
@MainActor
enum Drive {
    static func run(session: Session, player: Player, router: Router, motion: MotionSettings,
                    overlays: Overlays? = nil, ambience: AmbientSettings? = nil,
                    selectionBar: SelectionBarState? = nil) async {
        guard let script = ProcessInfo.processInfo.environment["OCTO_DRIVE"], !script.isEmpty else { return }
        for _ in 0..<40 where session.client == nil { try? await Task.sleep(for: .milliseconds(250)) }
        try? await Task.sleep(for: .seconds(1))
        for step in script.split(separator: ";").map({ $0.trimmingCharacters(in: .whitespaces) }) where !step.isEmpty {
            let (name, value) = split(step)
            switch name {
            case "wait": try? await Task.sleep(for: .seconds(Double(value) ?? 1))
            case "tab": if let tab = AppTab(rawValue: value) { router.drive = .tab(tab) }
            case "reselect": router.drive = .reselect
            case "back": router.drive = .back
            case "barplayer": router.drive = .barPlayer
            case "showtabs": router.drive = .showTabs
            case "player":
                router.drive = .openPlayer
                if !value.isEmpty {
                    let (state, detail) = split(value)
                    PlayerDrive.shared.ask(state, detail)
                }
            case "close": router.drive = .closePlayer
            case "lyrics": router.drive = .lyrics
            case "scroll": scrollFront(by: CGFloat(Double(value) ?? 400))
            case "probe":
                HitchProbe.shared.start()
                MainThreadProbe.start()
                FlightProbe.on = true
            case "pause": player.pause()
            case "toast": router.show(value.isEmpty ? "Added to the queue" : value)
            case "undo": router.undoable(value.isEmpty ? "Removed from the queue" : value) { router.done("Put back") }
            case "done": router.done(value.isEmpty ? "Added to Liked songs" : value)
            case "dialog": DriveSamples.dialog(router: router)
            case "sheet":
                if let overlays { DriveSamples.sheet(overlays, router: router) }
            case "reducemotion": motion.reduceMotion = value != "off"
            case "select":
                guard let selectionBar else { continue }
                let pool = player.queue.isEmpty ? (1...6).map { Song(id: "sample-\($0)", title: "Song \($0)") } : player.queue
                let selection = SongSelection()
                let target = SelectionTarget(selection: selection)
                target.list = pool.map { Pickable($0) }
                selectionBar.attach(target)
                for song in pool.prefix(max(1, Int(value) ?? 2)) {
                    if selection.active { selection.toggle(song.id) } else { selection.start(song.id) }
                }
                DriveSamples.selections.append(target)
            case "unselect":
                for target in DriveSamples.selections {
                    target.selection.clear()
                    selectionBar?.detach(target)
                }
                DriveSamples.selections = []
            case "glow":
                if let strength = AmbientStrength(rawValue: value.capitalized) { ambience?.setStrength(strength) }
            case "sound": SoundDrive.run(value, router: router)
            case "settings", "settings-search", "popup", "whatsnew", "settings-state": await SettingsNavigator.shared.drive(name, value, router: router)
            case "signin": GatewayDrive.signIn(value, router: router)
            case "servers": await GatewayDrive.servers(value, router: router)
            case "keychainProbe": KeychainProbe.run()
            case "list":
                guard let client = session.client,
                      let list = try? await client.playlists().first(where: { $0.name.caseInsensitiveCompare(value) == .orderedSame }),
                      let full = try? await client.playlist(list.id)
                else { continue }
                player.play(full.entry, from: 0)
            case "found", "search":
                guard let client = session.client, let found = try? await client.search(value) else { continue }
                let songs = found.song.filter { name == "found" ? $0.isExternal : !$0.isExternal }
                if !songs.isEmpty { player.play(songs, from: 0) }
            default:
                var taken = false
                for handle in steps where await handle(name, value) {
                    taken = true
                    break
                }
                if !taken { await DiscoveryDrive.step(name, value, session: session, router: router) }
            }
            try? await Task.sleep(for: .milliseconds(400))
        }
    }

    private static let log = Logger(subsystem: "app.winters.octo", category: "drive")

    /// Scrolls the list of the page in front: the outermost scroll view under the middle of the
    /// screen that can scroll and is shown and touchable, kept within its ends.
    private static func scrollFront(by points: CGFloat) {
        guard let window = UIApplication.shared.connectedScenes
            .compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first,
            let hit = window.hitTest(CGPoint(x: window.bounds.midX, y: window.bounds.midY), with: nil)
        else {
            log.info("scroll: nothing under the middle of the screen")
            return
        }
        var found: UIScrollView?
        var view: UIView? = hit
        while let current = view {
            if current.isHidden || current.alpha < 0.01 || !current.isUserInteractionEnabled {
                found = nil
            } else if let scroll = current as? UIScrollView, scroll.isScrollEnabled,
                      scroll.contentSize.height > scroll.bounds.height {
                found = scroll
            }
            view = current.superview
        }
        // A page SwiftUI fades out (a tab behind the one in front) can be see-through only in its layers.
        var layer: CALayer? = found?.layer
        while let current = layer {
            if current.isHidden || current.opacity < 0.01 {
                found = nil
                break
            }
            layer = current.superlayer
        }
        guard let scroll = found else {
            log.info("scroll: no shown scroll view under the middle of the screen")
            return
        }
        let top = -scroll.adjustedContentInset.top
        let bottom = max(top, scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom)
        let y = min(max(top, scroll.contentOffset.y + points), bottom)
        scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x, y: y), animated: false)
        log.info("scroll: \(String(describing: type(of: scroll)), privacy: .public) at \(String(describing: scroll.frame), privacy: .public) to \(Double(y), privacy: .public)")
    }

    /// More steps, added by parts of the app: each answers whether it took the step.
    static var steps: [@MainActor (_ name: String, _ value: String) async -> Bool] = []

    private static func split(_ step: String) -> (String, String) {
        guard let colon = step.firstIndex(of: ":") else { return (step, "") }
        return (String(step[..<colon]), String(step[step.index(after: colon)...]))
    }
}

/// The keychain's in-place update, checked on the phone with a scratch item alone: no server,
/// certificate or trust is touched, and nothing is left behind. One log line per step, with its
/// result only.
@MainActor
enum KeychainProbe {
    private static let account = "octo-walk-probe"
    private static let log = Logger(subsystem: "app.winters.octo", category: "keychain-probe")

    static func run() {
        // A probe stopped earlier may have left the item, and keychain items outlive a reinstall.
        Keychain.delete(for: account)
        line("cleared", Keychain.lookup(for: account) == .missing)
        line("added", Keychain.saveData(Data("first".utf8), for: account))
        line("marked", Keychain.probeMark(for: account))
        line("updated", Keychain.saveData(Data("second".utf8), for: account))
        line("label kept", Keychain.probeLabel(for: account))
        line("second value", Keychain.readData(for: account) == Data("second".utf8))
        if case .found = Keychain.lookup(for: account) { line("found", true) } else { line("found", false) }
        Keychain.delete(for: account)
        line("gone", Keychain.lookup(for: account) == .missing)
    }

    private static func line(_ step: String, _ ok: Bool) {
        log.info("keychain probe \(step, privacy: .public): \(ok, privacy: .public)")
    }
}

/// The system sheet and question as the drive steps show them.
@MainActor
enum DriveSamples {
    /// The picks the drive steps made, so `unselect` can end them.
    static var selections: [SelectionTarget] = []

    static func sheet(_ overlays: Overlays, router: Router) {
        let id = UUID()
        overlays.showSheet(id, tall: false, scrolls: true, content: AnyView(
            VStack(alignment: .leading, spacing: 0) {
                Text("Sleep timer").font(.octoSection).padding(.horizontal, 20).padding(.vertical, 10)
                ForEach(["15 minutes", "30 minutes", "45 minutes", "1 hour", "End of song"], id: \.self) { item in
                    Button {
                        overlays.hideSheet(id)
                        router.done("Sleeping in \(item)")
                    } label: {
                        Text(item).font(.octoBodySmall).foregroundStyle(OctoColor.textPrimary)
                            .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                            .padding(.horizontal, 20)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(SqueezeStyle())
                }
                OctoSwitchRow()
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
            }
            .padding(.bottom, 12)
        )) { overlays.hideSheet(id) }
    }

    /// The system question a delete asks, with the words a song's delete from disk uses.
    static func dialog(router: Router) {
        guard let top = SystemShare.topController() else { return }
        let ask = UIAlertController(title: "Delete this song from disk?",
                                    message: "This takes it off the server's disk and out of your library.", preferredStyle: .actionSheet)
        ask.addAction(UIAlertAction(title: "Delete", style: .destructive) { _ in router.done("Deleted") })
        ask.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        ask.popoverPresentationController?.sourceView = top.view
        top.present(ask, animated: true)
    }
}

/// The sign-in page in each state a sign-in page in front of the server leaves it, and the
/// servers list's rows for one. Every value is a sample ("example"), held in memory only:
/// nothing goes to the keychain, the settings or the kept servers.
@MainActor
enum GatewayDrive {
    private static let address = "music.example.com"

    /// Opens the sign-in page in `state`; an unknown state opens nothing. `STATE@end` opens it
    /// scrolled to its end, where the sign-in page fields are.
    static func signIn(_ step: String, router: Router) {
        let state = step.hasSuffix("@end") ? String(step.dropLast(4)) : step
        var form: SignInModel.Form = .signIn
        var then: ((SignInModel) -> Void)?
        let sample: (SignInModel) -> Void
        switch state {
        case "plain":
            // Add a server, Advanced open, the sign-in page section at None.
            form = .add
            sample = { model in
                model.label = "Example"
                model.rest = address
                model.username = "example"
                model.advancedOpen = true
            }
        case "authentik":
            // As a sign-in left it after Authentik answered in the server's place.
            sample = { model in
                model.rest = address
                model.username = "example"
                model.driveSample(met: .authentik, words: GatewayWords.failure(.authentik, loginSent: false))
            }
        case "cloudflare":
            sample = { model in
                model.rest = address
                model.username = "example"
                model.driveSample(met: .cloudflareAccess, words: GatewayWords.failure(.cloudflareAccess, loginSent: false))
            }
        case "saved":
            // An edit of a kept server whose sign-in page fields are saved.
            form = .edit(StoredServer(id: "drive-sample-saved", label: "Example", address: URL(string: "https://\(address)")!,
                                      username: "example"))
            sample = { model in model.driveSample(saved: .password) }
        case "https":
            // A plain http address across the internet with the sign-in page fields filled.
            sample = { model in
                model.rest = "http://\(address)"
                model.username = "example"
                model.pickGateway(.password)
                model.gatewayFirst = "example"
                model.gatewaySecond = "example"
                model.advancedOpen = true
            }
        case "join":
            sample = { model in
                model.rest = address
                model.startJoin()
                model.advancedOpen = true
            }
        case "tall":
            // As a sign-in left it after an unknown sign-in page answered: its words are the
            // longest, so at the largest text sizes the group is taller than the screen.
            sample = { model in
                model.rest = address
                model.username = "example"
                model.driveSample(met: .signInPage, words: GatewayWords.failure(.signInPage, loginSent: false))
            }
        case "key":
            // Add a server with Advanced open, scrolled to its end; then API key is picked with no
            // key kept, so the key row in the main card comes back into view.
            form = .add
            sample = { model in
                model.label = "Example"
                model.rest = address
                model.username = "example"
                model.advancedOpen = true
            }
            then = { model in model.pickMethod(.apiKey) }
        default:
            return
        }
        SignInView.sampleOnOpen = sample
        SignInView.endOnOpen = state != step || then != nil
        SignInView.thenOnOpen = then
        router.signIn = form
    }

    /// `rows`: Server and sync with two sample rows after the kept servers.
    static func servers(_ state: String, router: Router) async {
        guard state == "rows" else { return }
        ServersSection.sample = [
            (StoredServer(id: "drive-sample-authentik", label: "Example behind Authentik", address: URL(string: "https://\(address)")!,
                          username: "example", serverType: "navidrome", serverVersion: "0.58.0"),
             ServerCheck(.signInPage(.authentik, loginSent: true))),
            (StoredServer(id: "drive-sample-https", label: "Example over http", address: URL(string: "http://example.net")!,
                          username: "example", serverType: "navidrome", serverVersion: "0.58.0"),
             ServerCheck(.needsHttps)),
        ]
        await SettingsNavigator.shared.drive("settings", SettingsPage.server.rawValue, router: router)
    }
}

/// The sign-in page's callout as a device step reads it, one log line each: a visible height it
/// read beside UIKit's own for the key window, where it scrolled, and that it finished.
@MainActor
enum CalloutLog {
    private static let log = Logger(subsystem: "app.winters.octo", category: "callout")

    static func height(_ visible: Double) {
        let window = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first
        let uikit = window.map { Double($0.safeAreaLayoutGuide.layoutFrame.height) } ?? -1
        log.info("callout height visible=\(visible, privacy: .public) uikit=\(uikit, privacy: .public)")
    }

    static func scroll(_ target: CalloutTarget) {
        log.info("callout scroll \(String(describing: target), privacy: .public)")
    }

    static func shown(wasUp: Bool) {
        log.info("callout shown wasUp=\(wasUp, privacy: .public)")
    }
}

private struct OctoSwitchRow: View {
    @State private var on = false

    var body: some View {
        HStack {
            Text("Fade out at the end").font(.octoBodySmall).foregroundStyle(OctoColor.textPrimary)
            Spacer()
            OctoSwitch(isOn: $on, label: "Fade out at the end")
        }
    }
}

/// Watches the whole app's frames while a debug build runs and logs, every two seconds, how
/// many came and how many were late, for checking smoothness on a device.
@MainActor
final class HitchProbe: NSObject {
    static let shared = HitchProbe()
    private let log = Logger(subsystem: "app.winters.octo", category: "frames")
    private var link: CADisplayLink?
    private var last: CFTimeInterval?
    private var since: CFTimeInterval = 0
    private var count = 0
    private var late = 0
    private var worst: CFTimeInterval = 0

    func start() {
        guard link == nil else { return }
        let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    @objc private func tick(_ link: CADisplayLink) {
        let now = link.timestamp
        if let last {
            let gap = now - last
            worst = max(worst, gap)
            if gap > (link.targetTimestamp - link.timestamp) * 1.8 + 0.004 {
                late += 1
                log.notice("app late frame \(gap * 1000, format: .fixed(precision: 1)) ms at \(now, format: .fixed(precision: 3))")
            }
        } else {
            since = now
        }
        last = now
        count += 1
        if now - since >= 2 {
            log.notice("app frames \(Double(self.count) / (now - self.since), format: .fixed(precision: 1)) fps, late \(self.late), worst \(self.worst * 1000, format: .fixed(precision: 1)) ms")
            since = now
            count = 0
            late = 0
            worst = 0
        }
    }
}
/// Logs the cover's flight between the bar and the player, frame by frame, with the clock, so a
/// stall on a device can be told from a late frame and put next to what else happened.
@MainActor
enum FlightProbe {
    private static let log = Logger(subsystem: "app.winters.octo", category: "flight")
    static var on = false

    static func note(_ text: @autoclosure () -> String) {
        guard on else { return }
        let message = text()
        log.notice("flight \(CACurrentMediaTime(), format: .fixed(precision: 3)) \(message, privacy: .public)")
    }
}

/// Logs every turn of the main run loop that keeps the main thread busy for more than 20 ms.
@MainActor
enum MainThreadProbe {
    private static let log = Logger(subsystem: "app.winters.octo", category: "main")
    private nonisolated(unsafe) static var observer: CFRunLoopObserver?
    private nonisolated(unsafe) static var woke: CFTimeInterval = 0

    static func start() {
        guard observer == nil else { return }
        let activities = CFRunLoopActivity.afterWaiting.rawValue | CFRunLoopActivity.beforeWaiting.rawValue
        observer = CFRunLoopObserverCreateWithHandler(nil, activities, true, 0) { _, activity in
            let now = CACurrentMediaTime()
            if activity == .afterWaiting {
                woke = now
            } else if woke > 0, now - woke > 0.02 {
                log.notice("main busy \((now - woke) * 1000, format: .fixed(precision: 1)) ms from \(woke, format: .fixed(precision: 3))")
            }
        }
        CFRunLoopAddObserver(CFRunLoopGetMain(), observer, .commonModes)
    }
}

/// Times how long the server takes to hand over album covers, for a debug build on a device.
@MainActor
enum CoverTiming {
    static func run(session: Session, library: Library, count: Int) async {
        let log = Logger(subsystem: "app.winters.octo", category: "covers")
        guard let client = session.client else { return }
        var asks: [(String, String)] = []
        for album in await library.albums().shuffled().prefix(count) {
            guard case .server(_, let coverId, _, _) = ArtworkRef.decode(album.artwork) else { continue }
            asks.append((coverId, "album \(album.title)"))
        }
        for song in (try? await client.randomSongs(count: count)) ?? [] {
            guard let coverId = song.coverArt else { continue }
            asks.append((coverId, "song \(coverId) \(song.title)"))
        }
        for (coverId, name) in asks {
            for size in [600, 128, 600] {
                var request = URLRequest(url: client.coverURL(coverId, size: size))
                request.cachePolicy = .reloadIgnoringLocalCacheData
                let start = Date()
                let answer = try? await ServerExchange.answer(for: request, client: client)
                let ms = Int(Date().timeIntervalSince(start) * 1000)
                let bytes = answer?.data.count ?? 0
                let status = answer?.status ?? 0
                log.notice("cover \(size) \(ms) ms \(bytes) bytes status \(status) \(name, privacy: .public)")
            }
        }
    }
}

#endif
