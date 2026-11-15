import Foundation

/// The type of audio a file holds, from its first bytes: what a download is named and labeled by,
/// whatever the server said or was asked. An ID3 tag at the start is passed over first. Nil when the
/// bytes name no type this knows, or when a tag at the start is longer than the bytes given.
public func sniffDownloadAudio(_ head: [UInt8]) -> String? {
    let n = head.count
    func at(_ i: Int) -> Int { i >= 0 && i < n ? Int(head[i]) : -1 }
    func text(_ i: Int, _ word: String) -> Bool {
        let bytes = Array(word.utf8)
        return i + bytes.count <= n && bytes.indices.allSatisfy { at(i + $0) == Int(bytes[$0]) }
    }
    var start = 0
    if text(0, "ID3"), n >= 10 {
        let size = ((at(6) & 0x7F) << 21) | ((at(7) & 0x7F) << 14) | ((at(8) & 0x7F) << 7) | (at(9) & 0x7F)
        let footer = at(5) & 0x10 != 0 ? 10 : 0
        start = 10 + size + footer
        if start + 4 > n { return nil }
    }
    if text(start, "fLaC") { return "audio/flac" }
    if text(start, "OggS") { return oggType(head, start) }
    if text(start, "RIFF"), text(start + 8, "WAVE") { return "audio/wav" }
    if text(start + 4, "ftyp") { return "audio/mp4" }
    if at(start) == 0x1A, at(start + 1) == 0x45, at(start + 2) == 0xDF, at(start + 3) == 0xA3 { return "audio/webm" }
    if text(start, "caff") { return "audio/x-caf" }
    if at(start) == 0xFF, at(start + 1) >= 0, at(start + 1) & 0xE0 == 0xE0 { return frameType(at(start + 1), at(start + 2)) }
    return nil
}

/// An Ogg stream's type from its first page's first packet: Opus when it is an OpusHead, Ogg
/// otherwise (Vorbis, or a codec only the bytes would say).
private func oggType(_ head: [UInt8], _ page: Int) -> String {
    let segmentsAt = page + 26
    guard segmentsAt < head.count else { return "audio/ogg" }
    let packet = page + 27 + Int(head[segmentsAt])
    let opus = Array("OpusHead".utf8)
    let isOpus = packet + opus.count <= head.count && Array(head[packet..<packet + opus.count]) == opus
    return isOpus ? "audio/opus" : "audio/ogg"
}

/// An MPEG audio sync word's type: ADTS AAC when its layer bits are 0, an MP3 or other MPEG layer
/// when they are not, and only when its version, bitrate and sample rate fields are ones a real frame
/// has. Nil otherwise.
private func frameType(_ second: Int, _ third: Int) -> String? {
    guard third >= 0 else { return nil }
    let layer = (second >> 1) & 0x3
    if layer == 0 { return second & 0xF6 == 0xF0 ? "audio/aac" : nil }
    let version = (second >> 3) & 0x3
    let bitrate = (third >> 4) & 0xF
    let rate = (third >> 2) & 0x3
    return version != 1 && bitrate != 0xF && rate != 3 ? "audio/mpeg" : nil
}

/// Whether two types are the same kind of file, parameters aside: the one a listing names and the one
/// the bytes say. Ogg and Opus are one kind (Ogg is the container Opus files come in), as are the
/// names of MP4 and of WAV. A type that says nothing (none, or octet-stream) matches anything.
public func sameAudioKind(_ a: String?, _ b: String?) -> Bool {
    guard let x = audioKind(a), let y = audioKind(b) else { return true }
    return x == y
}

private func audioKind(_ type: String?) -> String? {
    guard let raw = type?.lowercased() else { return nil }
    let plain = trimmed(raw.before(";"))
    switch plain {
    case "", "application/octet-stream": return nil
    case "audio/mpeg", "audio/mp3": return "mp3"
    case "audio/flac", "audio/x-flac": return "flac"
    case "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/alac", "audio/mp4a-latm": return "mp4"
    case "audio/aac", "audio/aacp", "audio/x-aac": return "aac"
    case "audio/ogg", "audio/opus", "audio/vorbis", "application/ogg": return "ogg"
    case "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave": return "wav"
    case "audio/webm": return "webm"
    case "audio/x-caf": return "caf"
    default: return plain
    }
}
