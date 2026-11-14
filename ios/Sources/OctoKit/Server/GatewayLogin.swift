import Foundation

/// How the app gets through a sign-in page in front of the server: not at all, a username and
/// password sent as HTTP Basic (an Authentik app password, Authelia, a proxy's password), or a
/// Cloudflare Access service token.
public enum GatewayKind: String, Sendable, Equatable, CaseIterable {
    case none = "None", password = "Password", cloudflareToken = "CloudflareToken"
}

/// RFC 7617: "Basic " and the base64 of the UTF-8 bytes of the username and password, each in
/// NFC, joined by a colon. The caller has checked them already: no colon in the username, no
/// control characters.
public func basicCredentials(username: String, password: String) -> String {
    let pair = username.precomposedStringWithCanonicalMapping + ":" + password.precomposedStringWithCanonicalMapping
    return "Basic " + Data(pair.utf8).base64EncodedString()
}

/// The kind saved headers make up, read from their values: both Cloudflare names make a service
/// token (an Authorization beside them stays a row of its own); else an Authorization that is
/// HTTP Basic makes a username and password; anything else (a Bearer token, half a Cloudflare
/// pair) is none.
public func savedGatewayKind(_ headers: [String: String]) -> GatewayKind {
    if headers.keys.contains(where: { named($0, GatewayNames.cfId) }) && headers.keys.contains(where: { named($0, GatewayNames.cfSecret) }) {
        return .cloudflareToken
    }
    if headers.contains(where: isBasic) { return .password }
    return .none
}

/// The names, as saved, of the headers that make up that kind: the form shows them as the
/// sign-in page fields, never as rows of their own.
public func savedGatewayNames(_ headers: [String: String], kind: GatewayKind) -> Set<String> {
    switch kind {
    case .none: []
    case .password: Set(headers.filter(isBasic).keys)
    case .cloudflareToken: Set(headers.keys.filter { named($0, GatewayNames.cfId) || named($0, GatewayNames.cfSecret) })
    }
}

private func named(_ name: String, _ wanted: String) -> Bool {
    name.trimmingCharacters(in: .whitespaces).caseInsensitiveCompare(wanted) == .orderedSame
}

private func isBasic(_ header: (key: String, value: String)) -> Bool {
    named(header.key, GatewayNames.authorization) && header.value.lowercased().hasPrefix("basic ")
}

/// The sign-in page fields as the form holds them: the kind picked, the two fields as typed (a
/// username and password, or a Client ID and Client Secret), and the kind the saved headers make
/// up. Left empty under the saved kind, the fields keep the saved values. Never printed.
public struct GatewayDraft: Sendable, Equatable, CustomStringConvertible {
    public var kind: GatewayKind
    public var first: String
    public var second: String
    public var saved: GatewayKind

    public init(kind: GatewayKind = .none, first: String = "", second: String = "", saved: GatewayKind = .none) {
        self.kind = kind
        self.first = first
        self.second = second
        self.saved = saved
    }

    public var description: String {
        "GatewayDraft(kind=\(kind), first=\(first.isEmpty ? "empty" : "typed"), second=\(second.isEmpty ? "empty" : "typed"), saved=\(saved))"
    }
}

/// The headers to keep and send, or what keeps the form from going.
public enum ResolvedHeaders: Sendable, Equatable {
    case ready([String: String])
    case problem(String)
}

/// Every header the server gets: the rows as typed (a saved row left empty keeps its value) and
/// the sign-in page fields made into theirs. `saved` is the saved headers with their values;
/// `mainHttps` and `mainPrivate` describe the main address. The rows that make up the saved kind
/// are shown as the fields, so they are not among `rows`.
public func resolveConnectionHeaders(rows: [HeaderRules.Row], gateway: GatewayDraft, saved: [String: String],
                                     mainHttps: Bool, mainPrivate: Bool) -> ResolvedHeaders {
    let fields: [String: String]
    switch fieldHeaders(gateway, saved: saved) {
    case .problem(let text): return .problem(text)
    case .ready(let made): fields = made
    }
    let reserved = reservedNames(gateway.kind)
    if rows.contains(where: { row in reserved.contains { named(row.name, $0) } }) {
        return .problem(GatewayWords.reservedRow)
    }
    let headers = rowHeaders(rows, saved: saved).merging(fields) { $1 }
    if headers.keys.contains(where: GatewayNames.isGatewayName), !gatewayAllowed(mainHttps: mainHttps, mainPrivate: mainPrivate) {
        return .problem(GatewayWords.needsHttps)
    }
    return .ready(headers)
}

/// A sign-in page's password may go over https, or over plain http inside the home network;
/// never over plain http across the internet.
public func gatewayAllowed(mainHttps: Bool, mainPrivate: Bool) -> Bool { mainHttps || mainPrivate }

/// Whether a kept server's headers hold a sign-in page's header the scope never sends: a plain
/// http main address outside the home network. Its row then asks for https.
public func needsHttps(_ headers: [String: String], scope: ServerScope) -> Bool {
    headers.keys.contains(where: GatewayNames.isGatewayName) && scope.gatewayOrigins.isEmpty
}

/// The names the fields set for a kind, which no row may use beside them.
private func reservedNames(_ kind: GatewayKind) -> [String] {
    switch kind {
    case .none: []
    case .password: [GatewayNames.authorization]
    case .cloudflareToken: [GatewayNames.cfId, GatewayNames.cfSecret]
    }
}

/// The rows' headers: a saved row left empty keeps its saved value, each needs a name and a
/// plain value, and a name typed twice keeps the last one.
func rowHeaders(_ rows: [HeaderRules.Row], saved: [String: String]) -> [String: String] {
    var out: [String: String] = [:]
    var order: [String: String] = [:]
    for row in rows {
        let name = row.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let savedValue = saved.first { named($0.key, name) }?.value
        let typed = row.saved && row.value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? savedValue : row.value
        let value = (typed ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard HeaderRules.isValidName(name), !value.isEmpty, HeaderRules.isValidValue(value) else { continue }
        if let earlier = order[name.lowercased()] { out[earlier] = nil }
        order[name.lowercased()] = name
        out[name] = value
    }
    return out
}

/// The headers the fields make. The username is trimmed; a password, Client ID or Client
/// Secret is used as typed.
private func fieldHeaders(_ draft: GatewayDraft, saved: [String: String]) -> ResolvedHeaders {
    if draft.kind == .none { return .ready([:]) }
    let password = draft.kind == .password
    let first = password ? draft.first.trimmingCharacters(in: .whitespacesAndNewlines) : draft.first
    let second = draft.second
    if first.isEmpty && second.isEmpty && draft.kind == draft.saved {
        let names = savedGatewayNames(saved, kind: draft.kind)
        let kept = saved.filter { names.contains($0.key) }
        if !kept.isEmpty { return .ready(kept) }
    }
    if first.isEmpty || second.isEmpty {
        return .problem(password ? GatewayWords.bothPassword : GatewayWords.bothToken)
    }
    if password {
        if first.contains(":") { return .problem(GatewayWords.colon) }
        if (first + second).unicodeScalars.contains(where: { $0.properties.generalCategory == .control }) {
            return .problem(GatewayWords.control)
        }
        return .ready([GatewayNames.authorization: basicCredentials(username: first, password: second)])
    }
    if !(first + second).unicodeScalars.allSatisfy({ (0x20...0x7E).contains($0.value) }) {
        return .problem(GatewayWords.control)
    }
    return .ready([GatewayNames.cfId: first, GatewayNames.cfSecret: second])
}

/// The form's sign-in page state across attempts: whether Advanced is open, the kind picked, and
/// the gateway seen at the address typed (`detectedFor`). `callout`: the last failure met a
/// sign-in page at this address, so its group shows that failure as its error note, outlined, with
/// a pointer under Sign in. `calloutPending`: the group still has to be brought into view.
/// `keyPending`: the API key row, just asked for, still has to be brought into view.
public struct GatewayFormState: Sendable, Equatable {
    public var advancedOpen: Bool
    public var kind: GatewayKind
    public var detected: Gateway?
    public var detectedFor: String?
    public var callout: Bool
    public var calloutPending: Bool
    public var keyPending: Bool

    public init(advancedOpen: Bool = false, kind: GatewayKind = .none, detected: Gateway? = nil, detectedFor: String? = nil,
                callout: Bool = false, calloutPending: Bool = false, keyPending: Bool = false) {
        self.advancedOpen = advancedOpen
        self.kind = kind
        self.detected = detected
        self.detectedFor = detectedFor
        self.callout = callout
        self.calloutPending = calloutPending
        self.keyPending = keyPending
    }
}

/// A failed attempt at `attemptAddress`, while the address field reads `currentAddress`. A
/// gateway opens Advanced and, when no kind is picked yet, picks the one that gets through it.
/// The most specific gateway seen at this address is kept, and the group calls the failure out,
/// again for a repeat. A result for an address no longer typed is dropped.
public func reactToFailure(_ state: GatewayFormState, attemptAddress: String, currentAddress: String,
                           gateway: Gateway?) -> GatewayFormState {
    let address = currentAddress.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let gateway, attemptAddress.trimmingCharacters(in: .whitespacesAndNewlines) == address else { return state }
    let kept = state.detectedFor == address ? moreSpecific(state.detected, gateway) ?? gateway : gateway
    var next = state
    next.advancedOpen = true
    if state.kind == .none { next.kind = kept == .cloudflareAccess ? .cloudflareToken : .password }
    next.detected = kept
    next.detectedFor = address
    next.callout = true
    next.calloutPending = true
    return next
}

/// Another address forgets the gateway seen at the last one, and its callout.
public func addressChanged(_ state: GatewayFormState, address: String) -> GatewayFormState {
    guard state.detectedFor != address.trimmingCharacters(in: .whitespacesAndNewlines) else { return state }
    var next = state
    next.detected = nil
    next.detectedFor = nil
    next.callout = false
    next.calloutPending = false
    return next
}

/// Authentik, Authelia and Cloudflare Access outrank a password prompt and an unknown sign-in
/// page; otherwise the later one counts.
public func moreSpecific(_ a: Gateway?, _ b: Gateway?) -> Gateway? {
    guard let b else { return a }
    guard let a else { return b }
    if namedGateways.contains(b) { return b }
    if namedGateways.contains(a) { return a }
    return b
}

private let namedGateways: Set<Gateway> = [.authentik, .authelia, .cloudflareAccess]
