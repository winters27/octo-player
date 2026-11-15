import Foundation

/// Every word the iPhone app says about a sign-in page in front of a server. Android and the
/// desktop say the same; both are checked against fixtures/gateway/words.json.
public enum GatewayWords {
    public static let sectionLabel = "Sign-in page in front"
    public static let caption = "For Authentik, Authelia, a proxy password or Cloudflare Access"
    public static let clientIdLabel = "Client ID"
    public static let clientSecretLabel = "Client Secret"
    public static let joinHint = "If you don't have an account on this sign-in page, ask who invited you."
    public static let shareLine = "This server has a sign-in page in front of it, so people need an account there to open this link."
    public static let playFailure = "A sign-in page answered instead of the server."
    public static let needsHttps = "Use https for this address. The sign-in page password would cross the internet readable."
    public static let rowNeedsHttps = "Needs https for its sign-in page"
    public static let bothPassword = "Enter both the sign-in page username and the sign-in page password."
    public static let bothToken = "Enter both the Client ID and the Client Secret."
    public static let colon = "The sign-in page username can't contain a colon."
    public static let control = "Remove the line break or special character from the sign-in page fields."
    public static let reservedRow = "That header is set by the sign-in page fields. Remove the row."
    /// The line under Sign in while the sign-in page's group calls out a failure: a button that
    /// brings the group back into view. A no-break space keeps its last two words together, so a
    /// wrapped line never ends on one word.
    public static let pointer = "Your server's sign-in page needs its own login, under\u{00A0}Advanced."

    public static func name(_ gateway: Gateway) -> String {
        switch gateway {
        case .authentik: "Authentik"
        case .authelia: "Authelia"
        case .cloudflareAccess: "Cloudflare Access"
        case .passwordPrompt: "A password prompt"
        case .signInPage: "A sign-in page"
        }
    }

    /// The same, short enough for a server's row in the list.
    public static func row(_ gateway: Gateway, loginSent: Bool) -> String {
        loginSent ? "\(name(gateway)) didn't accept the sign-in" : "\(name(gateway)) is in front of this server"
    }

    /// What answered in the server's place, and what to do. `loginSent`: the app's own sign-in
    /// for it went along, and it said no.
    public static func failure(_ gateway: Gateway, loginSent: Bool) -> String {
        if loginSent {
            switch gateway {
            case .authentik: return "Authentik didn't accept that username and app password."
            case .authelia: return "Authelia didn't accept that username and password."
            case .cloudflareAccess: return "Cloudflare Access didn't accept the service token. Check that a Service Auth rule allows it."
            case .passwordPrompt: return "The server's password prompt didn't accept that username and password."
            case .signInPage: return "The sign-in page in front of the server didn't accept those details."
            }
        }
        switch gateway {
        case .authentik:
            return "Authentik is in front of this server, so Octo needs its own sign-in to get through. Fill in the sign-in page fields under Advanced."
        case .authelia:
            return "Authelia is in front of this server, so Octo needs its own sign-in to get through. Fill in the sign-in page fields under Advanced."
        case .cloudflareAccess:
            return "Cloudflare Access is in front of this server, so Octo needs a service token to get through. Fill in the sign-in page fields under Advanced."
        case .passwordPrompt:
            return "This server asks for a username and password before Octo can reach it. Fill in the sign-in page fields under Advanced."
        case .signInPage:
            return "A sign-in page answered instead of the server. On public Wi-\u{2060}Fi, sign in to the Wi-\u{2060}Fi first. Otherwise fill in the sign-in page fields under Advanced."
        }
    }

    /// A failure's words as the sign-in page group's own error note shows them, under the fields
    /// it names: "under Advanced" there reads "above", kept with "fields" so it never ends a
    /// wrapped note alone.
    public static func inGroup(_ text: String) -> String {
        text.replacingOccurrences(of: "Otherwise fill in the sign-in page fields under Advanced.", with: "Otherwise fill in the fields\u{00A0}above.")
            .replacingOccurrences(of: "Fill in the sign-in page fields under Advanced.", with: "Fill in the fields\u{00A0}above.")
    }

    /// How to fill in the fields for that gateway.
    public static func hint(_ gateway: Gateway) -> String {
        switch gateway {
        case .authentik:
            "Use your Authentik username and an app password. Make one in Authentik under your user settings, Tokens and App\u{00A0}passwords."
        case .authelia:
            "Use your Authelia username and password. Authelia lets apps in only where its rule for this address is one factor."
        case .cloudflareAccess:
            "Use a service token from Cloudflare Zero Trust, and add a Service Auth rule for it to this app's Access\u{00A0}policy."
        case .passwordPrompt:
            "Use the username and password the server's proxy asks for."
        case .signInPage:
            "If the sign-in page takes a username and password from apps, enter them here. A page that only works in a browser can't let Octo in: ask whoever runs it to let /\u{2060}rest/ through."
        }
    }

    /// The note under the sign-in page group when a kind is picked and no sign-in page was met: how
    /// to get a service token. Nil when there is nothing to add.
    public static func tip(_ kind: GatewayKind) -> String? {
        kind == .cloudflareToken ? hint(.cloudflareAccess) : nil
    }

    public static func kindLabel(_ kind: GatewayKind) -> String {
        switch kind {
        case .none: "None"
        case .password: "Username and password"
        case .cloudflareToken: "Service token"
        }
    }

    /// The line under a kind in the choice: what each is for. None has none.
    public static func kindHelper(_ kind: GatewayKind) -> String? {
        switch kind {
        case .none: nil
        case .password: "Authentik, Authelia or a proxy password"
        case .cloudflareToken: "Cloudflare Access"
        }
    }

    /// The names of a kind's two field rows: Cloudflare's own for a service token, the met sign-in
    /// page's own when it is one whose words are known, and otherwise names that never read like
    /// the server's own username and password.
    public static func fieldLabels(_ kind: GatewayKind, detected: Gateway?) -> (first: String, second: String) {
        if kind == .cloudflareToken { return (clientIdLabel, clientSecretLabel) }
        switch detected {
        case .authentik?: return ("Authentik username", "App password")
        case .authelia?: return ("Authelia username", "Authelia password")
        default: return ("Sign-in page username", "Sign-in page password")
        }
    }

    /// The words for an error a sign-in page in front of the server caused, or nil when none did.
    public static func failureFor(_ error: Error) -> String? {
        let known = SubsonicError(error)
        return known.gateway.map { failure($0, loginSent: known.loginSent) }
    }
}
