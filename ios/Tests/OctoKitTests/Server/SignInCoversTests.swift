import Foundation
import OctoKit
import Testing

/// What the router keeps about the sign-in covers, as OctoKit decides it: which forms have a sign-in
/// open, the cover an ask opened, and which kept server an ask is about.
@Suite struct SignInCoversTests {
    private final class Form {}

    private static let home = StoredServer(id: "home", address: URL(string: "https://music.example.com")!, username: "ana")
    private static let work = StoredServer(id: "work", label: "Work", address: URL(string: "https://work.example.com")!, username: "ana")

    /// Red when a form's mark is lost, a form closing clears another form's mark, or a closed form
    /// still counts as open.
    @Test func eachFormsMarkIsItsOwn() {
        var open = OpenSignIns<Form>()
        let first = Form()
        let second = Form()
        let atStart = open.anyOpen()
        #expect(!atStart)
        open.mark(first, open: true)
        open.mark(second, open: true)
        open.mark(second, open: false)
        let oneLeft = open.anyOpen()
        #expect(oneLeft)
        #expect(open.forms.count == 1)
        #expect(open.forms.first === first)
        // Marked open twice, closed once: closed.
        open.mark(first, open: true)
        open.mark(first, open: false)
        let noneLeft = open.anyOpen()
        #expect(!noneLeft)
        #expect(open.forms.isEmpty)
    }

    /// Red when a form gone without its last mark still keeps links out, or dropping it drops a form
    /// still alive.
    @Test func aFormGoneWithoutItsLastMarkIsDropped() {
        var alone = OpenSignIns<Form>()
        var only: Form? = Form()
        alone.mark(only!, open: true)
        only = nil
        #expect(only == nil)
        let aloneOpen = alone.anyOpen()
        #expect(!aloneOpen)
        #expect(alone.forms.isEmpty)

        var both = OpenSignIns<Form>()
        let kept = Form()
        var gone: Form? = Form()
        both.mark(kept, open: true)
        both.mark(gone!, open: true)
        gone = nil
        #expect(gone == nil)
        let bothOpen = both.anyOpen()
        #expect(bothOpen)
        #expect(both.forms.count == 1)
        #expect(both.forms.first === kept)
    }

    /// Red when an ask's cover stays the ask's once another cover or none shows, or is ended while it
    /// still shows.
    @Test func anAsksCoverIsItsOwnOnlyWhileItShows() {
        #expect(askedCoverKept("again.work", showing: "again.work") == "again.work")
        #expect(askedCoverKept("again.work", showing: "again.home") == nil)
        #expect(askedCoverKept("again.work", showing: "join.1.octo://join") == nil)
        #expect(askedCoverKept("again.work", showing: nil) == nil)
        #expect(askedCoverKept(nil, showing: "again.work") == nil)
        #expect(askedCoverKept(nil, showing: nil) == nil)
    }

    /// Red when an ask runs for a server no longer kept or for the one in use and signed in, is dropped
    /// for a kept server the phone is not signed in to, or names the server as it was when asked.
    @Test func anAskIsDroppedOnlyForAGoneServerOrTheOneSignedIn() {
        let servers = AskServers(kept: [Self.home, Self.work], activeId: "home", signedIn: true)
        #expect(servers.target("home") == nil)
        #expect(servers.target("work") == Self.work)
        #expect(servers.target("gone") == nil)
        // In use but not signed in, as when app start did not sign in to it again: asked.
        #expect(AskServers(kept: [Self.home], activeId: "home", signedIn: false).target("home") == Self.home)
        // Signed in to another server: asked.
        #expect(AskServers(kept: [Self.home, Self.work], activeId: "work", signedIn: true).target("home") == Self.home)
        // The server as kept now.
        var renamed = Self.work
        renamed.label = "Office"
        #expect(AskServers(kept: [renamed], activeId: nil, signedIn: false).target("work")?.name == "Office")
    }

    /// Red when a cover the person opened leaves an ask's message, an ask's cover no longer showing
    /// leaves one, or one is left for a server the ask would drop.
    @Test func onlyAnAsksOwnCoverLeavesItsMessage() {
        let servers = AskServers(kept: [Self.home, Self.work], activeId: "home", signedIn: true)
        #expect(askLeftOnReplace(asked: "again.work", showing: "again.work", server: "work", servers: servers) == Self.work)
        // A cover the person opened.
        #expect(askLeftOnReplace(asked: nil, showing: "again.work", server: "work", servers: servers) == nil)
        // The ask's cover was replaced by one the person opened.
        #expect(askLeftOnReplace(asked: "again.home", showing: "again.work", server: "work", servers: servers) == nil)
        // The ask's server is the one in use and signed in now, or is gone.
        #expect(askLeftOnReplace(asked: "again.home", showing: "again.home", server: "home", servers: servers) == nil)
        #expect(askLeftOnReplace(asked: "again.gone", showing: "again.gone", server: "gone", servers: servers) == nil)
    }
}
