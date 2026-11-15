import Foundation

/// Where a device's reading of a server's version changes stands: the server's `epoch` and the
/// last `seq` it handed out. Kept per server.
public struct StreamVersionCursor: Codable, Sendable, Equatable {
    public var epoch: String
    public var seq: Int64

    public init(epoch: String, seq: Int64) {
        self.epoch = epoch
        self.seq = seq
    }
}

/// What one getStreamVersionChanges answer asks of a device: drop every saved outside song
/// (`dropAll`), and in any case the songs of `dropIds`, then keep `next`.
public struct FeedPlan: Sendable, Equatable {
    public var dropAll: Bool
    public var dropIds: Set<String>
    public var next: StreamVersionCursor

    public init(dropAll: Bool, dropIds: Set<String>, next: StreamVersionCursor) {
        self.dropAll = dropAll
        self.dropIds = dropIds
        self.next = next
    }
}

/// The plan for an answer to the cursor `stored` (nil: none kept for this server yet). Every saved
/// outside song goes when no cursor was kept (the device cannot know what changed before), when
/// the server's epoch is another (its record of changes started again), or when the answer is not
/// complete.
public func planFeed(stored: StreamVersionCursor?, answer: StreamVersionChanges) -> FeedPlan {
    FeedPlan(
        dropAll: stored == nil || stored?.epoch != answer.epoch || !answer.complete,
        dropIds: Set(answer.change.flatMap(\.ids)),
        next: StreamVersionCursor(epoch: answer.epoch, seq: answer.seq)
    )
}
