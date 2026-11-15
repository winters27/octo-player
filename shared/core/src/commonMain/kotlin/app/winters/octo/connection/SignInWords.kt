package app.winters.octo.connection

import app.winters.octo.subsonic.AuthMode

// The sign-in page's words and the words a sign-in that went wrong ends
// with, the same on the phone, the desktop and the iPhone (OctoKit's
// SignInWords). All three are checked against
// fixtures/gateway/signin-words.json, where a template carries `{name}` in
// place of a name and a blank name has a form of its own.
object SignInWords {
    // Advanced: its title, the line under it naming what is inside, and its
    // three groups.
    const val ADVANCED = "Advanced"
    // A no-break space keeps the line's last two names together, so a wrapped
    // line never ends on one word.
    const val ADVANCED_LINE = "Sign-in page, home address, method, headers, certificate"
    const val GROUP_GETTING_THERE = "Getting to your server"
    const val GROUP_SIGNING_IN = "Signing in"
    const val GROUP_SPECIAL_SETUPS = "Special setups"

    // The rows' titles.
    const val SERVER_ADDRESS = "Server address"
    const val SERVER_NAME = "Name"
    const val USERNAME = "Username"
    const val PASSWORD = "Password"
    const val API_KEY = "API key"
    const val YOUR_NAME = "Your name"
    const val PASSWORD_AGAIN = "Password again"
    const val HOME_ADDRESS = "Home address"
    const val METHOD = "Method"
    const val CUSTOM_HEADERS = "Custom headers"
    const val HEADER_NAME = "Header name"
    const val HEADER_VALUE = "Header value"
    const val ADD_HEADER = "Add header"

    // What an empty field shows: one that must be filled in, a secret whose
    // saved value stays, an example home address, and examples of a name.
    const val REQUIRED = "Required"
    const val UNCHANGED = "Unchanged"
    const val HOME_PLACEHOLDER = "192.168.1.20:4533"
    const val NAME_PLACEHOLDER = "Like Home or Work"

    // The quiet tag after the name of a field that may stay empty, and that
    // field's name as a screen reader says it, the tag included.
    const val OPTIONAL_TAG = "Optional"

    fun optionalName(title: String): String = "$title, optional"

    // What the home address and the custom headers are for, and the danger
    // note for an API key over plain http across the internet.
    const val HOME_NOTE = "On Wi-⁠Fi, Octo uses it when it answers, and the main address otherwise."
    const val HEADERS_DESCRIPTION = "Sent with every request to this server, such as an access token for a proxy in front of it."
    const val API_KEY_IN_THE_OPEN = "Not encrypted, and your API key travels as it is. Anyone on the way can read it."

    // The Method choice: each way of signing in, and the line under it in
    // the list. `takesApiKeys` is null until the server is known.
    fun methodLabel(mode: AuthMode): String = when (mode) {
        AuthMode.Token -> "Normal"
        AuthMode.LegacyPassword -> "Legacy password"
        AuthMode.ApiKey -> "API key"
    }

    fun methodHelper(mode: AuthMode, takesApiKeys: Boolean?): String = when (mode) {
        AuthMode.Token -> "Works with most servers. Your password goes as a one-time token."
        AuthMode.LegacyPassword -> "Only for older servers that reject the normal sign-in."
        AuthMode.ApiKey -> if (takesApiKeys == true) "Your server gives out API keys." else "For servers that give out API keys."
    }

    // The name a header row's Remove button reads: the row's name, trimmed,
    // or none for a row still blank.
    fun removeHeader(name: String): String {
        val trimmed = name.trim()
        return if (trimmed.isEmpty()) "Remove header" else "Remove header $trimmed"
    }

    // What a sign-in that went wrong says.
    const val UNREACHABLE = "Couldn't reach the server. Check the address, and that you're on the right network."
    const val RESTARTING = "The server isn't answering right now. It may be restarting, so try again in a moment."
    const val NOT_MUSIC = "That address answered, but not like a music server. Check the port."
    const val WRONG_PASSWORD = "Wrong username or password."
    const val WAY_REFUSED = "The server doesn't take this way of signing in."
    const val SOMETHING_WRONG = "Something went wrong."
    const val BAD_ADDRESS = "That doesn't look like a server address."
    const val BAD_HOME_ADDRESS = "The home network address doesn't look like a server address."
    const val MISSING_SECRET = "Enter the password or key to sign in this way."
    const val GONE = "That server isn't in your list any more."

    fun alreadyKept(name: String): String = "$name is already in your list with that address and username."

    // A server that cannot check a token, and one that needs the password
    // itself where it would cross the internet unencrypted.
    const val LEGACY_HINT = "This account can't sign in with a token. Choose Legacy password under Advanced, Signing in."
    const val LEGACY_UNSAFE =
        "This server needs the password sent as it is, which isn't safe over http. Use https, or choose Legacy password under Advanced, Signing in."

    // The client certificate row, and the popup that asks for its file's
    // password.
    const val CERT_TITLE = "Client certificate"
    const val CERT_NONE = "None chosen"
    const val CERT_CHOOSE = "Choose"
    const val CERT_CHANGE = "Change"
    const val CERT_REMOVE = "Remove certificate"
    const val CERT_PASSWORD_TITLE = "Certificate password"
    const val CERT_PASSWORD_HINT = "The password the certificate file was saved with."
    const val CERT_PASSWORD_FIELD = "Password"
    const val CERT_PASSWORD_USE = "Use it"

    fun certificateLine(name: String, date: String): String = "$name, expires $date"

    // A server that asked for a client certificate, or turned one down.
    const val CERT_ASKED = "This server asks for a client certificate. Choose one under Advanced, Special setups."
    const val CERT_REFUSED = "The server didn't accept that client certificate."

    // A certificate file refused when it is picked.
    const val CERT_KEY_KIND =
        "Octo can't play through this kind of key. Use an RSA (2048 bits or more), P-256, P-384 or Ed25519 certificate."
    const val CERT_WRONG_PASSWORD = "That password doesn't open this certificate file."
    const val CERT_NOT_A_FILE = "This isn't a certificate file Octo can open. Choose a .p12 or .pfx file."
    const val CERT_NO_KEY = "This file has no private key, so it can't sign in. Choose the file with your key."
    const val CERT_NOT_FOR_CLIENTS = "This certificate is only for servers, so it can't sign in."
    const val CERT_EXPIRED = "This certificate has expired. Choose a current one."
    const val CERT_NOT_YET_VALID = "This certificate isn't valid yet. Check this computer's date, or choose another."
    const val CERT_TOO_BIG = "This file is too big to be a certificate."

    // A family link refused by the form it came to, and the words for an
    // outside link or one of Octo's own sign-in asks while a sign-in is open.
    const val LINK_WHILE_BUSY = "That link came in while Octo was signing in. Open it again to use it."
    const val LINK_FOR_ANOTHER_ACCOUNT = "This link is for another account. Add it from Add a server."
    const val LINK_FOR_ANOTHER_SERVER = "This link is for another server. Add it from Add a server."
    const val CODE_ELSEWHERE = "This code can't sign in here. Use it from Add a server instead."
    const val LINK_AFTER_ADDRESS_CHANGE = "The address here was changed. Add this link from Add a server instead."
    const val LINK_WHILE_SIGN_IN_OPEN = "Finish or close the sign-in that's open, then try this link again."

    fun askWhileSignInOpen(name: String): String = "Sign in to $name when you're done here."

    // The question about a server certificate the device does not trust:
    // asked while signing in, and again in use when a host Octo trusted a
    // certificate for shows a different one. `device` is "phone" or
    // "computer".
    const val TRUST_TITLE = "Trust this certificate?"
    const val TRUST_CHANGED_TITLE = "The server's certificate changed"
    const val TRUST_HOST = "Host"
    const val TRUST_FINGERPRINT = "SHA-256 fingerprint"
    const val TRUST_CONFIRM = "Trust this certificate"
    const val TRUST_REFUSED = "Not connected. The server's certificate wasn't trusted."

    fun trustDetail(device: String): String =
        "This $device doesn't trust the certificate the server showed. If it's your own server's, check the fingerprint " +
            "matches before you trust it. Octo trusts it for this host only, and keeps trusting it when it's renewed with the same key."

    const val TRUST_CHANGED_DETAIL =
        "This host showed a different certificate from the one you trusted. Servers renew their certificates, so this is " +
            "often expected, but check the fingerprint matches your server's before you trust it. Until then Octo can't reach the server."
}
