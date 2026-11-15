import Foundation
import Observation

/// The signed-in account's change of password, one at a time from every form that sends one
/// (Settings' Server page, Family's sheet). Once a change is sent the server may take the new
/// password at any moment, so nothing a form does (closed, swiped away, opened again) sends a
/// second one beside it: two could land on the server in one order and be kept on the phone in the
/// other. The app keeps one for as long as it runs.
@MainActor
@Observable
public final class PasswordChanges {
    /// A change is on its way, from any form.
    public private(set) var running = false

    public init() {}

    /// Sends a change through `send`, unless one is on its way: then nothing is sent and the answer
    /// is nil. The change is claimed here, before anything waits, so a second Done in the same
    /// moment finds this one, and it runs in a task of its own, which nothing a form does cancels.
    /// What it came to, in `words`, goes to its form through `answered`, which answers false once
    /// that form is gone; `notice` then says it. A change that worked is said in `notice` either
    /// way, with the check (`notice`'s second value). Answers the change's task.
    @discardableResult
    public func change(_ send: @escaping @MainActor () async -> PasswordChange,
                       words: @escaping @MainActor (PasswordChange) -> String,
                       answered: @escaping @MainActor (PasswordChange, String) -> Bool,
                       notice: @escaping @MainActor (String, Bool) -> Void) -> Task<Void, Never>? {
        guard !running else { return nil }
        running = true
        return Task { @MainActor in
            let result = await send()
            let said = words(result)
            let shown = answered(result, said)
            if result == .changed {
                notice(said, true)
            } else if !shown {
                notice(said, false)
            }
            running = false
        }
    }
}
