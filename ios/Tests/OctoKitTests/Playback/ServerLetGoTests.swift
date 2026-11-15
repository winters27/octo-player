import Testing
@testable import OctoKit

/// Octo refuses a later part of a play with 503 once the kept copy it read is gone: the player opens
/// the song again rather than asking for those bytes three times and failing it.
@Suite struct ServerLetGoTests {
    @Test func aBusyAnswerToALaterPartReopensTheSong() {
        #expect(refusalAction(status: 503, offset: 1_000_000, errorsInARow: 1, online: true) == .reopen)
        // However many refusals came before: the server's file is gone, asking again cannot help.
        #expect(refusalAction(status: 503, offset: 1, errorsInARow: 9, online: false) == .reopen)
    }

    @Test func otherRefusalsAreAskedAgainThenFailAsBefore() {
        // From the first byte a 503 is the server's refusal as it is.
        #expect(refusalAction(status: 503, offset: 0, errorsInARow: 1, online: true) == .retry)
        #expect(refusalAction(status: 500, offset: 4_096, errorsInARow: 3, online: true) == .retry)
        #expect(refusalAction(status: 500, offset: 4_096, errorsInARow: 4, online: true) == .fail)
        #expect(refusalAction(status: 502, offset: 0, errorsInARow: 1, online: false) == .fail)
        // A place past the end is never asked again.
        #expect(refusalAction(status: 416, offset: 9_000_000, errorsInARow: 1, online: true) == .fail)
    }
}
