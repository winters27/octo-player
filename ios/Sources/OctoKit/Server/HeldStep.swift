import Foundation

/// Whether a family link is refused because a step an earlier link left is held: a finished sign-up
/// (`signedUp`, made from `invite`) or a received sign-in (`received`, from `handOver`), each of which
/// works only once. While one is held, only that step's own invite or hand-over, taken for the same
/// server (a fitting or keep take), is taken again; any other link, with another token or for another
/// server, is refused and the held step stays as it is. With nothing held, nothing is refused here.
public func heldStepRefuses(_ link: FamilyLink, take: LinkTake, signedUp: Bool, invite: FamilyInviteLink?, received: Bool,
                            handOver: FamilyHandOverLink?) -> Bool {
    guard signedUp || received else { return false }
    let own: Bool = switch link {
    case .invite(let next): signedUp && next.token == invite?.token
    case .handOver(let next): received && next.token == handOver?.token
    case .prefill: false
    }
    return !own || take == .forget
}
