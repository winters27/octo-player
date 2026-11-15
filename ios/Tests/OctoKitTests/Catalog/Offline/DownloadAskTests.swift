import Foundation
import Testing
@testable import OctoKit

/// What a download asks the server for, the name it is started under, and the saved copy it may be
/// made from.
@Suite struct DownloadAskTests {
    let flac = StreamRef(sourceId: "s", serverId: "tr-1", mimeType: "audio/flac", bitrate: nil)

    @Test func onlyWhatIsBiggerThanTheCapIsMadeSmaller() {
        #expect(downloadAsk(mimeType: "audio/flac", bitrate: 900_000, quality: .mp3(kbps: 128)) == .smaller(maxKbps: 128))
        #expect(downloadAsk(mimeType: "audio/mpeg", bitrate: 128_000, quality: .mp3(kbps: 192)) == .asItIs)
        #expect(downloadAsk(mimeType: "audio/mp4", bitrate: 320_000, quality: .mp3(kbps: 192)) == .smaller(maxKbps: 192))
        #expect(downloadAsk(mimeType: "audio/flac", bitrate: 900_000, quality: .original) == .asItIs)
    }

    @Test func aFileThePhoneCannotOpenComesSmallerAndAnOggIsLookedAtFirst() {
        #expect(downloadAsk(mimeType: "audio/x-ms-wma", bitrate: 128_000, quality: .original) == .smaller(maxKbps: 320))
        #expect(downloadAsk(mimeType: "audio/x-ms-wma", bitrate: 128_000, quality: .mp3(kbps: 192)) == .smaller(maxKbps: 192))
        #expect(downloadAsk(mimeType: "audio/ogg", bitrate: 128_000, quality: .original) == .lookFirst)
        #expect(downloadAsk(mimeType: "audio/ogg", bitrate: 900_000, quality: .mp3(kbps: 128)) == .smaller(maxKbps: 128))
    }

    @Test func anOggIsDecidedByItsFirstBytes() {
        // Ogg Opus comes as it is on every phone (a CAF, or Octo's own decoder plays it).
        #expect(oggDecision(head: oggHead("OpusHead")) == .asItIs)
        #expect(oggDecision(head: oggHead("\u{01}vorbis")) == .smaller)
        // Not Ogg at all: a family limit had the server send an MP3.
        #expect(oggDecision(head: [0xFF, 0xFB, 0x90, 0x00]) == .asItIs)
    }

    @Test func aDownloadAsksTheFormatAndNoEstimateWhileAStreamIsAsItWas() {
        let opus = StreamRequest(128, format: .opus, estimate: false)
        #expect(opus.params.map { "\($0.0)=\($0.1)" } == ["format=opus", "maxBitRate=128"])
        #expect(StreamRequest(128).params.map { "\($0.0)=\($0.1)" } == ["format=mp3", "maxBitRate=128", "estimateContentLength=true"])
        #expect(StreamRequest(nil, format: .opus, estimate: false).params.map { "\($0.0)=\($0.1)" } == ["format=raw"])
        #expect(opus.mimeType("audio/flac") == "audio/opus")
    }

    @Test func theFormatSurvivesTheAddressAndAStreamAddressIsUnchanged() {
        let pinned = flac.pin(StreamRequest(128, format: .opus, estimate: false))
        #expect(parseStreamUri(streamUri(pinned)) == pinned)
        let stream = flac.pin(StreamRequest(128))
        #expect(!streamUri(stream).contains("fmt="))
        #expect(!streamUri(stream).contains("est="))
        #expect(parseStreamUri(streamUri(stream)) == stream)
    }

    @Test func eachFormatIsSavedApartAndAnMp3KeepsItsName() {
        #expect(streamCacheKey("s", flac, StreamRequest(128)) == "s|tr-1|audio/flac|128")
        #expect(streamCacheKey("s", flac, StreamRequest(128, format: .mp3, estimate: false)) == "s|tr-1|audio/flac|128")
        #expect(streamCacheKey("s", flac, StreamRequest(128, format: .opus, estimate: false)) == "s|tr-1|audio/flac|opus128")
        #expect(streamCacheKey("s", flac, StreamRequest(256, format: .aac, estimate: false)) == "s|tr-1|audio/flac|aac256")
        var sized = flac
        sized.size = 5000
        for request in [StreamRequest(nil), StreamRequest(128), StreamRequest(128, format: .opus, estimate: false),
                        StreamRequest(256, format: .aac, estimate: false)] {
            #expect(cacheKeyWithoutSize(streamCacheKey("s", sized, request)) == streamCacheKey("s", flac, request))
        }
        // A file type is never read as a request.
        #expect(cacheKeyWithoutSize("s|tr-1|audio/flac") == "s|tr-1|audio/flac")
    }

    @Test func anOpusDownloadNeverLooksUnderAnMp3StreamsName() {
        let streamKey = streamCacheKey("s", flac, StreamRequest(128))
        let opusKey = streamCacheKey("s", flac, StreamRequest(128, format: .opus, estimate: false))
        #expect(streamKey != opusKey)
    }

    @Test func aSmallerDownloadsNameCarriesTheMarkInsideItsBracket() {
        let name = downloadFileName(artist: "Drake", title: "Hotline Bling", sourceId: "s", serverId: "tr-1",
                                    format: "audio/opus", madeSmaller: true)
        #expect(name.wholeMatch(of: /Drake - Hotline Bling \[[0-9a-f]{8}~s\]\.opus/) != nil, "\(name)")
        #expect(isMadeSmallerName("/var/Octo/\(name)"))
        let raw = downloadFileName(artist: "Drake", title: "Hotline Bling", sourceId: "s", serverId: "tr-1", format: "audio/flac")
        #expect(!isMadeSmallerName(raw))
        // A title ending in what looks like the mark sits before the bracket, so it never counts.
        let faked = downloadFileName(artist: "A", title: "Song [cafebabe~s]", sourceId: "s", serverId: "tr-1", format: "audio/flac")
        #expect(!isMadeSmallerName(faked))
    }

    @Test func theFinishedNameFollowsTheBytes() {
        let start = "A - B [a1b2c3d4~s].opus"
        #expect(finishedFileName(start, ext: "caf", madeSmaller: true) == "A - B [a1b2c3d4~s].caf")
        #expect(finishedFileName("A - B [a1b2c3d4].flac", ext: "mp3", madeSmaller: true) == "A - B [a1b2c3d4~s].mp3")
        #expect(finishedFileName("A - B [a1b2c3d4].flac", ext: "flac", madeSmaller: false) == "A - B [a1b2c3d4].flac")
    }

    @Test func progressComesFromTheLengthAndTheCap() {
        #expect(expectedDownloadBytes(durationMs: 240_000, maxKbps: 128) == 3_840_000)
        #expect(expectedDownloadBytes(durationMs: 240_000, maxKbps: nil) == 0)
    }

    /// The first bytes of an Ogg page whose one packet starts with `magic`.
    func oggHead(_ magic: String) -> [UInt8] {
        var page = Array("OggS".utf8) + [0, 2] + [UInt8](repeating: 0, count: 20) + [1, 19]
        page += Array(magic.utf8)
        page += [UInt8](repeating: 0, count: 64 - page.count)
        return page
    }
}
