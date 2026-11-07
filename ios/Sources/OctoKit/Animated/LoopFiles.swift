import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The longest playlist read, and the largest loop file kept. Apple's playlists are a few KB and
/// their loops a few MB.
let loopPlaylistLimit = 512 * 1024
let loopFileLimit: Int64 = 64 * 1024 * 1024
let loopPosterLimit: Int64 = 16 * 1024 * 1024

/// Finds and fetches loop files: the version of a clip that suits a space on screen, as the one
/// file it is kept in. A relayed clip's addresses lead to the server, which may sit behind a
/// proxy or a sign-in page wanting its own headers and cookies: an address of `client`'s server
/// goes through its connection (`ServerExchange`), any other plainly through `session`.
public struct LoopFetcher: Sendable {
    let session: URLSession
    let client: SubsonicClient?

    public init(session: URLSession = .shared, client: SubsonicClient? = nil) {
        self.session = session
        self.client = client
    }

    /// The file of the clip's version that suits a space `width` by `height` pixels in a format
    /// the device plays, or nil when the clip offers none or cannot be read just now.
    public func locate(_ clip: AnimatedClip, width: Int, height: Int, codecs: Set<LoopCodec> = [.avc]) async -> LoopFile? {
        guard let master = URL(string: clip.video), let ladderText = await text(master) else { return nil }
        let ladder = parseLoopLadder(ladderText, playlistUrl: master)
        // A playlist with no versions is itself a version's playlist.
        let variant: URL
        if ladder.isEmpty {
            variant = master
        } else {
            guard let picked = pickLoopVariant(ladder, width: width, height: height, codecs: codecs) else { return nil }
            variant = picked.url
        }
        if variant == master { return parseLoopFile(ladderText, playlistUrl: master) }
        guard let media = await text(variant) else { return nil }
        return parseLoopFile(media, playlistUrl: variant)
    }

    /// Puts the loop's file at `target` unless it is there already: through a ".part" file
    /// beside it, so a half-fetched file is never taken for a whole one. True when the file is
    /// there.
    public func fetch(_ file: LoopFile, to target: URL) async -> Bool {
        await fetch(file.url, to: target, limit: loopFileLimit)
    }

    /// Puts a clip's still at `target` unless it is there already.
    public func fetchPoster(_ clip: AnimatedClip, to target: URL) async -> Bool {
        guard let poster = clip.poster, let url = URL(string: poster) else { return false }
        return await fetch(url, to: target, limit: loopPosterLimit)
    }

    /// One answer for an address, or nil when none came.
    private func get(_ url: URL) async -> (Data, HTTPURLResponse)? {
        let request = URLRequest(url: url, timeoutInterval: 60)
        if let client, client.scope.covers(url) {
            guard let answer = try? await ServerExchange.answer(for: request, client: client) else { return nil }
            return (answer.data, answer.response)
        }
        guard let (data, response) = try? await session.data(for: request), let http = response as? HTTPURLResponse else { return nil }
        return (data, http)
    }

    private func fetch(_ url: URL, to target: URL, limit: Int64) async -> Bool {
        let files = FileManager.default
        if let size = (try? files.attributesOfItem(atPath: target.path)[.size] as? NSNumber)?.int64Value, size > 0 {
            // A web page an older version kept as a loop is let go and fetched again.
            guard Self.storedPage(target) else { return true }
            try? files.removeItem(at: target)
        }
        // No loop file or still is ever a web page, so a sign-in page in front of the server is
        // never kept as one.
        guard let (data, http) = await get(url), (200..<300).contains(http.statusCode),
              !isHtml(http.value(forHTTPHeaderField: "Content-Type"))
        else { return false }
        let length = http.expectedContentLength
        guard !data.isEmpty, Int64(data.count) <= limit, length < 0 || Int64(data.count) == length else { return false }
        try? files.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        let part = URL(fileURLWithPath: target.path + ".part")
        try? files.removeItem(at: part)
        guard (try? data.write(to: part)) != nil else {
            try? files.removeItem(at: part)
            return false
        }
        try? files.removeItem(at: target)
        guard (try? files.moveItem(at: part, to: target)) != nil else {
            try? files.removeItem(at: part)
            return false
        }
        return true
    }

    private func text(_ url: URL) async -> String? {
        guard let (data, http) = await get(url), (200..<300).contains(http.statusCode),
              data.count <= loopPlaylistLimit,
              let body = String(data: data, encoding: .utf8), body.hasPrefix("#EXTM3U")
        else { return nil }
        return body
    }

    /// Whether a kept file starts like a web page.
    static func storedPage(_ file: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: file) else { return false }
        defer { try? handle.close() }
        return looksLikeHtmlFile((try? handle.read(upToCount: 64)) ?? Data())
    }
}

/// Loop files kept on disk under `dir`, named by their address. Used as a cache: `trim` drops the
/// least lately used past `maxBytes`.
public struct LoopShelf: Sendable {
    public let dir: URL
    let maxBytes: Int64

    public init(dir: URL, maxBytes: Int64) {
        self.dir = dir
        self.maxBytes = maxBytes
    }

    public func file(for url: URL) -> URL { dir.appendingPathComponent("\(loopHash(url.absoluteString)).mp4") }

    /// Marks a file as just used, so trimming keeps it longest.
    public func touch(_ file: URL) {
        guard FileManager.default.fileExists(atPath: file.path) else { return }
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: file.path)
    }

    /// Drops the least lately used files until the shelf fits, never one in `keep` (the loops on
    /// screen).
    public func trim(keep: Set<URL> = []) {
        let files = FileManager.default
        let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey, .isRegularFileKey]
        let listed = (try? files.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys)) ?? []
        func stamp(_ url: URL) -> Date { (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast }
        func size(_ url: URL) -> Int64 { Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        let loops = listed.filter { $0.pathExtension == "mp4" }.sorted { stamp($0) < stamp($1) }
        let kept = Set(keep.map(\.standardizedFileURL.path))
        var total = loops.reduce(Int64(0)) { $0 + size($1) }
        for file in loops {
            if total <= maxBytes { break }
            if kept.contains(file.standardizedFileURL.path) { continue }
            let bytes = size(file)
            if (try? files.removeItem(at: file)) != nil { total -= bytes }
        }
        // Parts left by a fetch cut short, an hour on.
        for part in listed where part.pathExtension == "part" && Date().timeIntervalSince(stamp(part)) > 3_600 {
            try? files.removeItem(at: part)
        }
    }
}

/// Where a downloaded album's loops are kept, beside its songs: one file per shape, and the
/// shape's still.
public func offlineLoopFile(dir: URL, albumId: String, shape: String) -> URL {
    dir.appendingPathComponent("\(loopHash(albumId))-\(shape).mp4")
}

public func offlineLoopPoster(dir: URL, albumId: String, shape: String) -> URL {
    dir.appendingPathComponent("\(loopHash(albumId))-\(shape).jpg")
}

/// The first 16 bytes of the text's SHA-256, in hex.
func loopHash(_ text: String) -> String {
    Hashes.hex(Hashes.sha256(Array(text.utf8)).prefix(16))
}
