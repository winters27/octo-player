import Foundation
import Synchronization
import Testing
@testable import OctoKit

/// The commit word main and the render thread share, and the memory of a spare song that failed.
@Suite struct BlendSessionCommitTests {
    @Test func theWordPacksTheGenerationAndTheState() {
        for generation: Int64 in [0, 1, 77, (1 << 61) - 1] {
            for state in CommitState.allCases {
                let word = commitWord(generation: generation, state: state)
                #expect(word >= 0)
                #expect(commitGeneration(of: word) == generation)
                #expect(commitState(of: word) == state)
            }
        }
    }

    @Test func aStartMovesOnlyForwardAndAStaleGenerationNeverMatches() {
        let word = CommitWord()
        #expect(word.state(of: 0) == .cancelled)
        #expect(word.state(of: 1) == nil)

        word.open(1)
        #expect(!word.ack(1), "not proposed yet")
        #expect(word.propose(1))
        #expect(!word.propose(1))
        #expect(word.ack(1))
        #expect(!word.cancel(1), "handed over")
        #expect(word.state(of: 1) == .acked)

        word.open(2)
        #expect(word.state(of: 1) == nil)
        #expect(!word.ack(1))
        #expect(!word.cancel(1))
        #expect(word.cancel(2), "an unproposed start cancels")
        #expect(!word.propose(2))
        #expect(!word.cancel(2))

        word.open(3)
        #expect(word.propose(3))
        #expect(word.cancel(3), "a proposed start cancels")
        #expect(!word.ack(3))
        #expect(word.state(of: 3) == .cancelled)
    }

    private final class Wins: Sendable {
        let count = Atomic<Int>(0)
    }

    @Test func mainsAckAndTheDeadlinesCancelNeverBothWin() {
        let word = CommitWord()
        for round in 1...5_000 {
            let generation = Int64(round)
            word.open(generation)
            _ = word.propose(generation)
            let wins = Wins()
            DispatchQueue.concurrentPerform(iterations: 2) { side in
                let won = side == 0 ? word.ack(generation) : word.cancel(generation)
                if won { wins.count.add(1, ordering: .relaxed) }
            }
            #expect(wins.count.load(ordering: .relaxed) == 1, "round \(round)")
        }
    }

    @Test func aFailedSpareIsNotLoadedAgainUntilTheSongChanges() {
        var memory = BlendFailedSpare()
        #expect(memory.mayLoad("b"))
        memory.failed("b")
        #expect(!memory.mayLoad("b"))
        #expect(memory.mayLoad("c"))
        memory.songChanged()
        #expect(memory.mayLoad("b"))
    }
}
