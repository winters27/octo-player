import Foundation

// The sign-in form's rules that every app's form follows, checked against
// fixtures/gateway/signin-vectors.json in OctoKit and in Kotlin.

/// A sign-in page field edited: the failure is the person's to fix now, so the group stops calling
/// it out.
public func gatewayEdited(_ state: GatewayFormState) -> GatewayFormState {
    var next = state
    next.callout = false
    return next
}

/// A sign-in page kind picked.
public func kindPicked(_ state: GatewayFormState, kind: GatewayKind) -> GatewayFormState {
    var next = state
    next.kind = kind
    next.callout = false
    return next
}

/// The form's error written or cleared by anything else: the callout and its scroll end, while a
/// key row waiting to be shown still waits.
public func calm(_ state: GatewayFormState) -> GatewayFormState {
    var next = state
    next.callout = false
    next.calloutPending = false
    return next
}

/// An attempt starts: nothing from the last one is called out or waits to be scrolled to.
public func sending(_ state: GatewayFormState) -> GatewayFormState {
    var next = state
    next.callout = false
    next.calloutPending = false
    next.keyPending = false
    return next
}

/// The group was brought into view.
public func calloutShown(_ state: GatewayFormState) -> GatewayFormState {
    var next = state
    next.calloutPending = false
    return next
}

/// The pointer under Sign in pressed: the group is brought into view again, while it calls a
/// failure out.
public func pointTo(_ state: GatewayFormState) -> GatewayFormState {
    guard state.callout else { return state }
    var next = state
    next.calloutPending = true
    return next
}

/// The Method changed from `oldMode` to `newMode`. Picking API key with no key kept for it asks for
/// the key row to be brought into view; every other pick drops that ask.
public func methodPicked(_ state: GatewayFormState, oldMode: AuthMode, newMode: AuthMode, keepsKey: Bool) -> GatewayFormState {
    var next = state
    next.keyPending = newMode == .apiKey && oldMode != .apiKey && !keepsKey
    return next
}

/// The key row was brought into view.
public func keyShown(_ state: GatewayFormState) -> GatewayFormState {
    var next = state
    next.keyPending = false
    return next
}

/// The Method from the two flags a form holds; an API key wins.
public func authModeOf(legacy: Bool, apiKey: Bool) -> AuthMode {
    apiKey ? .apiKey : (legacy ? .legacyPassword : .token)
}

/// The two flags for a Method.
public func flagsOf(_ mode: AuthMode) -> (legacy: Bool, apiKey: Bool) {
    switch mode {
    case .token: (false, false)
    case .legacyPassword: (true, false)
    case .apiKey: (false, true)
    }
}

/// The Methods to offer. Before signing in nobody knows whether the server takes API keys, so one
/// may be tried; once it is known not to, API key is offered only while it is the one picked.
public func methodsShown(takesApiKeys: Bool?, apiKeyOn: Bool) -> [AuthMode] {
    takesApiKeys != false || apiKeyOn ? [.token, .legacyPassword, .apiKey] : [.token, .legacyPassword]
}

/// Whether the password field may stay empty and keep the saved password: a form that keeps the
/// saved secret, a password saved (for a token or legacy sign-in), and a Method that sends a
/// password.
public func keepsPassword(keepsSecret: Bool, savedMode: AuthMode?, mode: AuthMode) -> Bool {
    keepsSecret && savedMode != nil && savedMode != .apiKey && mode != .apiKey
}

/// The same for the API key field: a key saved, and API key picked.
public func keepsKey(keepsSecret: Bool, savedMode: AuthMode?, mode: AuthMode) -> Bool {
    keepsSecret && savedMode == .apiKey && mode == .apiKey
}

/// Advanced's groups, in the order every app draws them.
public enum AdvancedGroup: String, Sendable, CaseIterable {
    case gettingThere = "GettingThere", signingIn = "SigningIn", specialSetups = "SpecialSetups"

    public var title: String {
        switch self {
        case .gettingThere: SignInWords.groupGettingThere
        case .signingIn: SignInWords.groupSigningIn
        case .specialSetups: SignInWords.groupSpecialSetups
        }
    }
}

/// Advanced's rows: the sign-in page kind and its two fields, the home address, the Method, the
/// custom headers and the client certificate.
public enum AdvancedRow: String, Sendable, CaseIterable {
    case pageKind = "PageKind", pageFirst = "PageFirst", pageSecond = "PageSecond", home = "Home", method = "Method"
    case headers = "Headers", certificate = "Certificate"
}

public struct LayoutGroup: Equatable, Sendable {
    public let group: AdvancedGroup
    public let rows: [AdvancedRow]

    public init(group: AdvancedGroup, rows: [AdvancedRow]) {
        self.group = group
        self.rows = rows
    }
}

/// What Advanced draws: its groups in order, and the Methods the Method row offers (none while
/// joining, where the link decides how to sign in).
public struct AdvancedLayout: Equatable, Sendable {
    public let groups: [LayoutGroup]
    public let methods: [AuthMode]

    public init(groups: [LayoutGroup], methods: [AuthMode]) {
        self.groups = groups
        self.methods = methods
    }
}

/// Advanced for one form: getting to the server (the sign-in page, its two fields once a kind is
/// picked, the home address), signing in (the Method, left out while joining), and special setups
/// (custom headers, and the client certificate where the host has that row).
public func advancedLayout(joining: Bool, takesApiKeys: Bool?, apiKeyOn: Bool, kind: GatewayKind,
                           certificateRow: Bool) -> AdvancedLayout {
    let fields: [AdvancedRow] = kind == .none ? [] : [.pageFirst, .pageSecond]
    var groups = [LayoutGroup(group: .gettingThere, rows: [.pageKind] + fields + [.home])]
    if !joining { groups.append(LayoutGroup(group: .signingIn, rows: [.method])) }
    groups.append(LayoutGroup(group: .specialSetups, rows: certificateRow ? [.headers, .certificate] : [.headers]))
    return AdvancedLayout(groups: groups, methods: joining ? [] : methodsShown(takesApiKeys: takesApiKeys, apiKeyOn: apiKeyOn))
}

/// Whether Advanced, just opened from its head, scrolls the page to bring its first group's first
/// row into view: only while it is still open with no callout or API key scroll on its way (those
/// move the page themselves), and only when that row ends under the view's visible bottom
/// (`rowBottom` and `visibleBottom` on screen, growing downward). A page that already shows the row
/// stays where it is, so opening never scrolls the page back.
public func advancedRevealScrolls(open: Bool, calloutPending: Bool, keyPending: Bool, rowBottom: Double,
                                  visibleBottom: Double) -> Bool {
    open && !calloutPending && !keyPending && rowBottom > visibleBottom
}
