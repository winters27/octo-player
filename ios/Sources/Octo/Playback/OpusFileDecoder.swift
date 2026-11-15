import AVFoundation
import AudioToolbox
import Foundation
import OctoKit

/// Plays a kept Opus file (an Ogg Opus, or an Opus CAF on a system that cannot play it) through
/// Octo's own Opus decoder (the bundled libopus, `OggOpusPlayer`). The song decodes at 48 kHz and
/// the system's converter hands it out at the output's rate, one buffer a channel, as every other
/// song decoder does.
final class OpusFileDecoder: SongDecoding {
    let fileSampleRate = Double(OggOpusPlayer.rate)
    let channels: Int
    let durationMs: Int?
    let codecMime: String? = "audio/opus"
    let bitDepth: Int? = nil
    let bitrate: Int?

    private let player: OggOpusPlayer
    private var converter: AudioConverterRef?
    private let list: UnsafeMutableAudioBufferListPointer
    /// The decoded 48 kHz frames handed to the converter, interleaved.
    private let feed: UnsafeMutablePointer<Float>
    private var ended = false

    /// How many frames are decoded for the converter at a time.
    private static let feedFrames = 4_096

    /// The song in `stream`, from a file of `fileBytes` bytes.
    init(stream: OggOpusStream, fileBytes: Int, outputRate: Double) throws {
        guard let player = OggOpusPlayer(stream: stream) else {
            throw PlayError(.parsingContainerMalformed, detail: "an Opus file Octo cannot read")
        }
        self.player = player
        channels = player.channels
        let ms = player.totalFrames * 1000 / Int64(OggOpusPlayer.rate)
        durationMs = ms > 0 ? Int(ms) : nil
        bitrate = ms > 0 ? Int(Int64(fileBytes) * 8 * 1000 / ms) : nil
        feed = UnsafeMutablePointer<Float>.allocate(capacity: Self.feedFrames * player.channels)
        list = AudioBufferList.allocate(maximumBuffers: 2)
        let rate = outputRate > 0 ? outputRate : fileSampleRate
        var source = AudioStreamBasicDescription(
            mSampleRate: fileSampleRate, mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked,
            mBytesPerPacket: UInt32(4 * player.channels), mFramesPerPacket: 1, mBytesPerFrame: UInt32(4 * player.channels),
            mChannelsPerFrame: UInt32(player.channels), mBitsPerChannel: 32, mReserved: 0)
        var client = AudioStreamBasicDescription(
            mSampleRate: rate, mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked | kAudioFormatFlagIsNonInterleaved,
            mBytesPerPacket: 4, mFramesPerPacket: 1, mBytesPerFrame: 4, mChannelsPerFrame: UInt32(player.channels),
            mBitsPerChannel: 32, mReserved: 0)
        var made: AudioConverterRef?
        let status = AudioConverterNew(&source, &client, &made)
        // Every stored property is set by now, so `deinit` runs after a throw and frees `feed` and
        // `list`: nothing is freed here.
        guard status == noErr, let made else {
            throw PlayError(.decodingFormatUnsupported, detail: "converter \(status)")
        }
        converter = made
    }

    deinit {
        close()
        free(list.unsafeMutablePointer)
        feed.deallocate()
    }

    func close() {
        if let converter { AudioConverterDispose(converter) }
        converter = nil
    }

    func seek(toMs ms: Int) throws {
        player.seek(toFrame: Int64(max(0, ms)) * Int64(OggOpusPlayer.rate) / 1000)
        ended = false
        if let converter { AudioConverterReset(converter) }
    }

    func read(into buffers: [UnsafeMutablePointer<Float>], frames: Int) throws -> Int {
        guard let converter, frames > 0 else { return 0 }
        for ch in 0..<channels {
            list[ch] = AudioBuffer(mNumberChannels: 1, mDataByteSize: UInt32(frames * 4), mData: UnsafeMutableRawPointer(buffers[ch]))
        }
        list.unsafeMutablePointer.pointee.mNumberBuffers = UInt32(channels)
        var count = UInt32(frames)
        let input: AudioConverterComplexInputDataProc = { _, packets, data, _, client in
            guard let client else { return -1 }
            return Unmanaged<OpusFileDecoder>.fromOpaque(client).takeUnretainedValue().supply(packets, data)
        }
        let status = AudioConverterFillComplexBuffer(converter, input, Unmanaged.passUnretained(self).toOpaque(), &count,
                                                     list.unsafeMutablePointer, nil)
        if count > 0 { return Int(count) }
        if status != noErr && !ended { throw PlayError(.decodingFailed, detail: "decode \(status)") }
        return 0
    }

    /// Hands the converter the next decoded frames; none at the song's end.
    private func supply(_ packets: UnsafeMutablePointer<UInt32>, _ data: UnsafeMutablePointer<AudioBufferList>) -> OSStatus {
        let got = player.read(into: feed, frames: Self.feedFrames)
        if got == 0 {
            ended = true
            packets.pointee = 0
            return noErr
        }
        data.pointee.mNumberBuffers = 1
        data.pointee.mBuffers = AudioBuffer(mNumberChannels: UInt32(channels), mDataByteSize: UInt32(got * channels * 4),
                                            mData: UnsafeMutableRawPointer(feed))
        packets.pointee = UInt32(got)
        return noErr
    }
}
