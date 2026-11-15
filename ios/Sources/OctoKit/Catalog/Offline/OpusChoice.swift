import Foundation

/// What the bundled Opus tone must decode to for the phone to count as playing the Opus files it
/// writes: one second at 48 kHz, its level that of a sine at half of full scale.
public let opusProbeFrames = 48_000
public let opusProbeRms = 0.5 / 2.0.squareRoot()
/// The room the tone's CAF needs before it is tried at all.
public let opusProbeFreeBytes: Int64 = 1_048_576

/// What a finished decode of the tone says: yes when it handed out the tone's frames, within one
/// packet, at the tone's level, within 3 dB; no otherwise.
public func probeAnswer(decodedFrames: Int, rms: Double) -> OpusAbility {
    let framesRight = abs(decodedFrames - opusProbeFrames) <= 960
    let levelRight = rms > 0 && abs(20 * log10(rms / opusProbeRms)) <= 3
    return framesRight && levelRight ? .yes : .no
}

/// What a status from writing or opening the tone's CAF says: no for the system refusing the format
/// or the file it was handed; nothing (try again next time) for the disk, permissions, or anything
/// it does not name.
public func probeAnswer(forStatus status: Int32) -> OpusAbility? {
    let refusals: Set<UInt32> = [fourCharCode("fmt?"), fourCharCode("typ?"), fourCharCode("dta?"), fourCharCode("!dat")]
    return refusals.contains(UInt32(bitPattern: status)) ? .no : nil
}

/// What the player's own error from opening the tone's CAF says: no when it calls the file or its
/// format one it cannot play, nothing otherwise.
public func probeAnswer(forOpenError code: PlayErrorCode) -> OpusAbility? {
    switch code {
    case .decodingFormatUnsupported, .decodingFormatExceedsCapabilities, .parsingContainerUnsupported, .parsingContainerMalformed: .no
    default: nil
    }
}
