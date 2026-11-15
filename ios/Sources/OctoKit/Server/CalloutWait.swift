import Foundation

/// What a sign-in page callout waits for: the on-screen keyboard finished closing, a field took
/// focus (the person is typing, so nothing scrolls), or a size it reads was measured.
public enum CalloutWake: Sendable, Equatable {
    case hidden, focused, measured
}

/// One callout's turn at waiting, from `CalloutWait.begin()` until `CalloutWait.end(_:)`.
public struct CalloutRun: Hashable, Sendable {
    let token: Int
}

/// What a callout brings into view: the sign-in page group's title just under the top fade, the
/// group's last note above the view's bottom (a group taller than the view), or the API key row.
public enum CalloutTarget: Sendable, Equatable {
    case marker, end, key
}

/// Which callout runs: the sign-in page group's, or the API key row's, which sits in the main card,
/// so it measures nothing and does not wait for Advanced.
public enum CalloutKind: Sendable, Equatable {
    case gateway, key
}

/// The waits of the sign-in page's callouts. Each run hears the wakes sent while it lasts on a
/// stream of its own, so a wake sent before it began never ends it, and ending or cancelling one
/// run leaves the others waiting.
@MainActor
public final class CalloutWait {
    private var runs: [Int: (stream: AsyncStream<CalloutWake>, feed: AsyncStream<CalloutWake>.Continuation)] = [:]
    private var last = 0

    public init() {}

    /// A new run, which hears every wake sent from now until it ends.
    public func begin() -> CalloutRun {
        last += 1
        let (stream, feed) = AsyncStream.makeStream(of: CalloutWake.self)
        runs[last] = (stream, feed)
        return CalloutRun(token: last)
    }

    /// Tells every run that has begun and not ended.
    public func send(_ wake: CalloutWake) {
        for run in runs.values { run.feed.yield(wake) }
    }

    /// The run's next `wanted` wake, or `.focused`, which ends every wait; other wakes are passed
    /// over. Nil once the run has ended or its task is cancelled.
    public func next(_ run: CalloutRun, for wanted: CalloutWake) async -> CalloutWake? {
        guard let stream = runs[run.token]?.stream else { return nil }
        for await wake in stream where wake == wanted || wake == .focused {
            return wake
        }
        return nil
    }

    /// Ends `run`: a wait still on it answers nil, and the other runs go on.
    public func end(_ run: CalloutRun) {
        runs.removeValue(forKey: run.token)?.feed.finish()
    }
}

/// How far under the scroll's top a callout lands its target: the 56 points of the top fade and 22
/// more, clear of the fade and the Back button.
public let calloutLanding: Double = 78

/// One callout. A field focused since the callout was asked for ends it at once, with `shown` and
/// no scroll. Otherwise the keyboard is closed and, when it was up, waited for until it has
/// finished closing; the sign-in page group's callout then waits until the group and the view are
/// measured and, with Advanced still open, brings the group's title under the top fade, or, for a
/// group (`groupHeight`: its title through what the callout must show) taller than the view less
/// `calloutLanding`, that part's end above the view's bottom; the key's callout brings the key row
/// under the fade. A field taking focus on the way ends it with `shown` and no scroll. A run
/// cancelled on the way, or one that finds Advanced shut, ends without `shown`, so the callout waits
/// for its next run.
@MainActor
public func runCallout(_ kind: CalloutKind, wait: CalloutWait, focusedAtStart: () -> Bool, keyboardWasUp: () -> Bool,
                       clearFocus: () -> Void, groupHeight: () -> Double?, visibleHeight: () -> Double?,
                       advancedOpen: () -> Bool, scroll: (CalloutTarget) -> Void, shown: () -> Void) async {
    if focusedAtStart() {
        shown()
        return
    }
    let wasUp = keyboardWasUp()
    let run = wait.begin()
    defer { wait.end(run) }
    clearFocus()
    if wasUp {
        switch await wait.next(run, for: .hidden) {
        case .focused?:
            shown()
            return
        case nil:
            return
        default:
            break
        }
    }
    if kind == .gateway {
        while groupHeight() == nil || visibleHeight() == nil {
            switch await wait.next(run, for: .measured) {
            case .focused?:
                shown()
                return
            case nil:
                return
            default:
                continue
            }
        }
        guard advancedOpen() else { return }
    }
    await Task.yield()
    guard !Task.isCancelled, kind == .key || advancedOpen() else { return }
    if kind == .key {
        scroll(.key)
    } else if let group = groupHeight(), let visible = visibleHeight(), group <= visible - calloutLanding {
        scroll(.marker)
    } else {
        scroll(.end)
    }
    shown()
}
