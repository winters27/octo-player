import Foundation

/// Every word the iPhone app says about a sign-in page in front of a server. Android and the
/// desktop say the same; both are checked against fixtures/gateway/words.json.
public enum GatewayWords {
    public static let sectionLabel = "Sign-in page in front of the server"
    public static let caption = "For Authentik, Authelia, a proxy password or Cloudflare Access in front of the server."
    public static let usernameLabel = "Username"
    public static let passwordLabel = "Password"
    public static let clientIdLabel = "Client ID"
    public static let clientSecretLabel = "Client Secret"
    public static let savedLabel = "Saved. Leave empty to keep it."
    public static let joinHint = "If you don't have an account on this sign-in page, ask who invited you."
    public static let shareLine = "This server has a sign-in page in front of it, so people need an account there to open this link."
    public static let playFailure = "A sign-in page answered instead of the server."
    public static let needsHttps = "Use https for this address. The sign-in page password would cross the internet readable."
    public static let rowNeedsHttps = "Needs https for its sign-in page"
    public static let bothPassword = "Enter both the username and the password."
    public static let bothToken = "Enter both the Client ID and the Client Secret."
    public static let colon = "The username can't contain a colon."
    public static let control = "Remove the line break or special character from the sign-in page fields."
    public static let reservedRow = "That header is set by the sign-in page fields. Remove the row."

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
            return "Authentik is in front of this server, so the app needs its own sign-in to get through. Fill in the sign-in page fields under Advanced."
        case .authelia:
            return "Authelia is in front of this server, so the app needs its own sign-in to get through. Fill in the sign-in page fields under Advanced."
        case .cloudflareAccess:
            return "Cloudflare Access is in front of this server, so the app needs a service token to get through. Fill in the sign-in page fields under Advanced."
        case .passwordPrompt:
            return "This server asks for a username and password before the app can reach it. Fill in the sign-in page fields under Advanced."
        case .signInPage:
            return "A sign-in page answered instead of the server. On public Wi-Fi, sign in to the Wi-Fi first. Otherwise fill in the sign-in page fields under Advanced."
        }
    }

    /// How to fill in the fields for that gateway.
    public static func hint(_ gateway: Gateway) -> String {
        switch gateway {
        case .authentik:
            "Use your Authentik username and an app password. Make one in Authentik under your user settings, Tokens and App passwords."
        case .authelia:
            "Use your Authelia username and password. Authelia lets apps in only where its rule for this address is one factor."
        case .cloudflareAccess:
            "Use a service token from Cloudflare Zero Trust, and add a Service Auth rule for it to this app's Access policy."
        case .passwordPrompt:
            "Use the username and password the server's proxy asks for."
        case .signInPage:
            "If the sign-in page takes a username and password from apps, enter them here. A page that only works in a browser can't let the app in: ask whoever runs it to let /rest/ through."
        }
    }

    public static func kindLabel(_ kind: GatewayKind) -> String {
        switch kind {
        case .none: "None"
        case .password: "Username and password"
        case .cloudflareToken: "Cloudflare service token"
        }
    }

    /// The words for an error a sign-in page in front of the server caused, or nil when none did.
    public static func failureFor(_ error: Error) -> String? {
        let known = SubsonicError(error)
        return known.gateway.map { failure($0, loginSent: known.loginSent) }
    }
}
