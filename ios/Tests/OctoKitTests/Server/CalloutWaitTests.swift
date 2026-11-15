import Foundation
import OctoKit
import Testing

/// Waits with a time limit, so a wrong build fails red instead of hanging the test run. Every wait
/// handed in here ends when its task is cancelled.
enum Bounded {
    struct TooLate: Error {}

    /// `work`'s answer, or `TooLate` (a failed row) when it has none after five seconds.
    static func answer<T: Sendable>(_ work: @escaping @Sendable () async -> T) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { await work() }
            group.addTask {
                try await Task.sleep(for: .seconds(5))
                throw TooLate()
            }
            defer { group.cancelAll() }
            guard let first = try await group.next() else { throw TooLate() }
            return first
        }
    }

    /// Waits for `task` to end; after five seconds it is cancelled and the row fails.
    static func finished(_ task: Task<Void, Never>) async throws {
        try await answer {
            await withTaskCancellationHandler {
                await task.value
            } onCancel: {
                task.cancel()
            }
        }
    }
}

/// One callout run's world: what it reads, and what it did.
@MainActor
final class CalloutProbe {
    var focusedAtStart = false
    var keyboardWasUp = false
    var groupHeight: Double?
    var visibleHeight: Double?
    var advancedOpen = true
    private(set) var cleared = 0
    private(set) var scrolled: [CalloutTarget] = []
    private(set) var shown = 0
    private let clears: AsyncStream<Void>
    private let clearsFeed: AsyncStream<Void>.Continuation

    init() {
        let (stream, feed) = AsyncStream.makeStream(of: Void.self)
        clears = stream
        clearsFeed = feed
    }

    func run(_ kind: CalloutKind, _ wait: CalloutWait) async {
        await runCallout(kind, wait: wait, focusedAtStart: { self.focusedAtStart }, keyboardWasUp: { self.keyboardWasUp },
                         clearFocus: {
                             self.cleared += 1
                             self.clearsFeed.yield()
                         },
                         groupHeight: { self.groupHeight }, visibleHeight: { self.visibleHeight },
                         advancedOpen: { self.advancedOpen }, scroll: { self.scrolled.append($0) }, shown: { self.shown += 1 })
    }

    /// Returns once the run has closed the keyboard: it has begun its turn and waits for its next wake.
    func keyboardClosed() async {
        for await _ in clears { return }
    }
}

@MainActor
@Suite struct CalloutWaitTests {
    /// Red when a wake sent before a run began ends it (a stale keyboard close would scroll early).
    @Test func aWakeSentBeforeARunBeganIsNotHeardByIt() async throws {
        let wait = CalloutWait()
        wait.send(.focused)
        let run = wait.begin()
        wait.send(.hidden)
        let heard = try await Bounded.answer { await wait.next(run, for: .hidden) }
        #expect(heard == .hidden)
    }

    /// Red when a wait takes any wake for the one it waits for.
    @Test func eachWaitPassesOverTheOtherKinds() async throws {
        let wait = CalloutWait()
        let run = wait.begin()
        wait.send(.measured)
        wait.send(.hidden)
        let hidden = try await Bounded.answer { await wait.next(run, for: .hidden) }
        #expect(hidden == .hidden)
        wait.send(.hidden)
        wait.send(.measured)
        let measured = try await Bounded.answer { await wait.next(run, for: .measured) }
        #expect(measured == .measured)
    }

    /// Red when a cancelled run's wait does not answer nil, or the cancel ends the next run's waits.
    @Test func aRunAfterACancelledRunStillWaits() async throws {
        let wait = CalloutWait()
        let first = wait.begin()
        let cancelled = Task { await wait.next(first, for: .hidden) }
        cancelled.cancel()
        let answer = try await Bounded.answer {
            await withTaskCancellationHandler { await cancelled.value } onCancel: { cancelled.cancel() }
        }
        #expect(answer == nil)
        wait.end(first)
        let second = wait.begin()
        wait.send(.hidden)
        let heard = try await Bounded.answer { await wait.next(second, for: .hidden) }
        #expect(heard == .hidden)
    }

    /// Red when only one of two runs hears the keyboard close.
    @Test func twoRunsBothHearTheKeyboardClose() async throws {
        let wait = CalloutWait()
        let first = wait.begin()
        let second = wait.begin()
        wait.send(.hidden)
        let heardFirst = try await Bounded.answer { await wait.next(first, for: .hidden) }
        let heardSecond = try await Bounded.answer { await wait.next(second, for: .hidden) }
        #expect(heardFirst == .hidden)
        #expect(heardSecond == .hidden)
    }

    /// Red when ending one run ends the others, or an ended run still waits.
    @Test func endingAnOlderRunLeavesANewerOneWaiting() async throws {
        let wait = CalloutWait()
        let older = wait.begin()
        let newer = wait.begin()
        wait.end(older)
        wait.send(.hidden)
        let heard = try await Bounded.answer { await wait.next(newer, for: .hidden) }
        #expect(heard == .hidden)
        let ended = try await Bounded.answer { await wait.next(older, for: .hidden) }
        #expect(ended == nil)
    }

    /// Red when a field taking focus does not end a wait for the keyboard or for a measure.
    @Test func aFocusedFieldEndsEitherWait() async throws {
        let wait = CalloutWait()
        let run = wait.begin()
        wait.send(.focused)
        let duringHide = try await Bounded.answer { await wait.next(run, for: .hidden) }
        #expect(duringHide == .focused)
        wait.send(.focused)
        let duringMeasure = try await Bounded.answer { await wait.next(run, for: .measured) }
        #expect(duringMeasure == .focused)
    }

    /// Red when a cancelled run's wait hangs or answers a wake.
    @Test func aCancelledRunsWaitAnswersNil() async throws {
        let wait = CalloutWait()
        let run = wait.begin()
        let waiting = Task { await wait.next(run, for: .measured) }
        waiting.cancel()
        let answer = try await Bounded.answer {
            await withTaskCancellationHandler { await waiting.value } onCancel: { waiting.cancel() }
        }
        #expect(answer == nil)
    }
}

@MainActor
@Suite struct CalloutRunTests {
    private func makeProbe(up: Bool = false, group: Double? = 300, visible: Double? = 700) -> CalloutProbe {
        let probe = CalloutProbe()
        probe.keyboardWasUp = up
        probe.groupHeight = group
        probe.visibleHeight = visible
        return probe
    }

    // The sign-in page group's callout.

    /// Red when a field focused since the callout was asked for is moved away from.
    @Test func aFieldFocusedBeforeTheRunEndsItAtOnce() async throws {
        let wait = CalloutWait()
        let probe = makeProbe(up: true)
        probe.focusedAtStart = true
        let run = Task { await probe.run(.gateway, wait) }
        try await Bounded.finished(run)
        #expect(probe.shown == 1)
        #expect(probe.scrolled.isEmpty)
        #expect(probe.cleared == 0)
    }

    /// Red when a field focused while the keyboard closes is scrolled away from.
    @Test func aFieldFocusedWhileTheKeyboardClosesEndsItWithNoScroll() async throws {
        let wait = CalloutWait()
        let probe = makeProbe(up: true)
        let run = Task { await probe.run(.gateway, wait) }
        try await Bounded.answer { await probe.keyboardClosed() }
        wait.send(.focused)
        try await Bounded.finished(run)
        #expect(probe.shown == 1)
        #expect(probe.scrolled.isEmpty)
        #expect(probe.cleared == 1)
    }

    /// Red when a field focused while the group is still unmeasured is scrolled away from.
    @Test func aFieldFocusedWhileTheGroupIsMeasuredEndsItWithNoScroll() async throws {
        let wait = CalloutWait()
        let probe = makeProbe(group: nil)
        let run = Task { await probe.run(.gateway, wait) }
        try await Bounded.answer { await probe.keyboardClosed() }
        wait.send(.focused)
        try await Bounded.finished(run)
        #expect(probe.shown == 1)
        #expect(probe.scrolled.isEmpty)
    }

    /// Red when a run cancelled during the keyboard wait marks the callout shown (it would never come).
    @Test func aRunCancelledWhileTheKeyboardClosesLeavesTheCalloutWaiting() async throws {
        let wait = CalloutWait()
        let probe = makeProbe(up: true)
        let run = Task { await probe.run(.gateway, wait) }
        try await Bounded.answer { await probe.keyboardClosed() }
        run.cancel()
        try await Bounded.finished(run)
        #expect(probe.shown == 0)
        #expect(probe.scrolled.isEmpty)
    }

    /// Red when a run cancelled while it waits to measure marks the callout shown.
    @Test func aRunCancelledWhileTheGroupIsMeasuredLeavesTheCalloutWaiting() async throws {
        let wait = CalloutWait()
        let probe = makeProbe(visible: nil)
        let run = Task { await probe.run(.gateway, wait) }
        try await Bounded.answer { await probe.keyboardClosed() }
        run.cancel()
        try await Bounded.finished(run)
        #expect(probe.shown == 0)
        #expect(probe.scrolled.isEmpty)
    }

    /// Red when the group is scrolled to, or marked shown, with Advanced shut.
    @Test func advancedShutOnTheWayLeavesTheCalloutWaiting() async throws {
        let wait = CalloutWait()
        let probe = makeProbe()
        probe.advancedOpen = false
        let run = Task { await probe.run(.gateway, wait) }
        try await Bounded.finished(run)
        #expect(probe.shown == 0)
        #expect(probe.scrolled.isEmpty)
        #expect(probe.cleared == 1)
    }

    /// Red when a run cancelled before its scroll still scrolls or marks the callout shown.
    @Test func aRunCancelledBeforeItScrollsNeitherScrollsNorEnds() async throws {
        let wait = CalloutWait()
        let probe = makeProbe()
        let run = Task { await probe.run(.gateway, wait) }
        run.cancel()
        try await Bounded.finished(run)
        #expect(probe.cleared == 1)
        #expect(probe.scrolled.isEmpty)
        #expect(probe.shown == 0)
    }

    /// Red when a group shorter than the view does not land under the fade.
    @Test func aGroupShorterThanTheViewLandsUnderTheFade() async throws {
        let wait = CalloutWait()
        let probe = makeProbe(group: 300, visible: 700)
        let run = Task { await probe.run(.gateway, wait) }
        try await Bounded.finished(run)
        #expect(probe.scrolled == [.marker])
        #expect(probe.shown == 1)
    }

    /// Red when a group exactly as tall as the view less the landing is shown by its end.
    @Test func aGroupThatJustFitsStillLandsUnderTheFade() async throws {
        #expect(calloutLanding == 78)
        let wait = CalloutWait()
        let probe = makeProbe(group: 622, visible: 700)
        let run = Task { await probe.run(.gateway, wait) }
        try await Bounded.finished(run)
        #expect(probe.scrolled == [.marker])
    }

    /// Red when a group taller than the view puts its title under the fade and its notes off screen.
    @Test func aGroupTallerThanTheViewShowsItsEnd() async throws {
        let wait = CalloutWait()
        let probe = makeProbe(group: 623, visible: 700)
        let run = Task { await probe.run(.gateway, wait) }
        try await Bounded.finished(run)
        #expect(probe.scrolled == [.end])
        #expect(probe.shown == 1)
    }

    /// Red when the run scrolls before the keyboard has finished closing.
    @Test func theRunWaitsForTheKeyboardThenScrolls() async throws {
        let wait = CalloutWait()
        let probe = makeProbe(up: true)
        let run = Task { await probe.run(.gateway, wait) }
        try await Bounded.answer { await probe.keyboardClosed() }
        #expect(probe.scrolled.isEmpty)
        wait.send(.hidden)
        try await Bounded.finished(run)
        #expect(probe.scrolled == [.marker])
        #expect(probe.shown == 1)
    }

    /// Red when the run scrolls before the group has a measured height.
    @Test func theRunWaitsForTheGroupToBeMeasured() async throws {
        let wait = CalloutWait()
        let probe = makeProbe(group: nil)
        let run = Task { await probe.run(.gateway, wait) }
        try await Bounded.answer { await probe.keyboardClosed() }
        #expect(probe.scrolled.isEmpty)
        probe.groupHeight = 900
        wait.send(.measured)
        try await Bounded.finished(run)
        #expect(probe.scrolled == [.end])
    }

    // The API key row's callout.

    /// Red when a field focused since API key was picked is moved away from.
    @Test func aFieldFocusedBeforeTheKeyRunEndsIt() async throws {
        let wait = CalloutWait()
        let probe = makeProbe(up: true)
        probe.focusedAtStart = true
        let run = Task { await probe.run(.key, wait) }
        try await Bounded.finished(run)
        #expect(probe.shown == 1)
        #expect(probe.scrolled.isEmpty)
    }

    /// Red when a field focused while the keyboard closes is scrolled away from.
    @Test func aFieldFocusedWhileTheKeyboardClosesEndsTheKeyRun() async throws {
        let wait = CalloutWait()
        let probe = makeProbe(up: true)
        let run = Task { await probe.run(.key, wait) }
        try await Bounded.answer { await probe.keyboardClosed() }
        wait.send(.focused)
        try await Bounded.finished(run)
        #expect(probe.shown == 1)
        #expect(probe.scrolled.isEmpty)
    }

    /// Red when a key run cancelled during the keyboard wait marks the row shown.
    @Test func aKeyRunCancelledWhileTheKeyboardClosesLeavesItWaiting() async throws {
        let wait = CalloutWait()
        let probe = makeProbe(up: true)
        let run = Task { await probe.run(.key, wait) }
        try await Bounded.answer { await probe.keyboardClosed() }
        run.cancel()
        try await Bounded.finished(run)
        #expect(probe.shown == 0)
        #expect(probe.scrolled.isEmpty)
    }

    /// Red when a key run cancelled before its scroll still scrolls.
    @Test func aKeyRunCancelledBeforeItScrollsNeitherScrollsNorEnds() async throws {
        let wait = CalloutWait()
        let probe = makeProbe(group: nil, visible: nil)
        let run = Task { await probe.run(.key, wait) }
        run.cancel()
        try await Bounded.finished(run)
        #expect(probe.cleared == 1)
        #expect(probe.scrolled.isEmpty)
        #expect(probe.shown == 0)
    }

    /// Red when the key run waits for a measure it never gets (nothing measures the main card).
    @Test func theKeyRunScrollsToTheKeyRowWithNoMeasuring() async throws {
        let wait = CalloutWait()
        let probe = makeProbe(group: nil, visible: nil)
        let run = Task { await probe.run(.key, wait) }
        try await Bounded.finished(run)
        #expect(probe.scrolled == [.key])
        #expect(probe.shown == 1)
    }

    /// Red when the key run waits for Advanced, which the key row is not in.
    @Test func theKeyRunIgnoresAdvanced() async throws {
        let wait = CalloutWait()
        let probe = makeProbe(group: nil, visible: nil)
        probe.advancedOpen = false
        let run = Task { await probe.run(.key, wait) }
        try await Bounded.finished(run)
        #expect(probe.scrolled == [.key])
        #expect(probe.shown == 1)
    }

    /// Red when the key run scrolls before the keyboard has finished closing.
    @Test func theKeyRunWaitsForTheKeyboard() async throws {
        let wait = CalloutWait()
        let probe = makeProbe(up: true, group: nil, visible: nil)
        let run = Task { await probe.run(.key, wait) }
        try await Bounded.answer { await probe.keyboardClosed() }
        #expect(probe.scrolled.isEmpty)
        wait.send(.hidden)
        try await Bounded.finished(run)
        #expect(probe.scrolled == [.key])
        #expect(probe.shown == 1)
    }
}
