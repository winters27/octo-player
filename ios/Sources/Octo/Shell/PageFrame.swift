import CoreText
import OctoKit
import SwiftUI
import UIKit

/// A page's big title: 32 point Black, scrolling with the page under the bar. Always whole:
/// never cut short and never broken inside a word. A long title wraps between its words; a
/// single word too wide for the line on its own is set just small enough to fit (never below
/// half size). It names its page for the bar, which shows the name once the page has scrolled
/// these capitals up to the status bar; the title fades out just before.
struct ScreenTitle: View {
    let text: String
    /// The largest size the title may take, when its line has a limit (beside the bar's back button).
    var maxSize: CGFloat?
    @State private var width: CGFloat = 0
    @Environment(PageTop.self) private var top: PageTop?
    @ScaledMetric(relativeTo: .largeTitle) private var size: CGFloat = 32

    init(_ text: String, maxSize: CGFloat? = nil) {
        self.text = text
        self.maxSize = maxSize
    }

    var body: some View {
        let base = min(size, maxSize ?? size)
        let scale = TitleFit.scale(text, size: base, weight: .black, tracking: octoDisplayTracking, width: width)
        let font = UIFont.systemFont(ofSize: base * scale, weight: .black)
        let drawn = width > 0 ? base * scale : nil
        Text(breakableText(text))
            .font(.system(size: base * scale, weight: .black))
            .tracking(octoDisplayTracking)
            .foregroundStyle(OctoColor.textPrimary)
            .fixedSize(horizontal: false, vertical: true)
            .modifier(TitlePlace(inset: font.ascender - font.capHeight))
            .frame(maxWidth: .infinity, alignment: .leading)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 16)
            .accessibilityLabel(text)
            .accessibilityAddTraits(.isHeader)
            .onChange(of: text, initial: true) { _, now in top?.titleName = now }
            // Only a measured size is passed on: a title being built again keeps the last one.
            .onChange(of: drawn, initial: true) { _, now in if let now { top?.titleSize = now } }
    }
}

/// A page's big title: reports where its capitals start (`inset` below the view's top) while the
/// page is at rest, for the bar to name the page once the page has scrolled them to the line the
/// bar names it at, and fades the title out over the last 8 points before.
struct TitlePlace: ViewModifier {
    var inset: CGFloat = 0
    @Environment(PageTop.self) private var top: PageTop?

    func body(content: Content) -> some View {
        content
            .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).minY } action: { y in
                top?.titleSeen(onScreen: y + inset)
            }
            .opacity(Double(top?.titleShown ?? 1))
            .onAppear { top?.titleShowings &+= 1 }
    }
}

/// The big title's report under its earlier name, for `bigTitle()`.
typealias TitleTopReport = TitlePlace

/// A section's title inside a page: 18 point Bold, in the theme's words.
struct SectionTitle: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.octoSection)
            .foregroundStyle(OctoColor.textPrimary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
            .accessibilityAddTraits(.isHeader)
    }
}

/// How small a title's type must be for its widest unbreakable stretch to fit the line.
enum TitleFit {
    static func scale(_ text: String, size: CGFloat, weight: UIFont.Weight, tracking: CGFloat, width: CGFloat) -> CGFloat {
        guard width > 0, !text.isEmpty else { return 1 }
        var scale = 1.0
        // Type does not scale quite evenly, so it is measured again after each step.
        for _ in 0..<3 {
            let font = UIFont.systemFont(ofSize: size * scale, weight: weight)
            let step = fitScale(widest: widestStretch(breakableText(text), font: font, tracking: tracking), width: Double(width))
            if step >= 1 { break }
            scale = max(titleLeastShare, scale * step)
            if scale <= titleLeastShare { break }
        }
        return CGFloat(scale)
    }

    /// The widest stretch of the text a line may not break inside: a word, the part of one a line
    /// may end after (a hyphen's), or one character of a script written without spaces.
    static func widestStretch(_ text: String, font: UIFont, tracking: CGFloat) -> Double {
        let string = text as NSString
        let tokenizer = CFStringTokenizerCreate(nil, string as CFString, CFRange(location: 0, length: string.length),
                                                kCFStringTokenizerUnitLineBreak, nil)
        var widest = 0.0
        var kind = CFStringTokenizerAdvanceToNextToken(tokenizer)
        while kind != [] {
            let range = CFStringTokenizerGetCurrentTokenRange(tokenizer)
            let piece = string.substring(with: NSRange(location: range.location, length: range.length))
                .trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: "\u{200B}")))
            if !piece.isEmpty {
                let measured = (piece as NSString).size(withAttributes: [.font: font, .kern: tracking]).width
                widest = max(widest, Double(measured))
            }
            kind = CFStringTokenizerAdvanceToNextToken(tokenizer)
        }
        return widest
    }
}

extension View {
    /// A pushed page under the system bar: the bar's glass back button at its top left, the
    /// system's soft edge over whatever scrolls under the bar, and the page's name in the bar once
    /// the page has scrolled its big title's capitals to the status bar (on a page under artwork,
    /// to the bar's bottom). A page with `ownBack` has its title on the button's line: its
    /// `PageHeader` draws that line just above its own place, and the page's content starts where
    /// that puts the title's capitals on the button's middle. Other pages start under the status bar.
    func detailPage(ownBack: Bool = false) -> some View {
        modifier(PageBar(back: true, titleBesideBack: ownBack))
    }

    /// A tab's first page: no back button, the page starting under the status bar, and its name in
    /// the system bar once the page has scrolled its big title's capitals to the status bar.
    /// Without `showsBar` the bar stays hidden, for a page whose top never scrolls.
    func rootPage(showsBar: Bool = true) -> some View {
        modifier(PageBar(back: false, titleBesideBack: false, showsBar: showsBar))
    }

    /// Room under a page's content for the floating bar.
    func barClearance() -> some View {
        contentMargins(.bottom, ShellMeasures.clearance, for: .scrollContent)
    }
}

/// Space a page under artwork leaves at its top, clear of the bar's back button.
let detailTopGap: CGFloat = 56

/// What going back does on a page that has something to close first, like a selection or a
/// search: set by the page, run by the back button and the edge swipe in place of leaving.
@MainActor
final class BackHandler: Equatable {
    var run: () -> Void = {}

    /// The handler of the page in front, for the edge swipe.
    static weak var current: BackHandler?

    nonisolated static func == (a: BackHandler, b: BackHandler) -> Bool { a === b }
}

struct BackHandlerKey: PreferenceKey {
    static var defaultValue: BackHandler? { nil }

    static func reduce(value: inout BackHandler?, nextValue: () -> BackHandler?) {
        value = nextValue() ?? value
    }
}

extension View {
    /// While `run` is set, going back runs it instead of leaving the page.
    func backAction(_ run: (() -> Void)?) -> some View {
        modifier(BackActionSetter(run: run))
    }
}

private struct BackActionSetter: ViewModifier {
    let run: (() -> Void)?
    @State private var handler = BackHandler()

    func body(content: Content) -> some View {
        if let run { handler.run = run }
        return content.preference(key: BackHandlerKey.self, value: run == nil ? nil : handler)
    }
}

/// Where the system bar puts its back button's middle, from the bar's top left corner, and how
/// tall the bar is: the same on every page of a phone. Read from a bar shown at full size and held
/// steady for a moment, and kept for later launches.
@MainActor
@Observable
final class BarMetrics {
    static let shared = BarMetrics()
    private(set) var back: CGPoint
    private(set) var height: CGFloat
    /// Whether these come from this phone's bar (kept from an earlier launch, or read now).
    private(set) var measured: Bool
    private static let key = "barMetrics"

    private init() {
        if let saved = UserDefaults.standard.array(forKey: Self.key) as? [Double], saved.count == 3 {
            back = CGPoint(x: saved[0], y: saved[1])
            height = saved[2]
            measured = true
        } else {
            back = Self.firstBack
            height = Self.firstHeight
            measured = false
        }
    }

    /// What a phone starts from before its bar has been read.
    private static let firstBack = CGPoint(x: 38, y: 21.7)
    private static let firstHeight: CGFloat = 54

    /// A reading from a bar at full size, kept when it differs from what is held or nothing has
    /// been read on this phone yet.
    func take(_ reading: BarReading) {
        guard !measured || abs(reading.back.x - back.x) > 0.5 || abs(reading.back.y - back.y) > 0.5
            || abs(reading.height - height) > 0.5 else { return }
        back = reading.back
        height = reading.height
        measured = true
        UserDefaults.standard.set([Double(back.x), Double(back.y), Double(height)], forKey: Self.key)
    }

    #if DEBUG
    /// Forgets this phone's bar, as on a first launch.
    func forget() {
        back = Self.firstBack
        height = Self.firstHeight
        measured = false
        UserDefaults.standard.removeObject(forKey: Self.key)
    }
    #endif
}

/// The back button's middle from the bar's top left corner, and the bar's height.
struct BarReading: Equatable {
    let back: CGPoint
    let height: CGFloat
}

/// The page's whole extent on screen, and its own size.
struct PageFrame: Equatable {
    let screen: CGRect
    let size: CGSize
}

/// Where a page's top parts are, so its title line can sit on the system back button's middle
/// and the bar can name the page once it has scrolled its big title away. Places are read on
/// screen and turned into the page's own points with a clear leaf that fills the screen (a tab
/// switch scales a whole tab, and a push moves the page); how far the page has scrolled is read
/// from its scroll view.
@MainActor
@Observable
final class PageTop {
    /// Whether the page has a back button, and whether its title line sits beside it.
    let hasBack: Bool
    let titleBesideBack: Bool
    /// The status bar's height.
    var status: CGFloat = PageTop.windowTop() {
        didSet {
            measureGap()
            updateGone()
        }
    }
    /// The page's whole extent on screen.
    var page: PageFrame? { didSet { measureGap() } }
    /// Where the page's layout puts its `PageHeader` on screen, and the header's place below the
    /// content's top, taken once with the page at rest.
    var headerSlot: CGFloat? { didSet { measureGap() } }
    var headerGap: CGFloat?
    /// The header's left edge in the page (a list may inset its rows).
    var headerLeft: CGFloat = 0
    /// Whether the header keeps its line's height (in a list whose rows clip what overflows them).
    var headerHoldsHeight = false
    /// The title line's height, and the middle of the title's capitals below the line's top.
    var lineHeight: CGFloat?
    var lineInHeader: CGFloat?
    /// The page's name for the bar: its own, from `inlineTitle`, else its big title's.
    var name: String?
    var titleName: String?
    /// The size the big title is drawn at, once its width is known.
    var titleSize: CGFloat?
    /// How far the page has scrolled from its top.
    var scrolled: CGFloat = 0 { didSet { updateGone() } }
    /// Where the big title's capitals start in the page at rest.
    var titleRest: CGFloat? { didSet { updateGone() } }
    /// The page has scrolled its big title away, and how much of the title still shows.
    private(set) var gone = false
    private(set) var titleShown: CGFloat = 1
    /// Counts the big title's appearances, so the page's scroll view is looked for again when a
    /// page swaps it.
    var titleShowings = 0
    /// The page showed before it was measured, after waiting too long.
    var waited = false
    #if DEBUG
    /// Where the button's room is drawn on screen, how many times the content's top moved while
    /// the page showed, and what moved last (readout).
    var roomOnScreen: CGPoint?
    var moves = 0
    var lastMove = ""
    #endif

    init(back: Bool, titleBesideBack: Bool) {
        self.hasBack = back
        self.titleBesideBack = titleBesideBack
    }

    /// The status bar's height from the window, known before anything is laid out.
    static func windowTop() -> CGFloat {
        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?.safeAreaInsets.top ?? 0
    }

    /// A height read on screen, in the page's own points.
    func own(_ y: CGFloat) -> CGFloat {
        guard let page, page.screen.height > 0, page.size.height > 0 else { return y }
        return (y - page.screen.minY) * page.size.height / page.screen.height
    }

    /// A left edge read on screen, in the page's own points.
    func ownX(_ x: CGFloat) -> CGFloat {
        guard let page, page.screen.width > 0, page.size.width > 0 else { return x }
        return (x - page.screen.minX) * page.size.width / page.screen.width
    }

    /// The system back button's middle.
    var back: CGPoint { CGPoint(x: BarMetrics.shared.back.x, y: status + BarMetrics.shared.back.y) }

    /// The system bar's bottom.
    var barBottom: CGFloat { status + BarMetrics.shared.height }

    /// Where the page's scrolling content starts. A page whose title shares the back button's
    /// line draws that line just above its header's place (or, holding the line's height, at its
    /// place), so its content starts where that puts the title's capitals on the button's middle
    /// (until measured, 8 under the bar). Other pages start under the status bar.
    var contentTop: CGFloat {
        guard titleBesideBack else { return status }
        guard let headerGap, let lineInHeader, headerHoldsHeight || lineHeight != nil else { return barBottom + 8 }
        return back.y - lineInHeader + (headerHoldsHeight ? 0 : lineHeight ?? 0) - headerGap
    }

    /// Whether the page can be shown where it belongs.
    var settled: Bool {
        waited || (status > 0 && (!titleBesideBack
            || (BarMetrics.shared.measured && headerGap != nil && lineInHeader != nil
                && (headerHoldsHeight || lineHeight != nil) && titleSize != nil)))
    }

    /// The line the big title's capitals reach when the bar names the page: the status bar, or on
    /// a page under artwork the bar's bottom, so the title never passes beside the back button.
    var nameLine: CGFloat { hasBack && !titleBesideBack ? barBottom : status }

    /// What the bar's title shows: the page's name once the big title has gone, and nothing
    /// before, since a named bar softens everything under it, line one too.
    var barTitle: String { gone ? (name ?? titleName ?? "") : "" }

    /// The largest big title whose capitals, on the button's middle, stay 4 points under the
    /// status bar.
    var titleRoom: CGFloat? {
        guard titleBesideBack, status > 0 else { return nil }
        let capShare = UIFont.systemFont(ofSize: 100, weight: .black).capHeight / 100
        return 2 * (back.y - status - 4) / capShare
    }

    /// The big title read on screen: kept as its place at rest while the page has not scrolled
    /// (a list may stop reporting its rows while it scrolls).
    func titleSeen(onScreen y: CGFloat) {
        guard scrolled.magnitude < 0.5 else { return }
        let rest = own(y)
        if titleRest != rest { titleRest = rest }
    }

    #if DEBUG
    /// The things the content's top follows (readout).
    var inputs: String {
        func text(_ value: CGFloat?) -> String { value.map { String(format: "%.1f", $0) } ?? "-" }
        return "gap \(text(headerGap)) line \(text(lineInHeader))/\(text(lineHeight)) status \(text(status)) back \(text(back.y))"
    }
    #endif

    /// Takes the header's gap the first time the header is seen with the page measured and the
    /// status bar known: the page is at rest then, and its content starts 8 under the bar.
    private func measureGap() {
        guard headerGap == nil, let headerSlot, page != nil, status > 0 else { return }
        headerGap = own(headerSlot) - (barBottom + 8)
    }

    private func updateGone() {
        guard let titleRest else {
            if gone { gone = false }
            if titleShown != 1 { titleShown = 1 }
            return
        }
        // The fade takes the last 8 points, or the whole way when the title starts nearer.
        let fade = min(8, max(0, titleRest - nameLine))
        let left = titleRest - scrolled - nameLine
        let nowGone = left < 0
        let nowShown: CGFloat = left >= fade ? 1 : (fade > 0 ? max(0, left / fade) : 0)
        if nowGone != gone { gone = nowGone }
        if nowShown != titleShown { titleShown = nowShown }
    }
}

/// A page under the system bar: the bar holds the back button on a pushed page and the page's
/// name once the page has scrolled its big title away; the page runs under the bar, where the
/// system softens whatever scrolls there. The page waits unseen until it can be drawn where it
/// belongs (a fraction of a second at most), and once shown it stays shown.
private struct PageBar: ViewModifier {
    let back: Bool
    var showsBar = true
    @Environment(\.dismiss) private var dismiss
    @State private var handler: BackHandler?
    @State private var top: PageTop
    @State private var shown = false
    @State private var backOnScreen: CGPoint?
    @State private var barOnScreen: ProbeReading?
    #if DEBUG
    @State private var movedFrom = ""
    /// The content's top when the page settled, to count later moves against.
    @State private var settledTop: CGFloat?
    #endif

    init(back: Bool, titleBesideBack: Bool, showsBar: Bool = true) {
        self.back = back
        self.showsBar = showsBar
        _top = State(initialValue: PageTop(back: back, titleBesideBack: titleBesideBack))
    }

    func body(content: Content) -> some View {
        content
            .environment(top)
            // While the title shares the button's line it sits where the band would soften it.
            .scrollEdgeEffectStyle(.soft, for: .top)
            .scrollEdgeEffectHidden(top.titleBesideBack && !top.gone, for: .top)
            .safeAreaPadding(.top, max(0, top.contentTop))
            // A start above the screen's top is reached by moving the page up, the page made as
            // much taller so it still reaches the screen's bottom.
            .padding(.bottom, min(0, top.contentTop))
            .offset(y: min(0, top.contentTop))
            .ignoresSafeArea(.container, edges: .top)
            .opacity(shown || top.settled ? 1 : 0)
            .background {
                // The page's whole extent on screen: a leaf that fills the screen, moved by a push
                // and scaled by a tab switch with the page.
                Color.clear
                    .onGeometryChange(for: PageFrame.self) { PageFrame(screen: $0.frame(in: .global), size: $0.size) } action: { frame in
                        top.page = frame
                    }
                    .ignoresSafeArea()
            }
            .background {
                BarFrameProbe(showings: top.titleShowings) { reading in
                    if top.status == 0, reading.windowTop > 0 { top.status = reading.windowTop }
                    if barOnScreen != reading { barOnScreen = reading }
                } scrolled: { offset in
                    if top.scrolled != offset { top.scrolled = offset }
                }
            }
            #if DEBUG
            .overlay(alignment: .bottom) { if PageBarReadout.shown.on { PageBarReadout(top: top) } }
            #endif
            .navigationBarBackButtonHidden(true)
            .navigationTitle(top.barTitle)
            .toolbarTitleDisplayMode(.inline)
            .toolbar(showsBar ? .visible : .hidden, for: .navigationBar)
            .toolbar {
                if back {
                    ToolbarItem(placement: .topBarLeading) {
                        Button(action: goBack) { IconView(.back, size: 20, color: OctoColor.textPrimary) }
                            .accessibilityLabel("Back")
                            .onGeometryChange(for: CGPoint.self) { CGPoint(x: $0.frame(in: .global).midX, y: $0.frame(in: .global).midY) } action: { backOnScreen = $0 }
                    }
                }
            }
            .task(id: reading) {
                // The first reading on a phone is taken at once; later ones must hold steady for a
                // moment, from a bar at full size.
                guard let reading else { return }
                if !BarMetrics.shared.measured {
                    BarMetrics.shared.take(reading)
                    return
                }
                try? await Task.sleep(for: .milliseconds(300))
                guard !Task.isCancelled else { return }
                BarMetrics.shared.take(reading)
            }
            .onChange(of: top.settled, initial: true) { _, settled in if settled { shown = true } }
            #if DEBUG
            .onChange(of: top.contentTop) { _, now in
                guard let settledTop, now != settledTop else { return }
                top.moves += 1
                top.lastMove = "\(movedFrom) -> \(top.inputs)"
                self.settledTop = now
                movedFrom = top.inputs
            }
            .onChange(of: top.settled, initial: true) { _, settled in
                guard settled, settledTop == nil else { return }
                settledTop = top.contentTop
                movedFrom = top.inputs
            }
            #endif
            .onAppear { if top.status == 0 { top.status = PageTop.windowTop() } }
            .task {
                try? await Task.sleep(for: .milliseconds(300))
                guard !Task.isCancelled else { return }
                if !top.settled { top.waited = true }
            }
            .onPreferenceChange(BackHandlerKey.self) { value in
                guard back else { return }
                Task { @MainActor in
                    handler = value
                    BackHandler.current = value
                }
            }
            .onDisappear { if back, BackHandler.current === handler { BackHandler.current = nil } }
    }

    /// The back button's place in the bar, read while the bar is at full size (a tab switch
    /// scales it).
    private var reading: BarReading? {
        guard let backOnScreen, let barOnScreen, abs(barOnScreen.bar.width - barOnScreen.windowWidth) < 0.5 else { return nil }
        return BarReading(back: CGPoint(x: backOnScreen.x - barOnScreen.bar.minX, y: backOnScreen.y - barOnScreen.bar.minY),
                          height: barOnScreen.bar.height)
    }

    private func goBack() {
        if let handler { handler.run() } else { dismiss() }
    }
}

/// The system bar's frame on screen, the window's width and the window's top safe area.
struct ProbeReading: Equatable {
    let bar: CGRect
    let windowWidth: CGFloat
    let windowTop: CGFloat
}

/// Reads the system bar's frame on screen, from the navigation controller showing the page, and
/// how far the page's own scroll view has scrolled from its top.
private struct BarFrameProbe: UIViewRepresentable {
    /// The big title's appearances: a new one may come with a new scroll view.
    var showings = 0
    let report: (ProbeReading) -> Void
    let scrolled: (CGFloat) -> Void

    func makeUIView(context: Context) -> ProbeView { ProbeView(report: report, scrolled: scrolled) }

    func updateUIView(_ view: ProbeView, context: Context) {
        view.report = report
        view.scrolled = scrolled
        view.measure()
        if view.showings != showings {
            view.showings = showings
            // The new scroll view joins the window after this update.
            DispatchQueue.main.async { view.measure() }
        }
    }

    final class ProbeView: UIView {
        var report: (ProbeReading) -> Void
        var scrolled: (CGFloat) -> Void
        var showings = 0
        private var last: ProbeReading?
        private weak var scroll: UIScrollView?
        private var watch: NSKeyValueObservation?

        init(report: @escaping (ProbeReading) -> Void, scrolled: @escaping (CGFloat) -> Void) {
            self.report = report
            self.scrolled = scrolled
            super.init(frame: .zero)
            isUserInteractionEnabled = false
        }

        required init?(coder: NSCoder) { nil }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            measure()
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            measure()
        }

        func measure() {
            guard let window else { return }
            var next: UIResponder? = self
            while let responder = next {
                if let controller = responder as? UIViewController, let bar = controller.navigationController?.navigationBar {
                    let reading = ProbeReading(bar: bar.convert(bar.bounds, to: window), windowWidth: window.bounds.width,
                                               windowTop: window.safeAreaInsets.top)
                    follow(pageScroll(in: controller.view))
                    guard reading != last else { return }
                    last = reading
                    let report = report
                    DispatchQueue.main.async { report(reading) }
                    return
                }
                next = responder.next
            }
        }

        /// The page's own scroll view: the first one that scrolls up and down and is at least half
        /// as tall as the page (a shelf of covers scrolls sideways).
        private func pageScroll(in root: UIView?) -> UIScrollView? {
            guard let root else { return nil }
            var queue: [UIView] = [root]
            while !queue.isEmpty {
                let view = queue.removeFirst()
                if let found = view as? UIScrollView, found.bounds.height >= root.bounds.height / 2,
                   found.contentSize.width <= found.bounds.width + 1 {
                    return found
                }
                queue.append(contentsOf: view.subviews)
            }
            return nil
        }

        /// Watches a page's scroll view, reporting how far it has scrolled from its top; one that
        /// is gone, or no longer in the window, is let go.
        private func follow(_ found: UIScrollView?) {
            guard let found else {
                if scroll != nil || watch != nil {
                    scroll = nil
                    watch = nil
                    scrolled(0)
                }
                return
            }
            guard found !== scroll || scroll?.window == nil else { return }
            scroll = found
            watch = found.observe(\.contentOffset, options: [.initial, .new]) { [weak self] view, _ in
                MainActor.assumeIsolated {
                    self?.scrolled(view.contentOffset.y + view.adjustedContentInset.top)
                }
            }
        }
    }
}

/// With the bar's own back button replaced by the page's, the edge swipe back still works.
extension UINavigationController: @retroactive UIGestureRecognizerDelegate {
    override open func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.delegate = self
    }

    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard viewControllers.count > 1 else { return false }
        if let handler = MainActor.assumeIsolated({ BackHandler.current }) {
            MainActor.assumeIsolated { handler.run() }
            return false
        }
        return true
    }
}
