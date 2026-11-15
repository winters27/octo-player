import Foundation
import Observation
import OctoKit

/// Server songs kept on the phone as real files, one at a time, in the order asked for: by hand,
/// or kept in step with rules (Liked songs, chosen playlists). The queue lives in the library
/// database, and the files come down through a background transfer the system carries on while
/// the app is away; one cut short waits again at the next start.
@MainActor
@Observable
final class OfflineDownloads {
    /// Every download, by library song.
    private(set) var byTrack: [String: KeptDownload] = [:]
    /// Library songs downloaded and ready to play.
    private(set) var done: Set<String> = []
    /// The downloads page: the server in use's downloads, downloading first.
    private(set) var rows: [DownloadListRow] = []

    /// Whether the server in use lets this account keep offline copies (a family plan may not).
    @ObservationIgnored var offlineCopiesAllowed: (@MainActor () async -> Bool)?

    @ObservationIgnored private let session: Session
    @ObservationIgnored private let library: Library
    @ObservationIgnored let settings: AppSettings
    @ObservationIgnored private let connectivity: Connectivity
    @ObservationIgnored private let announce: @MainActor (String) -> Void
    @ObservationIgnored private let bridge = TransferBridge()
    @ObservationIgnored private lazy var transfer: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: "app.winters.octo.downloads").namingOcto(Session.appVersion)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        // The system carries these transfers on by itself, so they keep no cookies: each request
        // takes the server's headers alone.
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        return URLSession(configuration: configuration, delegate: bridge, delegateQueue: bridge.queue)
    }()
    /// The download running now: its key, its transfer, and where its file goes.
    @ObservationIgnored private var running: Running?
    @ObservationIgnored private var tries: [String: Int] = [:]
    @ObservationIgnored private var lastProgressMs: Int64 = 0
    @ObservationIgnored private var lastWanted: [String: Set<String>]?
    @ObservationIgnored private var settle: Task<Void, Never>?
    @ObservationIgnored private var watches: [AnyObject] = []
    @ObservationIgnored private var started = false
    @ObservationIgnored private let pumping = SerialWork()
    @ObservationIgnored private var loopsFollowed = false

    private struct Running {
        var row: KeptDownload
        var task: URLSessionDownloadTask
        var fileName: String
        var format: String?
        /// The server's size for the song's file as the download started.
        var fileSize: Int64?
        /// The type smaller copies were asked as (MP3 for a transfer from before formats).
        var asked: DownloadFormat
        /// About how many bytes it comes to, for its progress when the server sends no length.
        var expected: Int64
    }

    /// Whether the bundled Opus tone has been tried since the app started.
    @ObservationIgnored private var opusProbeTried = false

    init(session: Session, library: Library, settings: AppSettings = .shared, connectivity: Connectivity, sync: ServerSync,
         announce: @escaping @MainActor (String) -> Void) {
        self.session = session
        self.library = library
        self.settings = settings
        self.connectivity = connectivity
        self.announce = announce
        sync.afterSync("downloads") { [weak self] in await self?.afterSync() }
    }

    /// Where finished downloads are kept: in the app's own space, where the Files app and the
    /// phone's music scan do not look, and not backed up.
    nonisolated static var folder: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        var dir = base.appendingPathComponent(downloadFolderName)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? dir.setResourceValues(values)
        return dir
    }

    /// Where removed files wait out their undo.
    nonisolated static var asideFolder: URL {
        let dir = folder.appendingPathComponent(downloadAsideFolderName)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func start() {
        guard !started else { return }
        started = true
        bridge.owner = self
        // Files set aside by a removal the app closed on before it was final.
        for file in (try? FileManager.default.contentsOfDirectory(at: Self.asideFolder, includingPropertiesForKeys: nil)) ?? [] {
            try? FileManager.default.removeItem(at: file)
        }
        // Pieces of downloads the app closed on before they finished. This runs before the transfer
        // session exists, so no transfer the system hands back has a piece here yet.
        let entries = ((try? FileManager.default.contentsOfDirectory(at: Self.folder, includingPropertiesForKeys: [.isRegularFileKey])) ?? [])
            .map { (name: $0.lastPathComponent, isFile: (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true) }
        for name in leftoverDownloadParts(entries) {
            try? FileManager.default.removeItem(at: Self.folder.appendingPathComponent(name))
        }
        watches.append(Watch({ [weak library] in library?.downloadsVersion ?? 0 }) { [weak self] _ in
            Task { await self?.reload() }
        })
        watches.append(Watch({ [weak self] in self?.session.inUse?.sourceId }) { [weak self] _ in
            Task {
                await self?.reload()
                await self?.pump()
            }
        })
        // Every new connection (an edit, a sign-out, another server) comes with a new cookie
        // store, told apart by its own id.
        watches.append(Watch({ [weak self] in self?.session.client.map { $0.cookies.id } }) { [weak self] _ in
            Task { await self?.connectionReplaced() }
        })
        // Rules follow likes, kept playlists and their songs, once they settle.
        watches.append(Watch({ [weak self] in
            guard let self else { return "" }
            return "\(self.library.likesVersion)|\(self.library.playlistsVersion)|\(self.settings.offline.keepLiked)|\(self.settings.offline.keptPlaylists.sorted())"
        }) { [weak self] _ in self?.rulesChanged() })
        watches.append(Watch({ [weak self] in self?.settings.offline.wifiOnly ?? true }) { [weak self] _ in
            Task { await self?.pump() }
        })
        connectivity.onChange { [weak self] in self?.connectionChanged() }
        Task {
            await adoptRunning()
            await reload()
            rulesChanged()
            await pump()
        }
    }

    // MARK: Asking

    /// Downloads these library songs, the ones that can be: on a server and not on the phone.
    func download(_ trackIds: [String]) {
        Task {
            let store = library.store
            let held = downloadsHeld((try? await store.allDownloads()) ?? [], serverInUse: session.inUse?.sourceId)
            let plan = planManualDownloads(held: held, trackIds: distinct(trackIds))
            for (trackId, reasons) in plan.change {
                for row in (try? await store.downloads(forTrack: trackId)) ?? [] {
                    try? await store.setDownloadReason(row.sourceId, row.serverId, reason: DownloadReasons.join(reasons))
                }
            }
            let refused = (try? await store.queueDownloads(plan.add, now: epochNowMs(), refusedSource: await refusedSource())) ?? 0
            if refused > 0 { announce(offlineCopiesOffWords) }
            await pump()
        }
    }

    func downloadAlbum(_ albumId: String) {
        Task { download(await (try? library.store.albumTrackIds(albumId)) ?? []) }
    }

    func downloadPlaylist(_ playlistId: String) {
        Task { download(await (try? library.store.playlistTrackIds(playlistId)) ?? []) }
    }

    /// Lets go of a download asked for by hand. One a rule also keeps stays. When the download
    /// itself goes, `gone` hears a way to bring it back, as long as its file could be set aside.
    func remove(_ trackId: String, gone: @escaping @MainActor (_ restore: @escaping @MainActor () -> Void) -> Void = { _ in }) {
        Task {
            let store = library.store
            let held = downloadsHeld((try? await store.allDownloads()) ?? [], serverInUse: session.inUse?.sourceId)
            guard let reasons = held[trackId] else { return }
            let left = reasonsAfterManualRemoval(reasons)
            if !left.isEmpty {
                for row in (try? await store.downloads(forTrack: trackId)) ?? [] {
                    try? await store.setDownloadReason(row.sourceId, row.serverId, reason: DownloadReasons.join(left))
                }
                return
            }
            let aside = await setAside(trackId)
            // The set-aside files go for good once the undo has passed.
            Task {
                try? await Task.sleep(for: .milliseconds(downloadUndoKeepMs))
                for item in aside { if let file = item.file { try? FileManager.default.removeItem(at: file) } }
            }
            if canRestore(aside.map { (state: $0.row.state, fileKept: $0.file != nil) }) {
                gone { self.restore(aside) }
            }
        }
    }

    private struct SetAside {
        var row: KeptDownload
        var file: URL?
    }

    /// Removes a song's downloads, moving each finished file aside rather than deleting it.
    private func setAside(_ trackId: String) async -> [SetAside] {
        let store = library.store
        var aside: [SetAside] = []
        for row in (try? await store.downloads(forTrack: trackId)) ?? [] {
            stopIfRunning(row)
            try? await store.deleteDownload(row.sourceId, row.serverId)
            var kept: URL?
            if !row.path.isEmpty, FileManager.default.fileExists(atPath: row.path) {
                let file = URL(fileURLWithPath: row.path)
                let target = Self.asideFolder.appendingPathComponent(downloadAsideName(file.lastPathComponent, stamp: epochNowMs()))
                if (try? FileManager.default.moveItem(at: file, to: target)) != nil { kept = target } else { try? FileManager.default.removeItem(at: file) }
            }
            aside.append(SetAside(row: row, file: kept))
        }
        return aside
    }

    /// Takes back a removal: finished files move back, anything unfinished waits again.
    private func restore(_ aside: [SetAside]) {
        Task {
            let store = library.store
            var rows: [KeptDownload] = []
            for item in aside where (try? await store.download(item.row.sourceId, item.row.serverId)) == nil {
                var back = false
                if item.row.state == .done, let file = item.file {
                    back = (try? FileManager.default.moveItem(at: file, to: URL(fileURLWithPath: item.row.path))) != nil
                }
                rows.append(restoredDownload(item.row, fileBack: back))
            }
            if !rows.isEmpty { try? await store.upsertDownloads(rows) }
            let held = downloadsHeld((try? await store.allDownloads()) ?? [], serverInUse: session.inUse?.sourceId)
            for trackId in distinct(aside.map(\.row.trackId)) {
                guard let reasons = held[trackId] else { continue }
                for row in (try? await store.downloads(forTrack: trackId)) ?? [] {
                    try? await store.setDownloadReason(row.sourceId, row.serverId,
                                                       reason: DownloadReasons.join(reasons.union([DownloadReasons.manual])))
                }
            }
            await pump()
        }
    }

    /// Stops keeping anything: the rules are switched off and every file goes.
    func removeAll() {
        settings.setKeepLiked(false)
        for id in settings.offline.keptPlaylists { settings.setPlaylistKept(id, kept: false) }
        Task {
            for row in (try? await library.store.allDownloads()) ?? [] { await deleteRow(row) }
        }
    }

    /// Tries a failed download again.
    func retry(_ trackId: String) {
        Task {
            let store = library.store
            for row in (try? await store.downloads(forTrack: trackId)) ?? [] where row.state == .failed {
                tries[downloadKey(row.sourceId, row.serverId)] = nil
                try? await store.setDownloadState(row.sourceId, row.serverId, state: .queued, progress: 0)
            }
            await pump()
        }
    }

    /// Deletes the downloads of a server taken off the list, files and all.
    func forgetSource(_ sourceId: String) async {
        for row in (try? await library.store.allDownloads()) ?? [] where row.sourceId == sourceId { await deleteRow(row) }
    }

    func setKeepLiked(_ on: Bool) { settings.setKeepLiked(on) }

    func setPlaylistKept(_ playlistId: String, _ kept: Bool) { settings.setPlaylistKept(playlistId, kept: kept) }

    /// The finished downloads of these library songs whose files are there. One whose file has
    /// gone waits to download again, and so does one whose file is a web page (a sign-in page an
    /// older version kept as the song), its file deleted.
    func playableCopies(_ trackIds: [String]) async -> [String: KeptDownload] {
        let rows = (try? await library.store.doneDownloads(for: distinct(trackIds))) ?? []
        var present: [String: KeptDownload] = [:]
        var gone = false
        for row in rows {
            if !row.path.isEmpty, FileManager.default.fileExists(atPath: row.path), !Self.storedPage(row.path) {
                present[row.trackId] = row
            } else {
                gone = true
                if !row.path.isEmpty { try? FileManager.default.removeItem(atPath: row.path) }
                try? await library.store.setDownloadState(row.sourceId, row.serverId, state: .queued, progress: 0)
            }
        }
        if gone { await pump() }
        return present
    }

    /// After each copy of the server's library: downloads of files the server swapped download
    /// again, downloads follow their songs to new ids, and songs a rule wants that just arrived
    /// download.
    private func afterSync() async {
        await refreshSwapped()
        if let wanted = lastWanted { await applyRules(wanted) }
        await pump()
    }

    /// A download whose server file is now another one (the server swapped it for a better copy,
    /// say, under the same id) downloads again. Until it has, the song streams, so the old file
    /// never plays. Downloads made before the server's size was noted have it noted here.
    private func refreshSwapped() async {
        guard let source = session.inUse?.sourceId else { return }
        let store = library.store
        for file in (try? await store.doneFiles(source)) ?? [] {
            switch checkDownload(file) {
            case .keep:
                break
            case .note:
                if let size = file.serverSize { try? await store.noteDownloadFileSize(file.sourceId, file.serverId, fileSize: size) }
            case .refetch:
                tries[downloadKey(file.sourceId, file.serverId)] = nil
                try? await store.setDownloadState(file.sourceId, file.serverId, state: .queued, progress: 0)
            }
        }
    }

    // MARK: Rules

    private func rulesChanged() {
        settle?.cancel()
        settle = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(downloadRulesSettleMs))
            if Task.isCancelled { return }
            guard let self else { return }
            let prefs = self.settings.offline
            let store = self.library.store
            let liked = prefs.keepLiked ? ((try? await store.likedIds()) ?? []) : []
            let members = prefs.keptPlaylists.isEmpty ? [] : ((try? await store.playlistMembers(Array(prefs.keptPlaylists))) ?? [])
            let wanted = wantedByRules(keepLiked: prefs.keepLiked, liked: liked, playlistSongs: members)
            self.lastWanted = wanted
            await self.applyRules(wanted)
            await self.pump()
        }
    }

    private func applyRules(_ wanted: [String: Set<String>]) async {
        let applied = try? await library.store.applyDownloadRules(wanted: wanted, serverInUse: session.inUse?.sourceId, now: epochNowMs(),
                                                                  refusedSource: await refusedSource())
        for row in applied?.removed ?? [] {
            stopIfRunning(row)
            if !row.path.isEmpty { try? FileManager.default.removeItem(atPath: row.path) }
        }
        if (applied?.refused ?? 0) > 0 { announce(offlineCopiesOffWords) }
    }

    /// The server in use, when its family plan has offline copies off for this account.
    private func refusedSource() async -> String? {
        guard let server = session.inUse, let allowed = offlineCopiesAllowed else { return nil }
        return await allowed() ? nil : server.sourceId
    }

    private func deleteRow(_ row: KeptDownload) async {
        stopIfRunning(row)
        try? await library.store.deleteDownload(row.sourceId, row.serverId)
        if !row.path.isEmpty { try? FileManager.default.removeItem(atPath: row.path) }
    }

    private func stopIfRunning(_ row: KeptDownload) {
        guard let running, running.row.sourceId == row.sourceId, running.row.serverId == row.serverId else { return }
        running.task.cancel()
        self.running = nil
    }

    // MARK: The queue

    private func reload() async {
        let all = (try? await library.store.allDownloads()) ?? []
        byTrack = downloadsByTrack(all)
        let before = done
        done = downloadedTrackIds(all)
        // Downloaded albums keep their animated art; albums let go lose it.
        if done != before || !loopsFollowed {
            loopsFollowed = true
            OfflineLoops.shared.follow(library: library, session: session)
        }
        let source = session.inUse?.sourceId
        rows = ((try? await library.store.downloadRows()) ?? []).filter { $0.sourceId == source }
    }

    private func connectionChanged() {
        if let running, downloadLeftWifi(wifiOnly: settings.offline.wifiOnly, onWifi: connectivity.onWifi) {
            // Leaving Wi-Fi pauses a download that waits for it.
            running.task.cancel()
            self.running = nil
            Task {
                try? await library.store.setDownloadState(running.row.sourceId, running.row.serverId, state: .queued, progress: 0)
            }
        }
        Task { await pump() }
    }

    /// A new connection to the server: the transfer running went out with the old one's headers,
    /// so it stops and waits again, to go with the new one's.
    private func connectionReplaced() async {
        if let running {
            running.task.cancel()
            self.running = nil
            try? await library.store.setDownloadState(running.row.sourceId, running.row.serverId, state: .queued, progress: 0)
        }
        await pump()
    }

    /// Starts the oldest waiting download of the server in use, when one may run now. One look at
    /// a time.
    /// While a failed download waits to be tried again, the queue waits with it.
    @ObservationIgnored private var holdUntil: Date?

    private func pump() async {
        await pumping.run { [weak self] in await self?.pumpOnce() }
    }

    private func pumpOnce() async {
        guard running == nil, holdUntil.map({ Date() >= $0 }) ?? true, let server = session.inUse else { return }
        guard downloadMayRunNow(signedIn: true, wifiOnly: settings.offline.wifiOnly, onWifi: connectivity.onWifi,
                                online: connectivity.online),
              let next = try? await library.store.nextQueuedDownload(server.sourceId), running == nil
        else { return }
        let store = library.store
        guard let copy = try? await store.serverCopy(next.sourceId, next.serverId) else {
            try? await store.setDownloadState(next.sourceId, next.serverId, state: .failed, progress: 0)
            Task { await self.pump() }
            return
        }
        let key = downloadKey(next.sourceId, next.serverId)
        let picked = settings.offline.downloadFormat
        // Whether this phone's system plays the Opus files it writes is found once per system
        // version, before the first Opus download: it decides whether an Opus download is put into
        // a CAF or kept as Ogg Opus for Octo's own decoder. Opus is asked as Opus either way.
        if picked == .opus, settings.opusAbility == nil, !opusProbeTried {
            opusProbeTried = true
            if let answer = await OpusProbe.run() { settings.setOpusAbility(answer) }
        }
        let asked = picked
        let quality = settings.offline.downloadQuality
        let maxKbps: Int?
        switch downloadAsk(mimeType: copy.mimeType, bitrate: downloadBitrate(copy), quality: quality) {
        case .asItIs:
            maxKbps = nil
        case .smaller(let kbps):
            maxKbps = kbps
        case .lookFirst:
            do {
                let head = try await Self.firstBytes(of: next.serverId, client: server.client)
                maxKbps = oggDecision(head: head) == .asItIs ? nil : (quality.kbps ?? 320)
            } catch {
                guard running == nil else { return }
                Task { await self.afterFailure(next, refused: false, error: error) }
                return
            }
        }
        let request = StreamRequest(maxKbps, format: asked, estimate: false)
        let format = downloadFormat(serverMimeType: copy.mimeType, maxKbps: maxKbps, asked: asked)
        let fileName = downloadFileName(artist: copy.artist, title: copy.title, sourceId: next.sourceId, serverId: next.serverId,
                                        format: format, madeSmaller: maxKbps != nil)
        // A song saved whole as it played is copied from the phone rather than fetched again, as
        // long as it was saved from the file the server has now. It arrives like any download.
        let fileSize = copy.sizeBytes.flatMap { $0 > 0 ? $0 : nil }
        let ref = StreamRef(sourceId: next.sourceId, serverId: next.serverId, mimeType: copy.mimeType, bitrate: nil, size: fileSize)
        let savedKey = await StreamCache.shared.savedKey(next.sourceId, ref, request)
        if let saved = await StreamCache.shared.savedFile(savedKey) {
            let part = Self.folder.appendingPathComponent(downloadPartName(fileName))
            try? FileManager.default.removeItem(at: part)
            if (try? FileManager.default.copyItem(at: saved.url, to: part)) != nil {
                let job = Arrived(row: next, part: part, startName: fileName, answeredType: saved.mimeType, listed: copy.mimeType,
                                  askedSmaller: maxKbps != nil, asked: asked, fileSize: fileSize)
                switch await arrive(job) {
                case .finished, .removed:
                    Task { await self.pump() }
                    return
                case .failed:
                    Task { await self.afterFailure(next, refused: false, error: nil) }
                    return
                }
            }
            try? FileManager.default.removeItem(at: part)
        }
        var params = [("id", next.serverId)]
        params += request.params
        let client = server.client
        // The server is told this is an offline copy, so an Octo server does not count it as a play.
        let purpose = [octoPurposeHeader: octoPurposeOffline]
        var asked_ = URLRequest(url: client.url("stream", params))
        for (name, value) in purpose { asked_.setValue(value, forHTTPHeaderField: name) }
        asked_.allowsCellularAccess = !settings.offline.wifiOnly
        // The system follows a background transfer's redirects by itself, with none of the
        // server's rules, so they are followed here first and the transfer asks where they end.
        let landed: URL
        do {
            landed = try await ServerExchange.finalAddress(of: asked_, client: client)
        } catch {
            guard running == nil else { return }
            Task { await self.afterFailure(next, refused: false, error: error) }
            return
        }
        // Another connection came in meanwhile: the song waits for the pump that follows it.
        guard running == nil, session.client?.cookies.id == client.cookies.id else {
            Task { await self.pump() }
            return
        }
        var transferRequest = backgroundDownloadRequest(landed, client: client, serverOnly: purpose)
        transferRequest.allowsCellularAccess = !settings.offline.wifiOnly
        let task = transfer.downloadTask(with: transferRequest)
        task.taskDescription = [key, fileName, format ?? "", fileSize.map(String.init) ?? ""]
            .joined(separator: "\n")
        running = Running(row: next, task: task, fileName: fileName, format: format, fileSize: fileSize, asked: asked,
                          expected: expectedDownloadBytes(durationMs: copy.durationMs, maxKbps: maxKbps))
        lastProgressMs = 0
        try? await store.setDownloadState(next.sourceId, next.serverId, state: .downloading, progress: 0)
        task.resume()
    }

    /// A song downloaded again under a new name (its file's type changed, say) lets its old file go.
    nonisolated private static func dropOldFile(_ row: KeptDownload, now target: URL) {
        guard !row.path.isEmpty, row.path != target.path else { return }
        try? FileManager.default.removeItem(atPath: row.path)
    }

    /// A listed Ogg file's first 64 bytes, marked as an offline copy so an Octo server counts no play.
    private static func firstBytes(of serverId: String, client: SubsonicClient) async throws -> [UInt8] {
        var asked = URLRequest(url: client.url("stream", [("id", serverId), ("format", "raw")]))
        asked.setValue("bytes=0-63", forHTTPHeaderField: "Range")
        asked.setValue(octoPurposeOffline, forHTTPHeaderField: octoPurposeHeader)
        let (data, _) = try await ServerExchange.media(for: asked, client: client, endpoint: "stream")
        return [UInt8](data.prefix(64))
    }

    /// A download whose bytes are on the phone, as `part`.
    private struct Arrived {
        var row: KeptDownload
        var part: URL
        var startName: String
        var answeredType: String?
        var listed: String?
        var askedSmaller: Bool
        var asked: DownloadFormat
        var fileSize: Int64?
    }

    /// Turns an arrived part into a finished download through OctoKit's `finishArrival` (named and
    /// labeled by its bytes, put into a CAF or an M4A off the main actor, the row looked at again
    /// before the rename), then writes the finished row. `ArriveOutcome` is OctoKit's.
    private func arrive(_ job: Arrived) async -> ArriveOutcome {
        let store = library.store
        let row = job.row
        let outcome = await finishArrival(
            ArrivalJob(part: job.part, folder: Self.folder, startName: job.startName, answeredType: job.answeredType,
                       listed: job.listed, askedSmaller: job.askedSmaller, asked: job.asked,
                       opusInCaf: settings.opusAbility == .yes),
            stillWanted: { (try? await store.download(row.sourceId, row.serverId)) != nil },
            readsAsOgg: { url in
                OggOpus.read([UInt8]((try? Data(contentsOf: url, options: .mappedIfSafe)) ?? Data())).flatMap(OggOpusPlayer.init) != nil
            },
            rewrap:{ kind, from, to in await Rewrap.inBackground(kind, from: from, to: to) })
        if case .finished(let target, let label) = outcome {
            let size = (try? FileManager.default.attributesOfItem(atPath: target.path)[.size] as? NSNumber)?.int64Value ?? 0
            try? await store.finishDownload(row.sourceId, row.serverId, path: target.path, size: size, format: label, fileSize: job.fileSize)
            Self.dropOldFile(row, now: target)
        }
        return outcome
    }

    /// Whether a kept file starts like a web page.
    nonisolated private static func storedPage(_ path: String) -> Bool {
        guard let handle = FileHandle(forReadingAtPath: path) else { return false }
        defer { try? handle.close() }
        return looksLikeHtmlFile((try? handle.read(upToCount: 64)) ?? Data())
    }

    /// A transfer still running from before the app last closed carries on; downloads marked as
    /// running with no transfer wait in line again.
    private func adoptRunning() async {
        let tasks = await transfer.allTasks.compactMap { $0 as? URLSessionDownloadTask }
        let store = library.store
        var live = Set<String>()
        for task in tasks {
            // The key, the file name, the format and (from transfers started since sizes were
            // noted) the server's size for the file.
            let parts = (task.taskDescription ?? "").components(separatedBy: "\n")
            guard parts.count == 3 || parts.count == 4, running == nil,
                  let row = ((try? await store.allDownloads()) ?? []).first(where: { downloadKey($0.sourceId, $0.serverId) == parts[0] })
            else {
                task.cancel()
                continue
            }
            let format = parts[2].isEmpty ? nil : parts[2]
            running = Running(row: row, task: task, fileName: parts[1], format: format,
                              fileSize: parts.count == 4 ? Int64(parts[3]) : nil,
                              asked: DownloadFormat.allCases.first { $0.mimeType == format } ?? .mp3, expected: 0)
            live.insert(parts[0])
        }
        for row in (try? await store.allDownloads()) ?? [] where row.state == .downloading && !live.contains(downloadKey(row.sourceId, row.serverId)) {
            try? await store.setDownloadState(row.sourceId, row.serverId, state: .queued, progress: 0)
        }
    }

    // MARK: From the transfer

    fileprivate func progressed(_ task: URLSessionTask, written: Int64, expected: Int64) {
        guard let running, running.task === task else { return }
        let now = epochNowMs()
        let length = expected > 0 ? expected : running.expected
        guard downloadProgressDue(nowMs: now, lastMs: lastProgressMs), let progress = downloadProgress(written: written, length: length) else {
            return
        }
        lastProgressMs = now
        Task { try? await library.store.setDownloadState(running.row.sourceId, running.row.serverId, state: .downloading, progress: progress) }
    }

    fileprivate func finished(_ task: URLSessionTask, part: URL?, answeredType: String?, refused: Bool, error: Error?) {
        guard let running, running.task === task else {
            if let part { try? FileManager.default.removeItem(at: part) }
            return
        }
        self.running = nil
        let row = running.row
        let key = downloadKey(row.sourceId, row.serverId)
        Task {
            let store = library.store
            // A web page where the song was asked for is a sign-in page in front of the server:
            // never kept, and counted as the server saying no.
            let turnedAway = refused || part.map { Self.storedPage($0.path) } == true
            if let part, error == nil, !turnedAway {
                let listed = (try? await store.serverCopy(row.sourceId, row.serverId))?.mimeType
                let job = Arrived(row: row, part: part, startName: running.fileName, answeredType: answeredType, listed: listed,
                                  askedSmaller: isMadeSmallerName(running.fileName), asked: running.asked, fileSize: running.fileSize)
                switch await arrive(job) {
                case .finished:
                    tries[key] = nil
                    await pump()
                    return
                case .removed:
                    await pump()
                    return
                case .failed:
                    break
                }
            } else if let part {
                try? FileManager.default.removeItem(at: part)
            }
            await afterFailure(row, refused: turnedAway, error: error)
        }
    }

    /// A download that did not finish (its transfer, or the redirects followed before it): tried
    /// again, waiting for a connection, or failed, by `downloadAfterFailure`. A canceled one is
    /// left as it is.
    private func afterFailure(_ row: KeptDownload, refused: Bool, error: Error?) async {
        if let url = error as? URLError, url.code == .cancelled { return }
        let store = library.store
        let key = downloadKey(row.sourceId, row.serverId)
        let tried = (tries[key] ?? 0) + 1
        tries[key] = tried
        // Only the phone being offline (or off the Wi-Fi it waits for) counts as no connection;
        // a connection that broke off while online is tried again like any other error.
        let noConnection = !connectivity.online || (error as? URLError)?.code == .notConnectedToInternet
            || downloadLeftWifi(wifiOnly: settings.offline.wifiOnly, onWifi: connectivity.onWifi)
        switch downloadAfterFailure(noConnection: noConnection, serverRefused: refused, tried: tried) {
        case .waitForConnection:
            tries[key] = nil
            try? await store.setDownloadState(row.sourceId, row.serverId, state: .queued, progress: 0)
        case .fail:
            try? await store.setDownloadState(row.sourceId, row.serverId, state: .failed, progress: 0)
        case .retry(let afterMs):
            try? await store.setDownloadState(row.sourceId, row.serverId, state: .queued, progress: 0)
            holdUntil = Date().addingTimeInterval(Double(afterMs) / 1000)
            try? await Task.sleep(for: .milliseconds(afterMs))
            holdUntil = nil
        }
        await pump()
    }
}

/// What a family member with offline copies off is told.
private let offlineCopiesOffWords = "Offline copies are off for this account"

/// The header that tells an Octo server why a song is asked for, and the word for a download.
let octoPurposeHeader = "X-Octo-Purpose"
let octoPurposeOffline = "offline"

/// The background transfer's delegate. The system hands the finished file over only for the
/// length of the call, so it is moved aside here before the owner hears of it.
final class TransferBridge: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    let queue: OperationQueue = {
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        return queue
    }()
    weak var owner: OfflineDownloads?
    /// Finished files moved aside, their answers' types, and refusals, by transfer, until the transfer
    /// completes.
    private var parts: [Int: URL] = [:]
    private var types: [Int: String] = [:]
    private var refused = Set<Int>()

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        Task { @MainActor [weak owner] in
            owner?.progressed(downloadTask, written: totalBytesWritten, expected: totalBytesExpectedToWrite)
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // An error, or a web page where a song was asked for (a sign-in page in front of the
        // server), is the server saying no: nothing is kept.
        if let http = downloadTask.response as? HTTPURLResponse,
           !(200..<300).contains(http.statusCode) || isHtml(http.value(forHTTPHeaderField: "Content-Type")) {
            refused.insert(downloadTask.taskIdentifier)
            return
        }
        let name = (downloadTask.taskDescription ?? "").components(separatedBy: "\n").dropFirst().first ?? UUID().uuidString
        let part = OfflineDownloads.folder.appendingPathComponent(downloadPartName(name))
        try? FileManager.default.removeItem(at: part)
        if (try? FileManager.default.moveItem(at: location, to: part)) != nil { parts[downloadTask.taskIdentifier] = part }
        if let type = (downloadTask.response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Type") {
            types[downloadTask.taskIdentifier] = type
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest) async -> URLRequest? {
        ServerTrust.shared.redirected(request, first: task.originalRequest)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        ServerTrust.shared.noteEnd(of: task.originalRequest, error)
        let part = parts.removeValue(forKey: task.taskIdentifier)
        let type = types.removeValue(forKey: task.taskIdentifier)
        let refusedNow = refused.remove(task.taskIdentifier) != nil
        Task { @MainActor [weak owner] in
            if let owner {
                owner.finished(task, part: part, answeredType: type, refused: refusedNow, error: error)
            } else if let part {
                try? FileManager.default.removeItem(at: part)
            }
        }
    }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge)
        async -> (URLSession.AuthChallengeDisposition, URLCredential?) {
        ServerTrust.shared.answer(challenge)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge)
        async -> (URLSession.AuthChallengeDisposition, URLCredential?) {
        ServerTrust.shared.answer(challenge)
    }
}
