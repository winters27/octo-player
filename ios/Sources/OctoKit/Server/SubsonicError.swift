import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// A failed call, in the kinds every Octo app tells apart.
public enum SubsonicError: Error, Equatable, Sendable, LocalizedError {
    /// Username, password or API key rejected (codes 40 and 44).
    case wrongCredentials(String)
    /// The server cannot check this way of signing in (codes 41 and 42).
    case authNotSupported(code: Int, message: String)
    /// The thing asked for does not exist (code 70).
    case notFound(String)
    /// Any other error the server reported.
    case server(code: Int, message: String)
    /// Something answered, but not a Subsonic server. `status` is the HTTP status when the
    /// answer was an error page. `gateway` names the sign-in page in front of the server when
    /// one answered, and `loginSent` is whether the app's own sign-in for that page went along.
    case notSubsonic(String, status: Int?, gateway: Gateway? = nil, loginSent: Bool = false)
    /// The server could not be reached at all. `certificate` when its certificate was refused.
    case unreachable(String, certificate: Bool)

    /// What a proxy or tunnel answers while the server behind it is restarting or down.
    public static let busyStatuses: Set<Int> = [502, 503, 504, 520, 521, 522, 523, 524, 530]

    public var message: String {
        switch self {
        case .wrongCredentials(let text), .notFound(let text), .notSubsonic(let text, _, _, _), .unreachable(let text, _): text
        case .authNotSupported(_, let text), .server(_, let text): text
        }
    }

    public var errorDescription: String? { message }

    /// A proxy in front of the server saying the server behind it is not answering.
    public var serverBusy: Bool {
        if case .notSubsonic(_, let status?, _, _) = self { return Self.busyStatuses.contains(status) }
        return false
    }

    public var isUnreachable: Bool {
        if case .unreachable = self { return true }
        return false
    }

    /// The HTTP status of an error page, if the answer was one.
    public var status: Int? {
        if case .notSubsonic(_, let status, _, _) = self { return status }
        return nil
    }

    /// The sign-in page in front of the server that answered in its place, if one did.
    public var gateway: Gateway? {
        if case .notSubsonic(_, _, let gateway, _) = self { return gateway }
        return nil
    }

    /// Whether the app's own sign-in for that sign-in page went with the request.
    public var loginSent: Bool {
        if case .notSubsonic(_, _, _, let loginSent) = self { return loginSent }
        return false
    }

    /// The server's error code, for the kinds that carry one.
    public var code: Int? {
        switch self {
        case .wrongCredentials: 40
        case .authNotSupported(let code, _), .server(let code, _): code
        case .notFound: 70
        default: nil
        }
    }

    /// Any error a call ended with, read as one of these kinds.
    public init(_ error: Error) {
        switch error {
        case let known as SubsonicError:
            self = known
        case let octo as OctoError:
            switch octo {
            case .wrongCredentials(let message): self = .wrongCredentials(message)
            case .authUnsupported(let code, let message): self = .authNotSupported(code: code, message: message)
            case .notFound(let message): self = .notFound(message)
            case .server(let code, let message): self = .server(code: code, message: message)
            case .notSubsonic(let gateway, let loginSent):
                self = .notSubsonic(Self.notSubsonicWords, status: nil, gateway: gateway, loginSent: loginSent)
            case .http(let status, let gateway, let loginSent):
                self = .notSubsonic("HTTP \(status)", status: status, gateway: gateway, loginSent: loginSent)
            }
        case let url as URLError:
            self = .unreachable(Self.words(url), certificate: Self.isCertificate(url))
        default:
            self = .unreachable(error.localizedDescription.isEmpty ? Self.unreachableWords : error.localizedDescription, certificate: false)
        }
    }

    static let notSubsonicWords = "The server did not answer like a Subsonic server"
    static let unreachableWords = "Could not reach the server"

    static func words(_ error: URLError) -> String {
        let text = error.localizedDescription
        return text.isEmpty ? unreachableWords : text
    }

    /// Whether a connection failed on the server's certificate rather than on the way there.
    public static func isCertificate(_ error: URLError) -> Bool {
        [.serverCertificateUntrusted, .serverCertificateHasBadDate, .serverCertificateHasUnknownRoot,
         .serverCertificateNotYetValid, .secureConnectionFailed, .clientCertificateRejected,
         .clientCertificateRequired].contains(error.code)
    }

    /// A failure to reach the address at all, as opposed to a cancelled call or a server that
    /// answered badly: refused or no route, unknown host, or a timeout. Having no network at all,
    /// or losing it partway, is not one.
    public static func isConnectionError(_ error: URLError) -> Bool {
        [.cannotConnectToHost, .cannotFindHost, .dnsLookupFailed, .timedOut].contains(error.code)
    }
}

/// An error as the app's plain kinds read it, for the words people see (`userMessage`): these
/// kinds as the matching `OctoError` (a sign-in page in front of the server kept with it), a
/// failure to reach the server as a connection error, and anything else as it is.
public func plainError(_ error: Error) -> Error {
    guard let known = error as? SubsonicError else { return error }
    switch known {
    case .wrongCredentials(let message): return OctoError.wrongCredentials(message)
    case .authNotSupported(let code, let message): return OctoError.authUnsupported(code: code, message: message)
    case .notFound(let message): return OctoError.notFound(message)
    case .server(let code, let message): return OctoError.server(code: code, message: message)
    case .notSubsonic(_, let status, let gateway, let loginSent):
        if let status { return OctoError.http(status, gateway: gateway, loginSent: loginSent) }
        return OctoError.notSubsonic(gateway: gateway, loginSent: loginSent)
    case .unreachable: return URLError(.cannotConnectToHost)
    }
}
