import Foundation
import Network
import OctoKit
import Synchronization

/// Whether the phone has a connection, kept where any thread can read it.
final class OnlineWatch: @unchecked Sendable {
    static let shared = OnlineWatch()

    private let monitor = NWPathMonitor()
    private let isOnline = Atomic<Bool>(true)
    private let isMobile = Atomic<Bool>(false)

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            self?.isOnline.store(online, ordering: .relaxed)
            self?.isMobile.store(online && !path.usesInterfaceType(.wifi) && !path.usesInterfaceType(.wiredEthernet), ordering: .relaxed)
        }
        monitor.start(queue: DispatchQueue(label: "app.winters.octo.playback.network"))
    }

    var online: Bool { isOnline.load(ordering: .relaxed) }
    var onMobileData: Bool { isMobile.load(ordering: .relaxed) }
}

/// Everything opening a song needs from the app, taken on the main actor as the song loads:
/// the server in use, the other kept servers, and the stream size for this connection.
struct StreamContext: Sendable {
    let client: SubsonicClient?
    let sourceId: String?
    /// The source ids of every kept server.
    let keptSources: Set<String>
    let quality: StreamQuality
    /// Songs played are kept on the phone as they stream.
    let keeping: Bool
    /// The sign-in, asked again as each request goes out.
    let session: Session?
    /// When the app started.
    var startedAt = Date.distantPast

    @MainActor
    static func now(_ session: Session) -> StreamContext {
        let inUse = session.inUse
        return StreamContext(
            client: inUse?.client, sourceId: inUse?.sourceId,
            keptSources: Set(session.servers.map(\.sourceId)),
            quality: AppSettings.shared.qualityInUse,
            keeping: AppSettings.shared.offline.cacheSize != .off,
            session: session, startedAt: session.startedAt)
    }

    /// The server in use as a request goes out: the address in use now (the home one while at
    /// home). In the first 3 s after the app starts, the saved sign-in gets until then to come
    /// back; after that, no server means none at once.
    func serverNow() -> (client: SubsonicClient, sourceId: String)? {
        guard let session else { return client.map { ($0, sourceId ?? "") } }
        let deadline = min(Date().addingTimeInterval(3), startedAt.addingTimeInterval(3))
        while true {
            let found = try? blocking { await MainActor.run { session.inUse.map { Unchecked(($0.client, $0.sourceId)) } } }
            if let found = found ?? nil { return found.value }
            if Date() >= deadline { return nil }
            Thread.sleep(forTimeInterval: 0.1)
        }
    }

    /// Whether a song is another kept server's than the one in use: its id would be some other
    /// song here. A song kept under this server's old address, before it was edited, still plays.
    func fromAnotherServer(_ ref: StreamRef) -> Bool {
        guard let source = ref.sourceId else { return false }
        return source != sourceId && keptSources.contains(source)
    }

    /// The song with the request it loads with fixed in its address, by what is saved of it.
    func pinned(_ ref: StreamRef) -> StreamRef {
        if ref.pinned != nil { return ref }
        guard let source = ref.sourceId ?? sourceId else { return ref }
        let quality = quality
        var picked: StreamRequest?
        if keeping { picked = try? blocking { await StreamCache.shared.pick(source, ref, quality).request } }
        var pinnedRef = ref.pin(picked ?? ref.request(quality))
        pinnedRef.sourceId = source
        return pinnedRef
    }

    /// The name a server song is kept under, or nil when no server is known for it or songs are
    /// not kept. A saved copy of a file the server has since swapped is let go on the way, and so
    /// is one that starts like a web page (a sign-in page an older version kept as the song).
    func savedKey(_ ref: StreamRef) -> String? {
        guard keeping, let source = ref.sourceId ?? sourceId else { return nil }
        let request = ref.request(quality)
        return try? blocking {
            let key = await StreamCache.shared.savedKey(source, ref, request)
            await StreamCache.shared.dropPage(key)
            return key
        }
    }

    /// A request for one load of a server song, and the client it goes through. `play` is the
    /// play token of the queue entry's play it is for (the same on every request of that play);
    /// `purpose` is what the request is for when it is no play (a scout's read, a copy fetched
    /// ahead to keep), sent as `X-Octo-Purpose`. Fails at once with no connection, no server, an
    /// unreadable address, or another server's song.
    func streamRequest(_ ref: StreamRef, play: String? = nil, purpose: String? = nil) throws -> (request: URLRequest, client: SubsonicClient) {
        guard OnlineWatch.shared.online else { throw PlayError(.ioUnspecified, detail: "No connection") }
        guard let (client, inUse) = serverNow() else { throw PlayError(.ioUnspecified, detail: "No server signed in") }
        if let source = ref.sourceId, source != inUse, keptSources.contains(source) {
            throw PlayError(.ioUnspecified, detail: "Another server's song")
        }
        var url = client.streamURL(serverId: ref.serverId, request: ref.request(quality))
        if let play, let named = Self.naming(url, play: play) { url = named }
        var request = URLRequest(url: url)
        if let purpose { request.setValue(purpose, forHTTPHeaderField: octoPurposeHeader) }
        return (request, client)
    }

    /// The stream address with the play token added to its query as it is, so the signature's own
    /// parts are never encoded again.
    private static func naming(_ url: URL, play: String) -> URL? {
        guard var parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let token = play.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-._~")))
        else { return nil }
        let pair = "\(PLAY_TOKEN_PARAM)=\(token)"
        parts.percentEncodedQuery = parts.percentEncodedQuery.map { "\($0)&\(pair)" } ?? pair
        return parts.url
    }

    /// A signed request for one load of a server song: the server's headers for its address and
    /// the sign-in page's cookies, with the store its answers keep cookies in. `play` and `purpose`
    /// as `streamRequest` takes them. Fails as `streamRequest` does.
    func sign(_ ref: StreamRef, play: String? = nil, purpose: String? = nil) throws -> SignedStream {
        let (request, client) = try streamRequest(ref, play: play, purpose: purpose)
        return SignedStream(request: client.prepared(request), cookies: client.cookies, scope: client.scope,
                            headers: { client.headers(for: $0) })
    }

    /// Before a song found online opens from the server in use while a copy of it is saved: the
    /// server's list of songs given another version is read (one read at a time, waited for here;
    /// offline, or with nothing saved, nothing is asked), and what it lists has its copies dropped,
    /// so a copy of a file the server no longer sends never plays.
    func beforeOpening(_ ref: StreamRef) {
        guard keeping, OnlineWatch.shared.online, let sourceId, ref.sourceId == nil || ref.sourceId == sourceId else { return }
        let id = ref.serverId
        guard (try? blocking({ await StreamCache.shared.holdsSong(sourceId, id) })) == true else { return }
        _ = try? blocking { await StreamVersionFeed.shared.refresh(opening: id) }
    }
}

/// One request for a song's bytes, with where its answers keep a sign-in page's cookies: the
/// store and scope of the client that signed it, so every answer to it, redirects included,
/// writes into that client's store and no other, and each redirect gets that client's headers
/// for its address (`headers`, its `headers(for:)`). A request to an outside address carries an
/// empty store, no scope and no headers.
struct SignedStream: Sendable {
    let request: URLRequest
    let cookies: ServerCookies
    let scope: ServerScope
    let headers: @Sendable (URL) -> [String: String]

    /// A request to an address outside the server: no server's headers or cookies go with it.
    static func outside(_ request: URLRequest) -> SignedStream {
        SignedStream(request: request, cookies: ServerCookies(), scope: ServerScope(), headers: { _ in [:] })
    }

    /// The same request at another address of the same song (as a player's loader asks for it):
    /// its headers, and the store's cookies for that address as they are now.
    func asking(_ url: URL) -> URLRequest {
        var out = request
        out.url = url
        out.httpShouldHandleCookies = false
        out.setValue(scope.covers(url) ? cookies.cookieHeader(for: url) : nil, forHTTPHeaderField: "Cookie")
        return out
    }
}

/// A song the scout may not read: its server sends it whole from the start every time, so
/// reading its end would mean reading all of it.
struct NotSeekable: Error {}

/// Opens queue items for the decks and the scout: phone files and media library songs as they
/// are, server songs from their saved copy when it is whole, otherwise streamed and signed as
/// each request goes out, and saved as they play.
enum SongOpener {
    /// How a deck opens `item`. `scouting` asks the server first whether it sends parts of a
    /// song, and gives up when it does not; a scout's requests say they are one. Every request of
    /// a queue entry's play carries the play's token (the entry and its restarts). A song found
    /// online with a saved copy has the server's list of changed songs read first.
    static func opener(_ item: QueueItem, context: StreamContext, outputRate: Double,
                       scouting: Bool = false) -> @Sendable (OpenToken) throws -> OpenedSong {
        let uri = item.uri
        let mime = item.mimeType
        let trackId = item.trackId
        let find = item.isFind
        let play = item.entryId.flatMap { entry in parseStreamUri(uri).map { streamPlayToken(entryId: entry, restarts: $0.restarts) } }
        let purpose = scouting ? StreamPlays.scoutPurpose : nil
        return { token in
            if let ref = parseStreamUri(uri) {
                guard OnlineWatch.shared.online || context.keeping else { throw PlayError(.ioUnspecified, detail: "No connection") }
                if find { context.beforeOpening(ref) }
                let pinned = context.pinned(ref)
                let savedKey = context.savedKey(pinned)
                if let key = savedKey,
                   let saved = try? blocking({ await StreamCache.shared.savedFile(key) }),
                   let file = try? LocalFile(url: saved.url, contentType: saved.mimeType) {
                    // A saved copy that fails part way is passed over for the server's.
                    let copy = SavedCopy(file) {
                        try RemoteFile(online: { OnlineWatch.shared.online }) { try context.sign(pinned, play: play, purpose: purpose) }
                    }
                    token.adopt(copy)
                    return OpenedSong(decoder: try SongDecoder(source: copy, mimeType: saved.mimeType ?? mime, outputRate: outputRate), bytes: copy)
                }
                let remote = try RemoteFile(savedKey: savedKey, online: { OnlineWatch.shared.online }) {
                    try context.sign(pinned, play: play, purpose: purpose)
                }
                token.adopt(remote)
                if scouting && !remote.answersRanges() { throw NotSeekable() }
                if token.isCancelled { throw PlayError(.ioUnspecified, detail: "stopped") }
                return OpenedSong(decoder: try SongDecoder(source: remote, mimeType: pinned.request(context.quality).mimeType(pinned.mimeType),
                                                           outputRate: outputRate), bytes: remote)
            }
            // A server song's address that cannot be read names no song to ask for.
            if uri.lowercased().hasPrefix(streamScheme + ":") {
                guard OnlineWatch.shared.online else { throw PlayError(.ioUnspecified, detail: "No connection") }
                guard context.serverNow() != nil else { throw PlayError(.ioUnspecified, detail: "No server signed in") }
                throw PlayError(.ioUnspecified, detail: "No song id")
            }
            guard let url = URL(string: uri) else { throw PlayError(.fileNotFound, detail: "no address") }
            switch url.scheme?.lowercased() {
            case "file":
                let file = try LocalFile(url: url, contentType: mime)
                token.adopt(file)
                // A kept Ogg Opus (a phone whose system cannot play the Opus files it writes keeps
                // them so), and an Opus CAF kept before this system version was found not to play
                // them, play through Octo's own Opus decoder.
                if let opus = try ownOpusDecoder(url, outputRate: outputRate) {
                    return OpenedSong(decoder: opus, bytes: file)
                }
                if let decoder = try? SongDecoder(source: file, mimeType: mime, outputRate: outputRate) {
                    return OpenedSong(decoder: decoder, bytes: file)
                }
                return OpenedSong(decoder: try AssetDecoder(url: url, outputRate: outputRate), bytes: file)
            case "http" where isRadio(trackId), "https" where isRadio(trackId):
                // A live station: read as it comes, with no length and no end; an HLS one by
                // its playlist, any other with its ICY titles.
                guard OnlineWatch.shared.online else { throw PlayError(.ioUnspecified, detail: "No connection") }
                return try openStation(url, hls: looksLikeHls(uri) || isHlsPlaylistType(mime), trackId: trackId, mime: mime,
                                       token: token, outputRate: outputRate)
            case "http", "https":
                let remote = try RemoteFile(online: { OnlineWatch.shared.online }) {
                    guard OnlineWatch.shared.online else { throw PlayError(.ioUnspecified, detail: "No connection") }
                    return .outside(URLRequest(url: url))
                }
                token.adopt(remote)
                return OpenedSong(decoder: try SongDecoder(source: remote, mimeType: mime, outputRate: outputRate), bytes: remote)
            default:
                // A song of the phone's media library.
                return OpenedSong(decoder: try AssetDecoder(url: url, outputRate: outputRate), bytes: nil)
            }
        }
    }

    /// Opens a live station at `url`: as HLS, or as a stream with ICY titles. A stream that
    /// answers with an HLS playlist is read as HLS, and an HLS address that holds a plain playlist
    /// of whole streams plays its first stream, at most `hops` times over. A source whose sound
    /// cannot start is stopped at once.
    private static func openStation(_ url: URL, hls: Bool, trackId: String, mime: String?, token: OpenToken,
                                    outputRate: Double, hops: Int = 3) throws -> OpenedSong {
        if token.isCancelled { throw PlayError(.ioUnspecified, detail: "stopped") }
        if hls {
            let bytes = HlsBytes(url: url)
            token.adopt(bytes)
            do {
                return OpenedSong(decoder: try LiveStreamDecoder(bytes: bytes, mimeType: nil, outputRate: outputRate), bytes: bytes)
            } catch {
                bytes.cancel()
                guard hops > 0, let entry = bytes.plainEntry else { throw error }
                return try openStation(entry, hls: looksLikeHls(entry.absoluteString), trackId: trackId, mime: nil, token: token,
                                       outputRate: outputRate, hops: hops - 1)
            }
        }
        let bytes = LiveBytes(request: URLRequest(url: url)) { title in LiveTitleBoard.post(trackId, title) }
        token.adopt(bytes)
        do {
            return OpenedSong(decoder: try LiveStreamDecoder(bytes: bytes, mimeType: isHlsPlaylistType(mime) ? nil : mime,
                                                             outputRate: outputRate), bytes: bytes)
        } catch {
            bytes.cancel()
            guard hops > 0, let playlist = bytes.playlistAddress else { throw error }
            return try openStation(playlist, hls: true, trackId: trackId, mime: nil, token: token, outputRate: outputRate, hops: hops - 1)
        }
    }
}

/// Octo's own Opus decoder for a kept file that needs it: an Ogg Opus always; an Opus CAF when this
/// system version was found not to play those (`AppSettings.storedOpusAbility`, read off the main
/// thread). None for any other file, which the system's own readers open.
func ownOpusDecoder(_ url: URL, outputRate: Double) throws -> OpusFileDecoder? {
    let head = arrivalHead(url)
    switch sniffDownloadAudio(head) {
    case "audio/opus":
        let bytes = [UInt8](try Data(contentsOf: url, options: .mappedIfSafe))
        guard let stream = OggOpus.read(bytes) else {
            throw PlayError(.parsingContainerMalformed, detail: "an Opus file Octo cannot read")
        }
        playbackLog.info("\(url.lastPathComponent, privacy: .public) opens through OpusFileDecoder")
        return try OpusFileDecoder(stream: stream, fileBytes: bytes.count, outputRate: outputRate)
    case "audio/x-caf" where CafOpus.isOpus(head) && AppSettings.storedOpusAbility() == .no:
        let bytes = [UInt8](try Data(contentsOf: url, options: .mappedIfSafe))
        guard let stream = CafOpus.read(bytes) else { return nil }
        playbackLog.info("\(url.lastPathComponent, privacy: .public) opens through OpusFileDecoder")
        return try OpusFileDecoder(stream: stream, fileBytes: bytes.count, outputRate: outputRate)
    default:
        return nil
    }
}
