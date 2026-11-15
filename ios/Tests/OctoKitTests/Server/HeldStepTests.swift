import OctoKit
import Testing

/// Which links a held sign-up or received sign-in refuses: each works once, so while one is held only
/// its own invite or hand-over, taken for the same server, is taken again.
@Suite struct HeldStepTests {
    private static let server = "https://music.example.com"
    private static let invite = FamilyInviteLink(server: server, token: "invite-1")
    private static let handOver = FamilyHandOverLink(base: server, token: "hand-1", key: "key-1", server: server)

    /// A held sign-up: its account is made, its invite spent.
    private static func signUpHeld(_ link: FamilyLink, _ take: LinkTake) -> Bool {
        heldStepRefuses(link, take: take, signedUp: true, invite: invite, received: false, handOver: nil)
    }

    /// A held received sign-in: its hand-over is redeemed.
    private static func receivedHeld(_ link: FamilyLink, _ take: LinkTake) -> Bool {
        heldStepRefuses(link, take: take, signedUp: false, invite: nil, received: true, handOver: handOver)
    }

    /// Red when an invite with another token, taken for the same server, gets past a held sign-up,
    /// which the take would then drop or sign in with the wrong account.
    @Test func anotherTokenOnTheSameServerIsRefusedWhileASignUpIsHeld() {
        let other = FamilyLink.invite(FamilyInviteLink(server: Self.server, token: "invite-2"))
        #expect(Self.signUpHeld(other, .keep))
        #expect(Self.signUpHeld(other, .fitting))
        // Nor does a hand-over or a sign-in to fill in for that server get past it.
        #expect(Self.signUpHeld(.handOver(Self.handOver), .keep))
        #expect(Self.signUpHeld(.prefill(FamilySignInPrefill(server: Self.server, username: "ana")), .keep))
    }

    /// Red when the held invite's own token, taken as another server (a forget), gets past a held
    /// sign-up, whose account belongs to the server it was made at.
    @Test func theSameTokenOnAnotherServerIsRefusedWhileASignUpIsHeld() {
        let moved = FamilyLink.invite(FamilyInviteLink(server: "https://other.example.com", token: "invite-1"))
        #expect(Self.signUpHeld(moved, .forget))
        // The same link read as another server, whatever its address says.
        #expect(Self.signUpHeld(.invite(Self.invite), .forget))
    }

    /// Red when a received sign-in lets another hand-over, an invite, a sign-in to fill in, or its own
    /// hand-over taken as another server past it.
    @Test func aReceivedSignInRefusesEveryOtherLink() {
        let another = FamilyLink.handOver(FamilyHandOverLink(base: Self.server, token: "hand-2", key: "key-2", server: Self.server))
        #expect(Self.receivedHeld(another, .keep))
        #expect(Self.receivedHeld(another, .fitting))
        #expect(Self.receivedHeld(.handOver(Self.handOver), .forget))
        // An invite whose token reads as the hand-over's is still not the held step's own link.
        #expect(Self.receivedHeld(.invite(FamilyInviteLink(server: Self.server, token: "hand-1")), .keep))
        #expect(Self.receivedHeld(.prefill(FamilySignInPrefill(server: Self.server)), .fitting))
    }

    /// A guard: red when the held step's own link for the same server is refused, so opening it again
    /// could never go on with what it already made.
    @Test func theHeldStepsOwnLinkForTheSameServerIsTaken() {
        for take in [LinkTake.keep, .fitting] {
            #expect(!Self.signUpHeld(.invite(Self.invite), take), "\(take)")
            #expect(!Self.receivedHeld(.handOver(Self.handOver), take), "\(take)")
        }
    }

    /// A guard: red when a link is refused with nothing held, as while an invite is shown but not yet
    /// signed up for, or a hand-over waits for Allow.
    @Test func nothingHeldRefusesNothing() {
        let links: [FamilyLink] = [.invite(Self.invite), .invite(FamilyInviteLink(server: Self.server, token: "invite-2")),
                                   .handOver(Self.handOver), .prefill(FamilySignInPrefill(server: Self.server))]
        for take in [LinkTake.keep, .fitting, .forget] {
            for link in links {
                #expect(!heldStepRefuses(link, take: take, signedUp: false, invite: Self.invite, received: false, handOver: Self.handOver),
                        "\(link) \(take)")
            }
        }
    }
}
