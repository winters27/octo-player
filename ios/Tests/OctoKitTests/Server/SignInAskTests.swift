import OctoKit
import Testing

@Suite struct SignInAskTests {
    /// Red when an ask opens its form over an open sign-in or over a link's form, or never opens it.
    @Test func anAskOpensItsFormOnlyWhenNothingWouldBeReplacedOrBuried() {
        #expect(askOpens(hasOpenSignIn: false, showingJoin: false))
        #expect(!askOpens(hasOpenSignIn: true, showingJoin: false))
        #expect(!askOpens(hasOpenSignIn: false, showingJoin: true))
        #expect(!askOpens(hasOpenSignIn: true, showingJoin: true))
    }
}
