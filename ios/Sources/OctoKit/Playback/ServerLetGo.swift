import Foundation

/// What a player does with a server's refusal of a song's stream.
public enum RefusalAction: Equatable, Sendable {
    /// The server let go of the file the song was read from: the saved copy goes and the song
    /// opens again at its place from the file the server has now.
    case reopen
    /// Asked again, like a broken connection.
    case retry
    /// The song fails.
    case fail
}

/// The answer to a refusal of the request that asked from byte `offset`: a 503 for a later part
/// (`offset` above 0) means the server let go of the song's file (Octo answers so when the kept
/// copy a play read is gone); any other refusal is asked again up to three times in a row while
/// online, except a place past the end; then the song fails.
public func refusalAction(status: Int, offset: Int64, errorsInARow: Int, online: Bool) -> RefusalAction {
    if status == 503 && offset > 0 { return .reopen }
    if status != 416 && errorsInARow <= 3 && online { return .retry }
    return .fail
}
