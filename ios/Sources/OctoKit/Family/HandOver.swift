import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

// Signing in on another device of one's own: the signed-in device shows a QR code holding a
// one-time token and a key made on it; the new device redeems the token, the first device
// allows it, seals its sign-in with the key and hands the sealed box to the server, and the new
// device opens it. The server relays a box it cannot open: the key is only in the QR code,
// after the #, which never reaches it.

/// The sign-in that travels, sealed: who, the secret this app signs in with, how it signs in,
/// the server's addresses and the account's name. Never printed.
public struct HandOverSignIn: Codable, Sendable, Equatable, CustomStringConvertible {
    public var username: String
    public var secret: String
    public var mode: String
    public var server: String
    public var home: String?
    public var displayName: String

    public init(username: String, secret: String, mode: String = "Token", server: String, home: String? = nil, displayName: String = "") {
        self.username = username
        self.secret = secret
        self.mode = mode
        self.server = server
        self.home = home
        self.displayName = displayName
    }

    enum CodingKeys: String, CodingKey { case username, secret, mode, server, home, displayName }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        username = try c.decode(String.self, forKey: .username)
        secret = try c.decode(String.self, forKey: .secret)
        mode = try c.decodeIfPresent(String.self, forKey: .mode) ?? "Token"
        server = try c.decode(String.self, forKey: .server)
        home = try c.decodeIfPresent(String.self, forKey: .home)
        displayName = try c.decodeIfPresent(String.self, forKey: .displayName) ?? ""
    }

    /// Fields equal to their default are left out.
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(username, forKey: .username)
        try c.encode(secret, forKey: .secret)
        if mode != "Token" { try c.encode(mode, forKey: .mode) }
        try c.encode(server, forKey: .server)
        if let home { try c.encode(home, forKey: .home) }
        if !displayName.isEmpty { try c.encode(displayName, forKey: .displayName) }
    }

    public var description: String { "HandOverSignIn(username=\(username), server=\(server), home=\(home ?? "null"))" }
}

/// The box could not be opened: the key is not the one it was sealed with, or it was changed.
public struct HandOverError: Error, Equatable, Sendable {
    public let message: String
}

/// Sealing with XChaCha20-Poly1305: a random 24 byte nonce, a 256 bit key, and the purpose as
/// associated data, so a box made for anything else never opens here.
public enum HandOverBox {
    static let keyBytes = 32
    static let nonceBytes = 24
    static let purpose = Array("octo-family-signin-1".utf8)

    /// A new key, made on this device, as text for the QR code.
    public static func newKey() -> String {
        Base64URL.encode((0..<keyBytes).map { _ in UInt8.random(in: 0...255) })
    }

    public static func seal(_ signIn: HandOverSignIn, key: String) throws -> String {
        let nonce = (0..<nonceBytes).map { _ in UInt8.random(in: 0...255) }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        let plain = Array(try encoder.encode(signIn))
        let sealed = try XChaCha20Poly1305.seal(plain, key: try keyBytes(key), nonce: nonce, aad: purpose)
        return Base64URL.encode(nonce + sealed)
    }

    public static func open(_ box: String, key: String) throws -> HandOverSignIn {
        guard let raw = Base64URL.decode(box.trimmingCharacters(in: .whitespacesAndNewlines)), raw.count > nonceBytes + 16 else {
            throw HandOverError(message: "The sign-in from your other device is damaged.")
        }
        let opened: [UInt8]
        do {
            opened = try XChaCha20Poly1305.open(Array(raw.dropFirst(nonceBytes)), key: try keyBytes(key),
                                                nonce: Array(raw.prefix(nonceBytes)), aad: purpose)
        } catch let error as HandOverError {
            throw error
        } catch {
            throw HandOverError(message: "This sign-in could not be opened here. Make a new QR code on your other device.")
        }
        guard let signIn = try? JSONDecoder().decode(HandOverSignIn.self, from: Data(opened)) else {
            throw HandOverError(message: "The sign-in from your other device is damaged.")
        }
        return signIn
    }

    private static func keyBytes(_ key: String) throws -> [UInt8] {
        guard let bytes = Base64URL.decode(key.trimmingCharacters(in: .whitespacesAndNewlines)), bytes.count == keyBytes else {
            throw HandOverError(message: "This sign-in link is not complete. Scan the QR code again.")
        }
        return bytes
    }
}

/// URL-safe base64 without padding; reading also takes padding.
enum Base64URL {
    static func encode(_ bytes: [UInt8]) -> String {
        Data(bytes).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func decode(_ text: String) -> [UInt8]? {
        guard text.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" || $0 == "=") }) else { return nil }
        var standard = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        standard = standard.replacingOccurrences(of: "=", with: "")
        if standard.count % 4 == 1 { return nil }
        while standard.count % 4 != 0 { standard += "=" }
        return Data(base64Encoded: standard).map(Array.init)
    }
}

/// What the new device says at each step, in plain words.
public let handOverWaiting = "Waiting for your other device"
public let handOverAllowed = "Allowed, signing in"
public let handOverSignedIn = "Signed in"
public let handOverDenied = "Your other device said no. Nothing was shared."
public let handOverExpired = "This sign-in link expired. Make a new one on your other device."
public let handOverUnreachable = "Octo can't reach the server. Check this device is online, then try again."

/// The hand-over's row on the sign-in page: the step under way, else, after a failure, that the
/// phone is not signed in to `server` yet, else that it is ready to sign in there, the server named
/// as people read it.
public func handOverRowLine(step: String?, failed: Bool, server: String) -> String {
    if let step { return step }
    let host = shownServer(server)
    return failed ? "Not signed in to \(host) yet" : "Ready to sign in to \(host)"
}

/// How the new device's side ended: the sign-in to keep, or why not. A refusal names the sign-in
/// page in front of the server when one answered, and whether the app's sign-in for it went
/// along.
public enum HandOverResult: Sendable, Equatable {
    case received(HandOverSignIn)
    case refused(String, gateway: Gateway? = nil, loginSent: Bool = false)
}

/// The new device's side: redeems the token on the address the link was opened on, through the
/// client `connect` makes for that address (the sign-in form's headers, certificates and sign-in
/// page fields), says what is happening, waits for the other device, and opens the sealed
/// sign-in with the key from the link. The key is never sent. A sign-in page in front of the
/// server stops it with its own words, never as an expired link.
public func receiveHandOver(_ link: FamilyHandOverLink, connect: @Sendable (URL) -> SubsonicClient, deviceName: String, platform: FamilyPlatform,
                            step: @Sendable (String) async -> Void = { _ in },
                            pollMs: Int = 1_500, waitMs: Int = 130_000) async -> HandOverResult {
    guard let base = ServerAddress.httpURL(link.base) else {
        return .refused("This sign-in link has no server in it. Scan the QR code again.")
    }
    let client = connect(base)
    await step(handOverWaiting)
    let id: String
    do {
        id = try await FamilyOpenCalls.redeem(client: client, token: link.token, deviceName: deviceName, platform: platform)
    } catch is CancellationError {
        return .refused(handOverExpired)
    } catch {
        let failure = SubsonicError(error)
        if let refusal = gatewayRefusal(failure) { return refusal }
        switch failure {
        case .unreachable: return .refused(handOverUnreachable)
        case .notFound, .wrongCredentials: return .refused(handOverExpired)
        case .notSubsonic(_, 404, _, _): return .refused(handOverExpired)
        case let other: return .refused(other.message.isEmpty ? handOverExpired : other.message)
        }
    }
    var waited = 0
    while waited < waitMs {
        if Task.isCancelled { return .refused(handOverExpired) }
        var status: FamilySignInStatus?
        do {
            status = try await FamilyOpenCalls.status(client: client, id: id)
        } catch is CancellationError {
            return .refused(handOverExpired)
        } catch {
            let failure = SubsonicError(error)
            if let refusal = gatewayRefusal(failure) { return refusal }
            if case .unreachable = failure { status = nil } else { return .refused(handOverExpired) }
        }
        switch status?.state {
        case .allowed?:
            await step(handOverAllowed)
            if let box = status?.box {
                do {
                    return .received(try HandOverBox.open(box, key: link.key))
                } catch let error as HandOverError {
                    return .refused(error.message)
                } catch {
                    return .refused(handOverExpired)
                }
            }
        case .denied?: return .refused(handOverDenied)
        case .expired?: return .refused(handOverExpired)
        default: break
        }
        try? await Task.sleep(nanoseconds: UInt64(pollMs) * 1_000_000)
        waited += pollMs
    }
    return .refused(handOverExpired)
}

/// A sign-in page in front of the server, as a refusal that says so: never an expired link,
/// since the link may well still be good.
private func gatewayRefusal(_ failure: SubsonicError) -> HandOverResult? {
    guard let gateway = failure.gateway else { return nil }
    return .refused(GatewayWords.failure(gateway, loginSent: failure.loginSent), gateway: gateway, loginSent: failure.loginSent)
}
