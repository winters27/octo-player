import Foundation
import Observation
import OctoKit

/// What a pull on a library page does: rescans the phone and, when a server is signed in, copies
/// its library afresh (a copy already running is waited for instead), and asks how the downloads
/// on their way are going. The pull ends with the rescan: a copy can take minutes and shows its
/// own progress; one that fails says why through `announce` when it ends.
@MainActor
final class LibraryRefresh {
    private let device: DeviceLibrary
    private let sync: ServerSync
    private let downloads: Downloads
    private let session: Session
    private let announce: @MainActor (String) -> Void

    init(device: DeviceLibrary, sync: ServerSync, downloads: Downloads, session: Session, announce: @escaping @MainActor (String) -> Void) {
        self.device = device
        self.sync = sync
        self.downloads = downloads
        self.session = session
        self.announce = announce
    }

    /// Returns once the phone is rescanned. Answers nothing to say: the copy says its own failure.
    func refresh() async -> String? {
        Task { await downloads.refreshProgress() }
        if session.inUse != nil {
            let sync = sync
            let announce = announce
            Task { @MainActor in
                if let problem = await sync.syncNowAndWait(origin: .asked) { announce(problem) }
            }
        }
        await device.rescan()
        return nil
    }
}

/// What song rows show about copies kept on the phone: which songs are downloaded and, with no
/// connection, which server songs cannot play. The saved songs are only looked through once the
/// connection is gone.
@MainActor
@Observable
final class OfflineMarkings {
    private(set) var marks = OfflineMarks()

    @ObservationIgnored private let downloads: OfflineDownloads
    @ObservationIgnored private let connectivity: Connectivity
    @ObservationIgnored private let cache: StreamCache
    @ObservationIgnored private var watch: Watch<String>?

    init(downloads: OfflineDownloads, connectivity: Connectivity, cache: StreamCache = .shared) {
        self.downloads = downloads
        self.connectivity = connectivity
        self.cache = cache
        watch = Watch({ [weak self] in
            guard let self else { return "" }
            return "\(self.connectivity.online)|\(self.downloads.done.count)|\(self.downloads.done.hashValue)"
        }) { [weak self] _ in self?.refresh() }
        refresh()
    }

    private func refresh() {
        let offline = !connectivity.online
        let downloaded = downloads.done
        Task {
            let saved = offline ? await cache.fullySavedSongs() : []
            marks = OfflineMarks(downloaded: downloaded, offline: offline, saved: saved)
        }
    }

    func isDownloaded(_ track: LibraryTrack) -> Bool { marks.isDownloaded(track) }
    func isOutOfReach(_ track: LibraryTrack) -> Bool { marks.isOutOfReach(track) }
}
