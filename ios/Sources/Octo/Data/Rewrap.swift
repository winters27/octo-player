import AudioToolbox
import Foundation
import OctoKit
import os
import UIKit

private let rewrapLog = Logger(subsystem: "app.winters.octo", category: "downloads")

/// Puts a download's Opus into a CAF and a smaller copy's AAC into an M4A, with the phone's own file
/// writer, then checks the result plays through the decoder that plays it.
enum Rewrap {
    /// An audio framework status that stopped a write.
    struct Failure: Error {
        let status: OSStatus
    }

    /// Writes `from` into `kind`'s container at `to` and checks it, off the main actor and with time
    /// to finish should the app go to the background. False when it could not be written or does not
    /// play to its end; the piece at `to` is then gone.
    static func inBackground(_ kind: RewrapKind, from: URL, to: URL) async -> Bool {
        let task = await MainActor.run { UIApplication.shared.beginBackgroundTask(withName: "Octo download") }
        let done = await Task.detached(priority: .utility) { () -> Bool in
            do {
                let layout = try write(kind, from: from, to: to)
                let got = try decode(to, outputRate: layout.sampleRate)
                // Within one packet of what the container says the song is.
                let ok = Int64(got.frames) >= layout.expectedFrames - 2_048
                if !ok { rewrapLog.error("rewrapped \(to.lastPathComponent, privacy: .public) read short: \(got.frames) of \(layout.expectedFrames)") }
                return ok
            } catch {
                rewrapLog.error("rewrap of \(from.lastPathComponent, privacy: .public) failed: \(String(describing: error), privacy: .public)")
                return false
            }
        }.value
        if !done { try? FileManager.default.removeItem(at: to) }
        if task != .invalid { await MainActor.run { UIApplication.shared.endBackgroundTask(task) } }
        return done
    }

    /// Writes the packets of `from` into `kind`'s container at `to`, and answers the layout written.
    static func write(_ kind: RewrapKind, from: URL, to: URL) throws -> PacketLayout {
        let bytes = [UInt8](try Data(contentsOf: from))
        let layout: PacketLayout
        let type: AudioFileTypeID
        switch kind {
        case .caf:
            guard let stream = OggOpus.read(bytes) else { throw Failure(status: kAudioFileInvalidFileError) }
            layout = CafPlan.from(stream)
            type = kAudioFileCAFType
        case .m4a:
            guard let stream = Adts.read(bytes) else { throw Failure(status: kAudioFileInvalidFileError) }
            layout = M4aPlan.from(stream)
            type = kAudioFileM4AType
        }
        try writePackets(layout, to: to, type: type)
        return layout
    }

    private static func check(_ status: OSStatus) throws {
        if status != noErr { throw Failure(status: status) }
    }

    private static func writePackets(_ layout: PacketLayout, to url: URL, type: AudioFileTypeID) throws {
        try? FileManager.default.removeItem(at: url)
        var description = AudioStreamBasicDescription(
            mSampleRate: layout.sampleRate, mFormatID: layout.formatID, mFormatFlags: layout.formatFlags, mBytesPerPacket: 0,
            mFramesPerPacket: layout.framesPerPacket, mBytesPerFrame: 0, mChannelsPerFrame: layout.channels, mBitsPerChannel: 0,
            mReserved: 0)
        var made: AudioFileID?
        try check(AudioFileCreateWithURL(url as CFURL, type, &description, .eraseFile, &made))
        guard let file = made else { throw Failure(status: kAudioFileUnspecifiedError) }
        var closed = false
        defer { if !closed { AudioFileClose(file) } }
        if !layout.cookie.isEmpty {
            try layout.cookie.withUnsafeBytes { raw in
                try check(AudioFileSetProperty(file, kAudioFilePropertyMagicCookieData, UInt32(raw.count), raw.baseAddress!))
            }
        }
        var next: Int64 = 0
        var index = 0
        while index < layout.packets.count {
            let end = min(index + 512, layout.packets.count)
            var data: [UInt8] = []
            var descriptions: [AudioStreamPacketDescription] = []
            for at in index..<end {
                let packet = layout.packets[at]
                descriptions.append(AudioStreamPacketDescription(
                    mStartOffset: Int64(data.count),
                    mVariableFramesInPacket: layout.framesPerPacket == 0 ? UInt32(layout.packetFrames[at]) : 0,
                    mDataByteSize: UInt32(packet.count)))
                data.append(contentsOf: packet)
            }
            var count = UInt32(end - index)
            try data.withUnsafeBytes { raw in
                try check(AudioFileWritePackets(file, false, UInt32(raw.count), descriptions, next, &count, raw.baseAddress!))
            }
            guard count == UInt32(end - index) else { throw Failure(status: kAudioFileUnspecifiedError) }
            next += Int64(count)
            index = end
        }
        if let valid = layout.validFrames {
            var table = AudioFilePacketTableInfo(mNumberValidFrames: valid, mPrimingFrames: layout.primingFrames,
                                                 mRemainderFrames: layout.remainderFrames)
            try check(AudioFileSetProperty(file, kAudioFilePropertyPacketTableInfo, UInt32(MemoryLayout<AudioFilePacketTableInfo>.size), &table))
        }
        closed = true
        try check(AudioFileClose(file))
    }

    /// Reads a written file to its end through `SongDecoder`, then seeks to its middle and reads
    /// again: the frames it handed out and the first channel's level.
    static func decode(_ url: URL, outputRate: Double) throws -> (frames: Int, rms: Double) {
        let decoder = try SongDecoder(source: try LocalFile(url: url), mimeType: nil, outputRate: outputRate)
        defer { decoder.close() }
        let size = 4_096
        let buffers = (0..<decoder.channels).map { _ in UnsafeMutablePointer<Float>.allocate(capacity: size) }
        defer { buffers.forEach { $0.deallocate() } }
        var frames = 0
        var sum = 0.0
        while true {
            let got = try decoder.read(into: buffers, frames: size)
            if got == 0 { break }
            for at in 0..<got {
                let sample = Double(buffers[0][at])
                sum += sample * sample
            }
            frames += got
        }
        if let ms = decoder.durationMs, ms > 0 {
            try decoder.seek(toMs: ms / 2)
            _ = try decoder.read(into: buffers, frames: size)
        }
        return (frames, frames > 0 ? (sum / Double(frames)).squareRoot() : 0)
    }
}

/// Finds, once per system version, whether this phone plays the Opus files it writes: the bundled
/// one-second tone is put into a CAF with the same code a download is, and decoded through the
/// player's own decoder. Nil when nothing could be found (no room, the tone missing, a disk error):
/// the phone tries again next time.
enum OpusProbe {
    static func run() async -> OpusAbility? {
        await Task.detached(priority: .utility) { probe() }.value
    }

    private static func probe() -> OpusAbility? {
        guard let tone = Bundle.module.url(forResource: "opus-probe", withExtension: "opus", subdirectory: "Probe") else {
            rewrapLog.error("the Opus tone is not in the app")
            return nil
        }
        let folder = FileManager.default.temporaryDirectory
        let free = (try? folder.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?
            .volumeAvailableCapacityForImportantUsage ?? Int64.max
        guard free >= opusProbeFreeBytes else { return nil }
        // A tone the phone cannot read as Ogg Opus says nothing about the system: nothing is stored.
        guard let bytes = try? Data(contentsOf: tone), OggOpus.read([UInt8](bytes)) != nil else {
            rewrapLog.error("the Opus tone in the app does not read as Ogg Opus")
            return nil
        }
        let out = folder.appendingPathComponent("opus-probe-\(UUID().uuidString).caf")
        defer { try? FileManager.default.removeItem(at: out) }
        do {
            _ = try Rewrap.write(.caf, from: tone, to: out)
        } catch let failure as Rewrap.Failure {
            return probeAnswer(forStatus: failure.status)
        } catch {
            return nil
        }
        do {
            let got = try Rewrap.decode(out, outputRate: 48_000)
            let answer = probeAnswer(decodedFrames: got.frames, rms: got.rms)
            rewrapLog.info("Opus tone: \(got.frames) frames, level \(got.rms), \(answer.rawValue, privacy: .public)")
            return answer
        } catch let error as PlayError {
            return probeAnswer(forOpenError: error.code)
        } catch {
            return nil
        }
    }
}
