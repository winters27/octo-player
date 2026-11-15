import Foundation
import OctoKit

/// The server's list of songs given another version (`getStreamVersionChanges`), read at sign-in,
/// when the app comes to the front, after a pick here and before a song found online with a saved
/// copy opens. One read at a time: a read asked for while one is on its way waits for that one.
/// What a read lists goes to `apply` (the player drops what the phone keeps of those songs' old
/// files); where each server's list was read to is kept on the phone by server, so the next read,
/// after a restart too, goes on from there.
actor StreamVersionFeed {
    static let shared = StreamVersionFeed()

    private var server: (@Sendable () async -> ServerInUse?)?
    private var apply: (@Sendable (FeedPlan, String, String?) async -> Void)?
    private var settle: (@Sendable (String?) async -> Void)?
    private var running: Task<Void, Never>?

    func attach(server: @escaping @Sendable () async -> ServerInUse?,
                apply: @escaping @Sendable (FeedPlan, String, String?) async -> Void,
                settle: @escaping @Sendable (String?) async -> Void) {
        self.server = server
        self.apply = apply
        self.settle = settle
    }

    /// Reads the list once, or waits for the read on its way. `opening` names the song about to
    /// open (its id on the server), whose copies go now even while it is the song on now. A read
    /// that fails (offline, the server down) leaves everything as it was.
    func refresh(opening: String? = nil) async {
        if let running {
            await running.value
            await settle?(opening)
            return
        }
        let read = Task { await readOnce(opening) }
        running = read
        await read.value
        running = nil
    }

    private func readOnce(_ opening: String?) async {
        guard let now = await server?(), now.extensions.contains("\(STREAM_VERSION_CHANGES_EXTENSION):1") else { return }
        let key = "versions.cursor.\(now.sourceId)"
        let stored = UserDefaults.standard.string(forKey: key).flatMap(versionCursor(from:))
        guard let answer = try? await now.client.streamVersionChanges(epoch: stored?.epoch, since: stored?.seq) else { return }
        let plan = planFeed(stored: stored, answer: answer)
        await apply?(plan, now.sourceId, opening)
        UserDefaults.standard.set(versionCursorText(plan.next), forKey: key)
    }
}
