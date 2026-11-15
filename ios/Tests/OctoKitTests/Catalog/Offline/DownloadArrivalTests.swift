import Foundation
import Testing
@testable import OctoKit

/// What an arrived download is named and labeled by, the container it is put into, and the pieces a
/// closed app left behind.
@Suite struct DownloadArrivalTests {
    func ogg(_ magic: String) -> [UInt8] {
        var page = Array("OggS".utf8) + [0, 2] + [UInt8](repeating: 0, count: 20) + [1, 19]
        page += Array(magic.utf8)
        return page + [UInt8](repeating: 0, count: 64 - page.count)
    }

    @Test func theSniffTellsEveryKindApart() {
        #expect(sniffDownloadAudio(ogg("OpusHead")) == "audio/opus")
        #expect(sniffDownloadAudio(ogg("\u{01}vorbis")) == "audio/ogg")
        #expect(sniffDownloadAudio([0xFF, 0xF1, 0x50, 0x80]) == "audio/aac")
        #expect(sniffDownloadAudio([0xFF, 0xFB, 0x90, 0x00]) == "audio/mpeg")
        #expect(sniffDownloadAudio([0xFF, 0xF3, 0x90, 0x00]) == "audio/mpeg")
        #expect(sniffDownloadAudio(Array("fLaC".utf8) + [0, 0, 0, 34]) == "audio/flac")
        #expect(sniffDownloadAudio([0, 0, 0, 0x20] + Array("ftypM4A ".utf8)) == "audio/mp4")
        #expect(sniffDownloadAudio(Array("RIFF".utf8) + [0, 0, 0, 0] + Array("WAVE".utf8)) == "audio/wav")
        #expect(sniffDownloadAudio([0x1A, 0x45, 0xDF, 0xA3, 0x01]) == "audio/webm")
        #expect(sniffDownloadAudio(Array("caff".utf8) + [0, 1, 0, 0]) == "audio/x-caf")
        #expect(sniffDownloadAudio(Array("<html>".utf8)) == nil)
        // An ID3 tag in front of a FLAC is passed over.
        let tag: [UInt8] = Array("ID3".utf8) + [4, 0, 0, 0, 0, 0, 4] + [0, 0, 0, 0]
        #expect(sniffDownloadAudio(tag + Array("fLaC".utf8)) == "audio/flac")
    }

    @Test func aDownloadAskedAsItIsKeepsItsListedTypeWhenItsBytesAreThatKind() {
        let flac = Array("fLaC".utf8) + [0, 0, 0, 34]
        let arrival = downloadArrival(head: flac, answeredType: "audio/flac", listed: "audio/x-flac", askedSmaller: false, asked: .opus)
        #expect(arrival == DownloadArrival(label: "audio/x-flac", madeSmaller: false))
        #expect(arrival.ext == "flac")
    }

    @Test func aDownloadAskedAsItIsButSentSmallerIsASmallerCopy() {
        // A family limit had the server send an MP3 for a listed FLAC.
        let arrival = downloadArrival(head: [0xFF, 0xFB, 0x90, 0x00], answeredType: "audio/mpeg", listed: "audio/flac",
                                      askedSmaller: false, asked: .opus)
        #expect(arrival == DownloadArrival(label: "audio/mpeg", madeSmaller: true))
    }

    @Test func opusGoesIntoACafAndASmallerAacIntoAnM4a() {
        let opus = downloadArrival(head: ogg("OpusHead"), answeredType: "audio/ogg", listed: "audio/flac", askedSmaller: true, asked: .opus)
        #expect(opus == DownloadArrival(label: "audio/opus", madeSmaller: true, rewrap: .caf))
        #expect(opus.ext == "caf")
        let aac = downloadArrival(head: [0xFF, 0xF1, 0x50, 0x80], answeredType: "audio/aac", listed: "audio/flac", askedSmaller: true, asked: .aac)
        #expect(aac == DownloadArrival(label: "audio/mp4", madeSmaller: true, rewrap: .m4a))
        // A library Ogg Opus file kept as it is still goes into a CAF, and is not a smaller copy.
        let raw = downloadArrival(head: ogg("OpusHead"), answeredType: "audio/ogg", listed: "audio/ogg", askedSmaller: false, asked: .opus)
        #expect(raw == DownloadArrival(label: "audio/opus", madeSmaller: false, rewrap: .caf))
        // A library ADTS file kept as it is goes into an M4A too, and stays unmarked.
        let rawAac = downloadArrival(head: [0xFF, 0xF1, 0x50, 0x80], answeredType: "audio/aac", listed: "audio/aac", askedSmaller: false, asked: .opus)
        #expect(rawAac == DownloadArrival(label: "audio/mp4", madeSmaller: false, rewrap: .m4a))
    }

    @Test func anOggVorbisOrAWebmCannotBeKept() {
        #expect(!downloadArrival(head: ogg("\u{01}vorbis"), answeredType: "audio/ogg", listed: "audio/ogg", askedSmaller: false, asked: .opus).playable)
        #expect(!downloadArrival(head: [0x1A, 0x45, 0xDF, 0xA3, 0x01], answeredType: "audio/webm", listed: "audio/flac", askedSmaller: true, asked: .opus).playable)
    }

    @Test func aSmallerCopyTheBytesSayNothingOfIsNamedByTheAnswerThenTheAsk() {
        let byAnswer = downloadArrival(head: [1, 2, 3, 4], answeredType: "audio/mpeg; charset=x", listed: "audio/flac", askedSmaller: true, asked: .aac)
        #expect(byAnswer.label == "audio/mpeg")
        let byAsk = downloadArrival(head: [1, 2, 3, 4], answeredType: "application/octet-stream", listed: "audio/flac", askedSmaller: true, asked: .aac)
        #expect(byAsk.label == "audio/aac")
    }

    /// A rewrap that waits until the test lets it go.
    actor Parked {
        private var parked: CheckedContinuation<Void, Never>?
        private var waiting: CheckedContinuation<Void, Never>?
        private var isParked = false

        func park() async {
            await withCheckedContinuation { continuation in
                parked = continuation
                isParked = true
                waiting?.resume()
                waiting = nil
            }
        }

        func untilParked() async {
            if isParked { return }
            await withCheckedContinuation { waiting = $0 }
        }

        func release() {
            parked?.resume()
            parked = nil
        }
    }

    actor Wanted {
        var value = true
        func set(_ on: Bool) { value = on }
    }

    func arrivedAdts() throws -> (folder: URL, job: ArrivalJob) {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("arrival-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let part = folder.appendingPathComponent("A - B [a1b2c3d4~s].aac.part")
        try Data([0xFF, 0xF1, 0x50, 0x80, 0x00, 0x00, 0x00] + [UInt8](repeating: 0x21, count: 64)).write(to: part)
        let job = ArrivalJob(part: part, folder: folder, startName: "A - B [a1b2c3d4~s].aac", answeredType: "audio/aac",
                             listed: "audio/flac", askedSmaller: true, asked: .aac)
        return (folder, job)
    }

    @Test func aRemovalWhileTheRewrapIsParkedLeavesNoFile() async throws {
        let (folder, job) = try arrivedAdts()
        defer { try? FileManager.default.removeItem(at: folder) }
        let parked = Parked()
        let wanted = Wanted()
        let running = Task {
            await finishArrival(job, stillWanted: { await wanted.value }, rewrap: { _, _, out in
                FileManager.default.createFile(atPath: out.path, contents: Data([1, 2, 3]))
                await parked.park()
                return true
            })
        }
        await parked.untilParked()
        await wanted.set(false)
        await parked.release()
        #expect(await running.value == .removed)
        #expect(try FileManager.default.contentsOfDirectory(atPath: folder.path).isEmpty)
    }

    @Test func aWantedDownloadTakesItsFinalNameAndItsPiecesGo() async throws {
        let (folder, job) = try arrivedAdts()
        defer { try? FileManager.default.removeItem(at: folder) }
        let outcome = await finishArrival(job, stillWanted: { true }, rewrap: { kind, _, out in
            #expect(kind == .m4a)
            return FileManager.default.createFile(atPath: out.path, contents: Data([1, 2, 3]))
        })
        let target = folder.appendingPathComponent("A - B [a1b2c3d4~s].m4a")
        #expect(outcome == .finished(target: target, label: "audio/mp4"))
        #expect(try FileManager.default.contentsOfDirectory(atPath: folder.path) == ["A - B [a1b2c3d4~s].m4a"])
    }

    @Test func anAdtsThatWillNotGoIntoAnM4aIsNotKept() async throws {
        let (folder, job) = try arrivedAdts()
        defer { try? FileManager.default.removeItem(at: folder) }
        let outcome = await finishArrival(job, stillWanted: { true }, rewrap: { _, _, _ in false })
        #expect(outcome == .failed(rewrap: true))
        #expect(try FileManager.default.contentsOfDirectory(atPath: folder.path).isEmpty)
    }

    /// The Ogg Opus that arrived, on a phone whose system cannot play Opus in a CAF (or whose CAF
    /// would not write): kept as it is, in the format picked, for Octo's own Opus decoder.
    @Test func anOpusThePhonesSystemCannotPlayIsKeptAsOggOpus() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let part = folder.appendingPathComponent("A - B [a1b2c3d4~s].opus.part")
        try Data(ogg("OpusHead") + [UInt8](repeating: 0, count: 64)).write(to: part)
        let keep = ArrivalJob(part: part, folder: folder, startName: "A - B [a1b2c3d4~s].opus", answeredType: "audio/ogg",
                              listed: "audio/flac", askedSmaller: true, asked: .opus, opusInCaf: false)
        let kept = await finishArrival(keep, stillWanted: { true }, rewrap: { _, _, _ in
            Issue.record("no CAF is written without the system's Opus")
            return false
        })
        #expect(kept == .finished(target: folder.appendingPathComponent("A - B [a1b2c3d4~s].opus"), label: "audio/opus"))
        // A CAF that will not write leaves the same Ogg Opus, never a failed download or AAC.
        let again = folder.appendingPathComponent("C [b1b2c3d4~s].opus.part")
        try Data(ogg("OpusHead") + [UInt8](repeating: 0, count: 64)).write(to: again)
        let tried = ArrivalJob(part: again, folder: folder, startName: "C [b1b2c3d4~s].opus", answeredType: "audio/ogg",
                               listed: "audio/flac", askedSmaller: true, asked: .opus, opusInCaf: true)
        let fellBack = await finishArrival(tried, stillWanted: { true }, rewrap: { _, _, _ in false })
        #expect(fellBack == .finished(target: folder.appendingPathComponent("C [b1b2c3d4~s].opus"), label: "audio/opus"))
    }

    /// An Ogg Opus Octo's own decoder cannot read (a cut page) is never kept as Done: the try fails and
    /// nothing is left, whether it was to stay Ogg or its CAF would not write.
    @Test func anOggOpusThatWillNotReadFailsTheTry() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        for inCaf in [false, true] {
            let part = folder.appendingPathComponent("A - B [a1b2c3d4~s].opus.part")
            try Data(ogg("OpusHead") + [UInt8](repeating: 0, count: 64)).write(to: part)
            let job = ArrivalJob(part: part, folder: folder, startName: "A - B [a1b2c3d4~s].opus", answeredType: "audio/ogg",
                                 listed: "audio/flac", askedSmaller: true, asked: .opus, opusInCaf: inCaf)
            let outcome = await finishArrival(job, stillWanted: { true }, readsAsOgg: { _ in false }, rewrap: { _, _, _ in false })
            #expect(outcome == .failed(rewrap: true), "in a CAF: \(inCaf)")
            #expect(try FileManager.default.contentsOfDirectory(atPath: folder.path).isEmpty)
        }
    }

    @Test func onlyThePiecesAtTheTopGo() {
        let entries: [(name: String, isFile: Bool)] = [
            ("A - B [a1b2c3d4].flac", true), ("A - B [a1b2c3d4~s].opus.part", true), ("C [b1b2c3d4~s].caf.part", true),
            (".removed", false), ("loops", false), ("folder.part", false),
        ]
        #expect(leftoverDownloadParts(entries) == ["A - B [a1b2c3d4~s].opus.part", "C [b1b2c3d4~s].caf.part"])
    }
}
