import Foundation
import OctoKit
import Testing

/// One change of password at a time from every form that sends one, and where its answer is said.
@MainActor
@Suite struct PasswordChangesTests {
    /// A server the row answers by hand: it holds every change until `answer`, which answers them
    /// all and every later one at once. A change that should never have gone out then shows as a
    /// count, never as a change left waiting.
    @MainActor
    final class Server {
        private(set) var sent = 0
        private var waiting: [CheckedContinuation<PasswordChange, Never>] = []
        private var answered: PasswordChange?
        var holding: Bool { !waiting.isEmpty }

        func send() async -> PasswordChange {
            sent += 1
            if let answered { return answered }
            return await withCheckedContinuation { waiting.append($0) }
        }

        func answer(_ result: PasswordChange) {
            answered = result
            for one in waiting { one.resume(returning: result) }
            waiting = []
        }
    }

    struct Said: Equatable {
        let words: String
        let done: Bool
    }

    /// What the form was offered and what the app's notice said.
    @MainActor
    final class Heard {
        var offered: [String] = []
        var notices: [Said] = []

        /// The form's answer: it takes the words while `shown`.
        func form(_ shown: Bool) -> @MainActor (PasswordChange, String) -> Bool {
            { [self] _, words in
                offered.append(words)
                return shown
            }
        }

        var notice: @MainActor (String, Bool) -> Void {
            { [self] words, done in notices.append(Said(words: words, done: done)) }
        }
    }

    /// Red when a second change goes out while one is on its way, one asked for in the same moment
    /// (before the first has reached the server) included, or when the claim outlives its answer.
    @Test func aSecondChangeWhileOneRunsSendsNothing() async {
        let changes = PasswordChanges()
        let server = Server()
        let heard = Heard()
        let first = changes.change({ await server.send() }, words: { passwordChangeWords($0) }, answered: heard.form(true), notice: heard.notice)
        #expect(first != nil)
        #expect(changes.running)
        let sameMoment = changes.change({ await server.send() }, words: { passwordChangeWords($0) }, answered: heard.form(true), notice: heard.notice)
        #expect(sameMoment == nil)
        #expect(await until("the first change reaches the server") { server.holding })
        let meanwhile = changes.change({ await server.send() }, words: { passwordChangeWords($0) }, answered: heard.form(true), notice: heard.notice)
        #expect(meanwhile == nil)
        #expect(server.sent == 1)
        server.answer(.wrongCurrent)
        await first?.value
        #expect(!changes.running)
        #expect(server.sent == 1)
        #expect(heard.offered == [passwordChangeWords(.wrongCurrent)])
        // Once it answered, the next change goes.
        let after = Server()
        after.answer(.changed)
        let next = changes.change({ await after.send() }, words: { passwordChangeWords($0) }, answered: heard.form(true), notice: heard.notice)
        #expect(next != nil)
        await next?.value
        #expect(after.sent == 1)
        #expect(!changes.running)
    }

    /// Red when a refusal is lost with its form, or said in the notice over a form that shows it.
    @Test func aRefusalGoesToItsFormWhileItIsShownAndToTheNoticeOnceItIsGone() async {
        let words = passwordChangeWords(.wrongCurrent, family: true)
        for shown in [true, false] {
            let changes = PasswordChanges()
            let heard = Heard()
            let run = changes.change({ .wrongCurrent }, words: { passwordChangeWords($0, family: true) }, answered: heard.form(shown),
                                     notice: heard.notice)
            await run?.value
            #expect(heard.offered == [words], "shown: \(shown)")
            #expect(heard.notices == (shown ? [] : [Said(words: words, done: false)]), "shown: \(shown)")
            #expect(!changes.running)
        }
    }

    /// Red when a change that worked goes unsaid: the notice says it, with the check, whether or not
    /// its form is still there to close.
    @Test func aChangeThatWorkedIsAlwaysSaid() async {
        let words = passwordChangeWords(.changed)
        for shown in [true, false] {
            let changes = PasswordChanges()
            let heard = Heard()
            let run = changes.change({ .changed }, words: { passwordChangeWords($0) }, answered: heard.form(shown), notice: heard.notice)
            await run?.value
            #expect(heard.offered == [words], "shown: \(shown)")
            #expect(heard.notices == [Said(words: words, done: true)], "shown: \(shown)")
            #expect(!changes.running)
        }
    }
}

/// Where the app's two password forms (Settings' Server page, Family's sheet) send a change through
/// the one `PasswordChanges` and keep their sheets while it runs, checked in their sources.
@Suite struct PasswordChangeSitesTests {
    private static let settings = "Octo/Settings/Server/ChangePassword.swift"
    private static let family = "Octo/Family/FamilySheets.swift"

    /// The Family sheet's own code.
    private static func familySheet() throws -> String {
        try SourceText.slice(try SourceText.read(family), from: "struct FamilyPasswordSheet: View {", to: "\n}\n")
    }

    /// Red when a form keeps a busy flag of its own (a sheet opened again starts with it off), sends
    /// the change itself instead of through the app's one `PasswordChanges`, or Done gets past a
    /// change on its way from either form.
    @Test func bothFormsSendThroughTheOneChangeOnItsWay() throws {
        let session = SourceText.squashed(try SourceText.read("Octo/App/Session.swift"))
        #expect(session.contains("@ObservationIgnored let passwordChanges = PasswordChanges()"))
        let forms: [(name: String, text: String, send: String, done: String)] = [
            ("settings", try SourceText.read(Self.settings), "await session.changePassword(current: draft.current, new: draft.new, family: family)",
             "default: change()"),
            ("family", try Self.familySheet(), "await change(draft.current, draft.new)", "if index == 2 { go() } else { typing = index + 1 }"),
        ]
        for form in forms {
            let code = SourceText.squashed(form.text)
            #expect(code.contains("private var busy: Bool { session.passwordChanges.running }"), "\(form.name)")
            #expect(!code.contains("@State private var busy"), "\(form.name)")
            #expect(!code.contains("busy = "), "\(form.name)")
            #expect(!code.contains("Task {"), "\(form.name)")
            #expect(code.contains("{ guard !busy else { return } if let problem = passwordDraftProblem(draft) {"), "\(form.name)")
            #expect(code.contains("session.passwordChanges.change { \(form.send) } words:"), "\(form.name)")
            #expect(SourceText.count("passwordChanges.change {", in: code) == 1, "\(form.name)")
            #expect(code.contains(form.done), "\(form.name)")
        }
        // Nothing else sends a change: the Settings form's call and the one Family's sheet is handed.
        var senders: [String: Int] = [:]
        for source in try ServerCallSourceTests.sources() where source.path.hasPrefix("Octo/") {
            let calls = SourceText.count(".changePassword(current:", in: source.text)
            if calls > 0 { senders[source.path] = calls }
        }
        #expect(senders == [Self.settings: 1, "Octo/Family/FamilyHub.swift": 1])
        let screen = SourceText.squashed(try SourceText.read("Octo/Family/FamilyScreen.swift"))
        #expect(screen.contains("FamilyPasswordSheet(change: hub.changePassword, close: { changingPassword = false })"))
        #expect(SourceText.count("hub.changePassword", in: screen) == 1)
    }

    /// Red when a sheet can be swiped away, closed from its Cancel or Family's Close while a change
    /// is on its way, so a sheet opened again could send a second one.
    @Test func aChangeOnItsWayKeepsItsSheet() throws {
        for (name, text) in [("settings", try SourceText.read(Self.settings)), ("family", try Self.familySheet())] {
            let code = SourceText.squashed(text)
            #expect(code.contains(".interactiveDismissDisabled(busy)"), "\(name)")
            #expect(code.contains("GlazeButton(\"Cancel\", size: .medium, enabled: !busy, fillWidth: true, action: leave)"), "\(name)")
            #expect(code.contains("private func leave() { guard !busy else { return } close() }"), "\(name)")
        }
        let family = SourceText.squashed(try Self.familySheet())
        #expect(family.contains(
            "SheetHeader(title: FamilyText.changePassword, subtitle: FamilyText.signOutEverywhereLine, clock: false, closeEnabled: !busy, close: leave)"))
        let parts = SourceText.squashed(try SourceText.read("Octo/Family/FamilySheetParts.swift"))
        #expect(parts.contains(
            "Button(action: close) { Image(systemName: \"xmark\")"))
        #expect(parts.contains(".buttonStyle(.plain) .opacity(closeEnabled ? 1 : OctoInkColor.disabled) .disabled(!closeEnabled) .accessibilityLabel(\"Close\")"))
    }

    /// Red when an answer is written into a form that is gone (a refusal there is never seen, and its
    /// close would shut the sheet opened since), or a form says a change that worked by itself
    /// instead of through the notice.
    @Test func anAnswerGoesToItsFormOnlyWhileItIsOnScreen() throws {
        let family = SourceText.squashed(try Self.familySheet())
        #expect(family.contains("words: { result in passwordChangeWords(result, family: true) }"))
        let settings = SourceText.squashed(try SourceText.read(Self.settings))
        #expect(settings.contains("words: { result in passwordChangeWords(result) }"))
        #expect(settings.contains("@MainActor final class OnScreen { var shown = true }"))
        for (name, code) in [("settings", settings), ("family", family)] {
            #expect(code.contains("@State private var onScreen = OnScreen()"), "\(name)")
            #expect(code.contains(".onAppear { onScreen.shown = true } .onDisappear { onScreen.shown = false }"), "\(name)")
            #expect(code.contains(
                "answered: { result, words in guard onScreen.shown else { return false } if result == .changed { close() } else { problem = words } return true } notice: { words, done in router.show(words, done: done) }"),
                "\(name)")
            #expect(!code.contains("router.done("), "\(name)")
            #expect(SourceText.count("router.show(", in: code) == 1, "\(name)")
        }
    }
}
