import Foundation

/// The container a download's Opus or AAC is put into when it arrives: Opus in a CAF (the only way
/// the phone plays Opus), AAC from ADTS in an M4A (which knows its own exact length).
public enum RewrapKind: Sendable, Equatable {
    case caf
    case m4a

    public var ext: String {
        switch self {
        case .caf: "caf"
        case .m4a: "m4a"
        }
    }

    /// What the download is labeled once in that container.
    public var label: String {
        switch self {
        case .caf: "audio/opus"
        case .m4a: "audio/mp4"
        }
    }
}

/// What a download that has arrived is, by its bytes.
public struct DownloadArrival: Sendable, Equatable {
    /// The type it is labeled as once finished.
    public var label: String?
    /// Whether it is smaller than the server's file (its name gets the smaller mark).
    public var madeSmaller: Bool
    /// The container it is put into first, if any.
    public var rewrap: RewrapKind?
    /// False when the phone cannot play it at all (Ogg Vorbis, WebM): the try fails.
    public var playable: Bool

    public init(label: String?, madeSmaller: Bool, rewrap: RewrapKind? = nil, playable: Bool = true) {
        self.label = label
        self.madeSmaller = madeSmaller
        self.rewrap = rewrap
        self.playable = playable
    }

    /// The ending its file is finished under.
    public var ext: String { rewrap?.ext ?? downloadExtension(for: label) }
}

/// Decides what an arrived download is. A file asked for as it is keeps the type its listing names
/// when its bytes are that kind (as every download did), and is a smaller copy when they are another
/// kind (a family limit had the server make one). A file asked for smaller is named by its bytes, else
/// by what the server said, else by what was asked. Ogg Opus is put into a CAF when this phone's
/// system plays those (`opusInCaf`), else kept as it is for Octo's own Opus decoder; any ADTS AAC is
/// put into an M4A (a file as it is stays unmarked); Ogg Vorbis and WebM cannot play here.
public func downloadArrival(head: [UInt8], answeredType: String?, listed: String?, askedSmaller: Bool,
                            asked: DownloadFormat, opusInCaf: Bool = true) -> DownloadArrival {
    let sniffed = sniffDownloadAudio(head)
    let answered = answeredType.map { trimmed($0.lowercased().before(";")) }.flatMap { $0.isEmpty || $0 == "application/octet-stream" ? nil : $0 }
    let sameAsListed = sniffed == nil || sameAudioKind(listed, sniffed)
    let madeSmaller = askedSmaller || !sameAsListed
    let label = madeSmaller ? (sniffed ?? answered ?? asked.mimeType) : (sniffed == "audio/opus" ? sniffed : (listed ?? sniffed ?? answered))
    switch sniffed {
    case "audio/opus":
        return DownloadArrival(label: RewrapKind.caf.label, madeSmaller: madeSmaller, rewrap: opusInCaf ? .caf : nil)
    case "audio/ogg", "audio/webm":
        return DownloadArrival(label: label, madeSmaller: madeSmaller, playable: false)
    case "audio/aac":
        return DownloadArrival(label: RewrapKind.m4a.label, madeSmaller: madeSmaller, rewrap: .m4a)
    default:
        return DownloadArrival(label: label, madeSmaller: madeSmaller)
    }
}

/// A download whose bytes are in `part`, to be finished in `folder` under a name made from
/// `startName`.
public struct ArrivalJob: Sendable {
    public var part: URL
    public var folder: URL
    public var startName: String
    public var answeredType: String?
    public var listed: String?
    public var askedSmaller: Bool
    public var asked: DownloadFormat
    /// Whether Ogg Opus is put into a CAF (the phone's system plays those) or kept as it is.
    public var opusInCaf: Bool

    public init(part: URL, folder: URL, startName: String, answeredType: String?, listed: String?, askedSmaller: Bool,
                asked: DownloadFormat, opusInCaf: Bool = true) {
        self.part = part
        self.folder = folder
        self.startName = startName
        self.answeredType = answeredType
        self.listed = listed
        self.askedSmaller = askedSmaller
        self.asked = asked
        self.opusInCaf = opusInCaf
    }
}

/// How an arrived download ended.
public enum ArriveOutcome: Sendable, Equatable {
    /// Its file is `target`, labeled `label`: the row can be finished.
    case finished(target: URL, label: String?)
    /// The download was removed while it arrived: nothing is kept.
    case removed
    /// It cannot be kept: `rewrap` when its container could not be written (an ADTS that would not go
    /// into an M4A). An Opus that will not go into a CAF is kept as Ogg Opus instead, never failed.
    case failed(rewrap: Bool)
}

/// The first bytes of a file, enough to pass an ID3 tag with a cover in it.
public func arrivalHead(_ url: URL) -> [UInt8] {
    guard let handle = try? FileHandle(forReadingFrom: url) else { return [] }
    defer { try? handle.close() }
    return [UInt8]((try? handle.read(upToCount: 65_536)) ?? Data())
}

/// Turns an arrived part into a finished file, in this order: the row is looked at
/// (`stillWanted`); the bytes decide the name, the label and the container; the part is put into
/// that container (`rewrap`, from the part to a `.part` of the final name); the row is looked at
/// again, so a download removed meanwhile leaves no file; then the file takes its final name. A
/// part that cannot be put into its container fails the try (an ADTS that will not go into an M4A
/// is never kept as it is). Every piece goes, whatever happens.
///
/// An Ogg Opus kept as it is (no CAF) is kept only when `readsAsOgg` reads it through Octo's own
/// decoder; one that will not read fails the try, so bad bytes are fetched again, never kept as Done.
public func finishArrival(_ job: ArrivalJob, stillWanted: @Sendable () async -> Bool,
                          readsAsOgg: @Sendable (URL) -> Bool = { _ in true },
                          rewrap: @Sendable (RewrapKind, URL, URL) async -> Bool) async -> ArriveOutcome {
    let files = FileManager.default
    guard await stillWanted() else {
        try? files.removeItem(at: job.part)
        return .removed
    }
    var arrival = downloadArrival(head: arrivalHead(job.part), answeredType: job.answeredType, listed: job.listed,
                                  askedSmaller: job.askedSmaller, asked: job.asked, opusInCaf: job.opusInCaf)
    guard arrival.playable else {
        try? files.removeItem(at: job.part)
        return .failed(rewrap: false)
    }
    var finalName = finishedFileName(job.startName, ext: arrival.ext, madeSmaller: arrival.madeSmaller)
    var ready = job.part
    var made: URL?
    if let kind = arrival.rewrap {
        let out = job.folder.appendingPathComponent(downloadPartName(finalName))
        if await rewrap(kind, job.part, out) {
            made = out
            ready = out
        } else if kind == .caf && readsAsOgg(job.part) {
            // An Opus that will not go into a CAF stays the Ogg Opus that arrived, which Octo's own
            // Opus decoder plays: the download is kept, in the format picked.
            try? files.removeItem(at: out)
            arrival.rewrap = nil
            finalName = finishedFileName(job.startName, ext: arrival.ext, madeSmaller: arrival.madeSmaller)
        } else {
            try? files.removeItem(at: out)
            try? files.removeItem(at: job.part)
            return .failed(rewrap: true)
        }
    } else if arrival.label == RewrapKind.caf.label && !readsAsOgg(job.part) {
        // An Ogg Opus kept as it is that Octo's own decoder cannot read (a cut page): never Done.
        try? files.removeItem(at: job.part)
        return .failed(rewrap: true)
    }
    defer {
        if let made { try? files.removeItem(at: made) }
        try? files.removeItem(at: job.part)
    }
    guard await stillWanted() else { return .removed }
    let target = job.folder.appendingPathComponent(finalName)
    try? files.removeItem(at: target)
    guard (try? files.moveItem(at: ready, to: target)) != nil else { return .failed(rewrap: false) }
    return .finished(target: target, label: arrival.label)
}
