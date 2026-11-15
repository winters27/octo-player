import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The sign-in page's words and the words a sign-in that went wrong ends with, the same as the
/// phone and desktop apps' SignInWords. All three are checked against
/// fixtures/gateway/signin-words.json, where a template carries `{name}` in place of a name and a
/// blank name has a form of its own.
public enum SignInWords {
    /// Advanced: its title, the line under it naming what is inside, and its three groups.
    public static let advanced = "Advanced"
    /// A no-break space keeps the line's last two names together, so a wrapped line never ends on
    /// one word.
    public static let advancedLine = "Sign-in page, home address, method, headers,\u{00A0}certificate"
    public static let groupGettingThere = "Getting to your server"
    public static let groupSigningIn = "Signing in"
    public static let groupSpecialSetups = "Special setups"

    /// The rows' titles.
    public static let serverAddress = "Server address"
    public static let serverName = "Name"
    public static let username = "Username"
    public static let password = "Password"
    public static let apiKey = "API key"
    public static let yourName = "Your name"
    public static let passwordAgain = "Password again"
    public static let homeAddress = "Home address"
    public static let method = "Method"
    public static let customHeaders = "Custom headers"
    public static let headerName = "Header name"
    public static let headerValue = "Header value"
    public static let addHeader = "Add header"

    /// What an empty field shows: one that must be filled in, a secret whose saved value stays, an
    /// example home address, and examples of a name.
    public static let required = "Required"
    public static let unchanged = "Unchanged"
    public static let homePlaceholder = "192.168.1.20:4533"
    public static let namePlaceholder = "Like Home or Work"

    /// The quiet tag after the name of a field that may stay empty, and that field's name as
    /// VoiceOver says it, the tag included.
    public static let optionalTag = "Optional"

    public static func optionalName(_ title: String) -> String { "\(title), optional" }

    /// What the home address and the custom headers are for, and the danger note for an API key
    /// over plain http across the internet.
    public static let homeNote = "On Wi-\u{2060}Fi, Octo uses it when it answers, and the main address\u{00A0}otherwise."
    public static let headersDescription = "Sent with every request to this server, such as an access token for a proxy in front of it."
    public static let apiKeyInTheOpen = "Not encrypted, and your API key travels as it is. Anyone on the way can read it."

    /// The Method choice: each way of signing in, and the line under it in the list.
    /// `takesApiKeys` is nil until the server is known.
    public static func methodLabel(_ mode: AuthMode) -> String {
        switch mode {
        case .token: "Normal"
        case .legacyPassword: "Legacy password"
        case .apiKey: "API key"
        }
    }

    public static func methodHelper(_ mode: AuthMode, takesApiKeys: Bool?) -> String {
        switch mode {
        case .token: "Works with most servers. Your password goes as a one-time token."
        case .legacyPassword: "Only for older servers that reject the normal sign-in."
        case .apiKey: takesApiKeys == true ? "Your server gives out API keys." : "For servers that give out API keys."
        }
    }

    /// The name a header row's Remove button reads: the row's name, trimmed, or none for a row
    /// still blank.
    public static func removeHeader(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Remove header" : "Remove header \(trimmed)"
    }

    /// What a sign-in that went wrong says.
    public static let unreachable = "Couldn't reach the server. Check the address, and that you're on the right network."
    public static let restarting = "The server isn't answering right now. It may be restarting, so try again in a moment."
    public static let notMusic = "That address answered, but not like a music server. Check the port."
    public static let wrongPassword = "Wrong username or password."
    public static let wayRefused = "The server doesn't take this way of signing in."
    public static let somethingWrong = "Something went wrong."
    public static let badAddress = "That doesn't look like a server address."
    public static let badHomeAddress = "The home network address doesn't look like a server address."
    public static let missingSecret = "Enter the password or key to sign in this way."
    public static let gone = "That server isn't in your list any more."
    public static let badHeaders = "Each header needs a name with no spaces and a plain text value."
    public static let notTrusted = "Not connected. The server's certificate wasn't trusted."

    public static func alreadyKept(_ name: String) -> String {
        "\(name) is already in your list with that address and username."
    }

    /// A server that cannot check a token, and one that needs the password itself where it would
    /// cross the internet unencrypted.
    public static let legacyHint = "This account can't sign in with a token. Choose Legacy password under Advanced, Signing in."
    public static let legacyUnsafe =
        "This server needs the password sent as it is, which isn't safe over http. Use https, or choose Legacy password under Advanced, Signing in."

    /// The client certificate row, and the popup that asks for its file's password.
    public static let certTitle = "Client certificate"
    public static let certNone = "None chosen"
    public static let certChoose = "Choose"
    public static let certChange = "Change"
    public static let certRemove = "Remove certificate"
    public static let certPasswordTitle = "Certificate password"
    public static let certPasswordHint = "The password the certificate file was saved with."
    public static let certPasswordField = "Password"
    public static let certPasswordUse = "Use it"
    /// The iPhone's password question says this after its hint: the question opens only once the
    /// file did not open with an empty password, so a file saved with none has to be exported again
    /// with one. The iPhone's alone, so not among the shared words.
    public static let certNoPasswordOnPhone =
        "If this file has no password, export it again with one: iPhone can't open certificates without a password."

    public static func certificateLine(_ name: String, date: String) -> String { "\(name), expires \(date)" }

    /// A server that asked for a client certificate, or turned one down.
    public static let certAsked = "This server asks for a client certificate. Choose one under Advanced, Special setups."
    public static let certRefused = "The server didn't accept that client certificate."

    /// A certificate file refused when it is picked.
    public static let certKeyKind =
        "Octo can't play through this kind of key. Use an RSA (2048 bits or more), P-256, P-384 or Ed25519 certificate."
    public static let certWrongPassword = "That password doesn't open this certificate file."
    public static let certNotAFile = "This isn't a certificate file Octo can open. Choose a .p12 or .pfx file."
    public static let certNoKey = "This file has no private key, so it can't sign in. Choose the file with your key."
    public static let certNotForClients = "This certificate is only for servers, so it can't sign in."
    public static let certExpired = "This certificate has expired. Choose a current one."
    public static let certNotYetValid = "This certificate isn't valid yet. Check this computer's date, or choose another."
    public static let certTooBig = "This file is too big to be a certificate."

    /// A family link refused by the form it came to, and the words for an outside link or one of
    /// Octo's own sign-in asks while a sign-in is open.
    public static let linkWhileBusy = "That link came in while Octo was signing in. Open it again to use it."
    public static let linkForAnotherAccount = "This link is for another account. Add it from Add a server."
    public static let linkForAnotherServer = "This link is for another server. Add it from Add a server."
    public static let codeElsewhere = "This code can't sign in here. Use it from Add a server instead."
    public static let linkAfterAddressChange = "The address here was changed. Add this link from Add a server instead."
    public static let linkWhileSignInOpen = "Finish or close the sign-in that's open, then try this link again."

    public static func askWhileSignInOpen(_ name: String) -> String { "Sign in to \(name) when you're done here." }

    /// The question about a server certificate the device does not trust: asked while signing in,
    /// and again in use when a host Octo trusted a certificate for shows a different one.
    public static let trustTitle = "Trust this certificate?"
    public static let trustChangedTitle = "The server's certificate changed"
    public static let trustHost = "Host"
    public static let trustFingerprint = "SHA-256 fingerprint"
    public static let trustConfirm = "Trust this certificate"

    /// `device` is "phone" or "computer".
    public static func trustDetail(_ device: String) -> String {
        "This \(device) doesn't trust the certificate the server showed. If it's your own server's, check the fingerprint "
            + "matches before you trust it. Octo trusts it for this host only, and keeps trusting it when it's renewed with the same key."
    }

    public static let trustChangedDetail =
        "This host showed a different certificate from the one you trusted. Servers renew their certificates, so this is "
            + "often expected, but check the fingerprint matches your server's before you trust it. Until then Octo can't reach the server."

    /// The words for any error a sign-in can end with.
    public static func describe(_ error: Error) -> String {
        if let octo = error as? OctoError {
            switch octo {
            case .wrongCredentials: return wrongPassword
            case .authUnsupported(41, _): return legacyHint
            case .authUnsupported: return wayRefused
            case .notFound(let message), .server(_, let message):
                return message.isEmpty ? somethingWrong : "The server said: \(message)"
            case .notSubsonic(let gateway?, let loginSent), .http(_, let gateway?, let loginSent):
                return GatewayWords.failure(gateway, loginSent: loginSent)
            case .notSubsonic: return notMusic
            case .http(let status, _, _):
                return [502, 503, 504, 520, 521, 522, 523, 524, 530].contains(status) ? restarting : notMusic
            }
        }
        if error is URLError { return unreachable }
        return somethingWrong
    }
}
