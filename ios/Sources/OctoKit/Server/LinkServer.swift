import Foundation

// Whether a family link may take a form to its server, and what of the form it keeps. Every app's
// form keeps one LinkFormState, feeds it the form's events and asks it before taking a link or a
// received sign-in; it compares only addresses that came from a link, a received sign-in, a saved
// server or the app's own built address, never the address box's text. The shared core's
// LinkServer.kt is the same, and both run fixtures/gateway/signin-vectors.json.

/// A form opened for a kept server (a sign-in again, an edit, an edit of the connection), or any
/// other.
public enum LinkForm: String, Sendable {
    case fresh = "Fresh", keptServer = "KeptServer"
}

/// The saved server a form was filled from: its main and home addresses as saved, and its
/// username.
public struct LinkAnchor: Equatable, Sendable {
    public var main: String
    public var home: String?
    public var username: String

    public init(main: String, home: String?, username: String) {
        self.main = main
        self.home = home
        self.username = username
    }
}

/// What a family link names: its server, the address a hand-over is redeemed at (`base`), its home
/// address, the username it names, and whether it is an invite (which always makes a new account).
public struct LinkFacts: Equatable, Sendable {
    public var server: String
    public var base: String?
    public var home: String?
    public var username: String?
    public var invite: Bool

    public init(server: String, base: String? = nil, home: String? = nil, username: String? = nil, invite: Bool = false) {
        self.server = server
        self.base = base
        self.home = home
        self.username = username
        self.invite = invite
    }
}

extension FamilyLink {
    /// What this link names, for `LinkFormState.take`.
    public var facts: LinkFacts {
        switch self {
        case .invite(let link):
            LinkFacts(server: link.server, home: link.home, invite: true)
        case .prefill(let link):
            LinkFacts(server: link.server, home: link.home,
                      username: link.username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : link.username)
        case .handOver(let link):
            LinkFacts(server: link.server, base: link.base, home: link.home, username: link.username)
        }
    }
}

/// What a hand-over's sealed sign-in names.
public struct ReceivedFacts: Equatable, Sendable {
    public var server: String
    public var home: String?
    public var username: String

    public init(server: String, home: String?, username: String) {
        self.server = server
        self.home = home
        self.username = username
    }
}

extension HandOverSignIn {
    /// What this sealed sign-in names, for `LinkFormState.received`.
    public var facts: ReceivedFacts {
        ReceivedFacts(server: server, home: home.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 },
                      username: username)
    }
}

public enum LinkTake: String, Sendable {
    case fitting = "Fitting", keep = "Keep", forget = "Forget"
}

/// Why a link, or a received sign-in, is refused, and the words the form's link note shows.
public enum LinkRefusal: String, Sendable {
    case busy = "Busy", anotherAccount = "AnotherAccount", anotherServer = "AnotherServer", codeElsewhere = "CodeElsewhere"
    case addressChanged = "AddressChanged"

    public var words: String {
        switch self {
        case .busy: SignInWords.linkWhileBusy
        case .anotherAccount: SignInWords.linkForAnotherAccount
        case .anotherServer: SignInWords.linkForAnotherServer
        case .codeElsewhere: SignInWords.codeElsewhere
        case .addressChanged: SignInWords.linkAfterAddressChange
        }
    }
}

public enum LinkAction: Equatable, Sendable {
    /// The link is taken. For keep and forget the form writes `address` into its address box and
    /// `home` into its home field (forget after forgetting the last server's details); fitting
    /// writes neither. `addresses` is what `took` records as taken.
    case take(LinkTake, address: String?, home: String?, addresses: [String])
    case refuse(LinkRefusal)
}

public enum ReceivedAction: Equatable, Sendable {
    /// Sign in with the received sign-in: at the form's own addresses when `useForm` (a kept
    /// server's), else at the received ones.
    case keep(useForm: Bool)
    case refuse(LinkRefusal)
    /// Forget the form's details, write `address` and `home` into it, then `adopted`.
    case forgetAndAdopt(address: String, home: String, addresses: [String])
}

/// What a form knows about where links may take it. `anchor`: the saved server it was filled from,
/// until any edit of the address, scheme or home ends it (`anchorEnded`). `hadAddress`: the address
/// box held text at some point. `taken`: the addresses of the links taken since the last edit.
/// `typed`: the address and home typed since the last link. `note`: the words of the last refusal,
/// the form's link note.
public struct LinkFormState: Equatable, Sendable {
    public let kind: LinkForm
    public private(set) var anchor: LinkAnchor?
    public private(set) var anchorEnded = false
    public private(set) var hadAddress = false
    public private(set) var taken: [String] = []
    public private(set) var typed: [String] = []
    public private(set) var note: String?

    public init(_ kind: LinkForm) {
        self.kind = kind
    }

    /// The fill from a saved server, after its own writes.
    public mutating func filled(_ anchor: LinkAnchor) {
        self.anchor = anchor
        anchorEnded = false
    }

    /// The address, its scheme or the home changed, by the person or by the app's own writes:
    /// `address` is the app's built main address (nil when it does not build), `home` the home as
    /// the app reads it.
    public mutating func edited(address: String?, home: String?, addressNonBlank: Bool) {
        if anchor != nil {
            anchor = nil
            anchorEnded = true
        }
        taken = []
        if let main = address.flatMap(norm) {
            typed = distinct([main] + [home.flatMap(norm)].compactMap { $0 })
        } else {
            typed = []
        }
        if addressNonBlank { hadAddress = true }
        note = nil
    }

    /// What to do with a link arriving while the form's home field reads `home`. `busy` covers a
    /// certificate question waiting too.
    public func take(_ link: LinkFacts, busy: Bool, home: String) -> LinkAction {
        if busy { return .refuse(.busy) }
        let addresses = distinct([link.server, link.home, link.base].compactMap { $0.flatMap(norm) })
        func taking(_ kind: LinkTake) -> LinkAction {
            .take(kind, address: link.server, home: linkHome(forget: kind == .forget, home: home, linkHome: link.home), addresses: addresses)
        }
        if let anchor {
            let own = anchorAddresses(anchor)
            let fits = fitsIn(link.server, own) && (link.base.map { fitsIn($0, own) } ?? true)
            let named = link.username.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.flatMap { $0.isEmpty ? nil : $0 }
            if fits && !link.invite && (named == nil || named == anchor.username.trimmingCharacters(in: .whitespacesAndNewlines)) {
                return .take(.fitting, address: nil, home: nil, addresses: addresses)
            }
            if kind == .fresh { return taking(.forget) }
            return .refuse(fits ? .anotherAccount : (link.base != nil ? .codeElsewhere : .anotherServer))
        }
        if kind == .keptServer { return .refuse(anchorEnded ? .addressChanged : .anotherServer) }
        let known = taken + typed
        let all = [link.server, link.base, link.home].compactMap { $0 }.allSatisfy { fitsIn($0, known) }
        return taking(all || !hadAddress ? .keep : .forget)
    }

    /// The state a taken or refused link leaves, after the app's own writes.
    public mutating func took(_ action: LinkAction) {
        switch action {
        case .refuse(let why):
            note = why.words
        case .take(.fitting, _, _, _):
            note = nil
        case .take(.keep, _, _, let addresses):
            taken = distinct(taken + addresses)
            note = nil
        case .take(.forget, _, _, let addresses):
            if anchor != nil {
                anchor = nil
                anchorEnded = true
            }
            taken = addresses
            note = nil
        }
    }

    /// What to do with a hand-over's sealed sign-in, before its request is built (and again on every
    /// retry that reuses it).
    public func received(_ signIn: ReceivedFacts) -> ReceivedAction {
        let server = norm(signIn.server)
        let shownHome = signIn.home.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        let home = shownHome.flatMap(norm)
        if let anchor {
            let own = anchorAddresses(anchor)
            let fits = server.map { own.contains($0) } == true || home.map { own.contains($0) } == true
            let same = signIn.username.trimmingCharacters(in: .whitespacesAndNewlines) == anchor.username.trimmingCharacters(in: .whitespacesAndNewlines)
            if fits && same { return .keep(useForm: true) }
            if kind == .keptServer { return .refuse(fits ? .anotherAccount : .anotherServer) }
        } else {
            if let server, taken.contains(server), shownHome == nil || home.map({ taken.contains($0) }) == true {
                return .keep(useForm: false)
            }
            if kind == .keptServer { return .refuse(anchorEnded ? .addressChanged : .anotherServer) }
        }
        return .forgetAndAdopt(address: signIn.server, home: shownHome ?? "", addresses: distinct([server, home].compactMap { $0 }))
    }

    /// The received server adopted, after the app's forget and its writes, so a retry keeps it.
    public mutating func adopted(_ action: ReceivedAction) {
        if case .forgetAndAdopt(_, _, let addresses) = action { taken = addresses }
    }

    /// The form left the link (its sign-up, hand-over or prefill).
    public mutating func left() {
        note = nil
    }

    /// Words for the link note from outside the form: a refused received sign-in, or a link that
    /// came in while a sign-in was open.
    public mutating func noted(_ words: String) {
        note = words
    }
}

/// The home a taken link writes into the form's home field: the link's own on a forget (none when
/// it names none), else the link's or the one there.
public func linkHome(forget: Bool, home: String, linkHome: String?) -> String {
    forget ? linkHome ?? "" : linkHome ?? home
}

/// Whether a sign-in is open in a form: running, a certificate question waiting, or a received
/// sign-in or a finished sign-up held, each of which works only once.
public func signInOpen(busy: Bool, question: Bool, holdsReceived: Bool, holdsSignUp: Bool) -> Bool {
    busy || question || holdsReceived || holdsSignUp
}

/// An address in one form for comparing: `scheme://host[:port]path`, scheme and host lowercased,
/// the scheme's own port left out, one trailing slash dropped and an IPv6 host in brackets. Nil for
/// anything that is not an http or https address with a host, and, before parsing, for what URL
/// parsers read differently: user info, a dot segment in the path, an empty port, a host ending in
/// a dot or one that is not plain ASCII. Nil never fits anything.
public func norm(_ address: String) -> String? {
    let text = address.trimmingCharacters(in: .whitespacesAndNewlines)
    guard plainEnough(text), let parts = URLComponents(string: text), let scheme = parts.scheme?.lowercased(),
          scheme == "http" || scheme == "https", var host = parts.host, !host.isEmpty
    else { return nil }
    if host.hasPrefix("[") && host.hasSuffix("]") { host = String(host.dropFirst().dropLast()) }
    host = host.lowercased()
    let shownHost = host.contains(":") ? "[\(host)]" : host
    let port = parts.port.map { $0 == (scheme == "https" ? 443 : 80) ? "" : ":\($0)" } ?? ""
    var path = parts.percentEncodedPath
    if path.hasSuffix("/") { path.removeLast() }
    return "\(scheme)://\(shownHost)\(port)\(path)"
}

private let dotSegments: Set<String> = [".", "..", "%2e", "%2e%2e", ".%2e", "%2e."]

/// Whether `text` holds a scheme and none of what parsers read differently.
private func plainEnough(_ text: String) -> Bool {
    guard let start = text.range(of: "://") else { return false }
    let rest = text[start.upperBound...]
    let end = rest.firstIndex { $0 == "/" || $0 == "?" || $0 == "#" } ?? rest.endIndex
    let authority = rest[..<end]
    if authority.contains("@") { return false }
    let host: Substring
    let port: Substring?
    if authority.hasPrefix("[") {
        guard let close = authority.firstIndex(of: "]") else { return false }
        host = authority[authority.index(after: authority.startIndex)..<close]
        let after = authority[authority.index(after: close)...]
        if !after.isEmpty && !after.hasPrefix(":") { return false }
        port = after.isEmpty ? nil : after.dropFirst()
    } else if let colon = authority.firstIndex(of: ":") {
        host = authority[..<colon]
        port = authority[authority.index(after: colon)...]
    } else {
        host = authority
        port = nil
    }
    if port?.isEmpty == true { return false }
    if host.isEmpty || host.hasSuffix(".") || !host.unicodeScalars.allSatisfy(\.isASCII) { return false }
    var path = rest[end...]
    if let cut = path.firstIndex(where: { $0 == "?" || $0 == "#" }) { path = path[..<cut] }
    return !path.split(separator: "/", omittingEmptySubsequences: false).contains { dotSegments.contains($0.lowercased()) }
}

private func fitsIn(_ address: String, _ known: [String]) -> Bool {
    norm(address).map { known.contains($0) } == true
}

private func anchorAddresses(_ anchor: LinkAnchor) -> [String] {
    distinct([anchor.main, anchor.home].compactMap { $0.flatMap(norm) })
}

private func distinct(_ addresses: [String]) -> [String] {
    var seen: Set<String> = []
    return addresses.filter { seen.insert($0).inserted }
}

/// The fields every app's sign-in form forgets when a link takes it to another server (the same
/// list on every app, plus the iPhone's picked certificate file), held apart so the forget is tested
/// as behavior; the iPhone's model copies its fields in and back in one function.
public struct LinkFields: Equatable, Sendable {
    public var headers: HeaderList
    public var gatewayKind: GatewayKind
    public var gatewaySaved: GatewayKind
    public var gatewayFirst: String
    public var gatewaySecond: String
    public var legacy: Bool
    public var useApiKey: Bool
    public var apiKey: String
    public var password: String
    public var joinName: String
    public var joinPassword: String
    public var joinAgain: String
    public var username: String
    public var takesApiKeys: Bool?
    public var certificatePicked: Bool

    public init(headers: HeaderList, gatewayKind: GatewayKind, gatewaySaved: GatewayKind, gatewayFirst: String, gatewaySecond: String,
                legacy: Bool, useApiKey: Bool, apiKey: String, password: String, joinName: String, joinPassword: String,
                joinAgain: String, username: String, takesApiKeys: Bool?, certificatePicked: Bool) {
        self.headers = headers
        self.gatewayKind = gatewayKind
        self.gatewaySaved = gatewaySaved
        self.gatewayFirst = gatewayFirst
        self.gatewaySecond = gatewaySecond
        self.legacy = legacy
        self.useApiKey = useApiKey
        self.apiKey = apiKey
        self.password = password
        self.joinName = joinName
        self.joinPassword = joinPassword
        self.joinAgain = joinAgain
        self.username = username
        self.takesApiKeys = takesApiKeys
        self.certificatePicked = certificatePicked
    }

    /// The fields as a link to another server leaves them: nothing of the last server. The username
    /// stays only when the link names one, which the link then writes in.
    public func forgotten(linkUsername: String?) -> LinkFields {
        var out = self
        out.headers.replaced([])
        out.gatewayKind = .none
        out.gatewaySaved = .none
        out.gatewayFirst = ""
        out.gatewaySecond = ""
        out.legacy = false
        out.useApiKey = false
        out.apiKey = ""
        out.password = ""
        out.joinName = ""
        out.joinPassword = ""
        out.joinAgain = ""
        if linkUsername?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true { out.username = "" }
        out.takesApiKeys = nil
        out.certificatePicked = false
        return out
    }
}
