import OctoKit
import SwiftUI

// Playlist folders on the phone: the rows of the Playlists page and a folder's page, the folder
// page itself, the Library upkeep group, and the Move to Folder and Add Playlists sheets. Words
// come from OctoKit's PlaylistFolderWords, the same on every app.

/// What the Move to Folder sheet moves: playlists by their placement keys, or one folder.
struct FolderMove: Identifiable {
    let id = UUID()
    var playlistKeys: [String] = []
    var folderId: String?
}

/// The folder the Add Playlists sheet fills.
struct AddPlaylists: Identifiable {
    let id = UUID()
    let folderId: String
}

/// A playlist's row on the Playlists page or a folder's page: its cover, name and count, opening
/// its page; a long press opens its menu.
struct PlaylistNavRow: View {
    let playlist: PlaylistSummary

    var body: some View {
        NavigationLink(value: Route.library(.playlist(id: playlist.id))) {
            PlaylistLine(name: playlist.name,
                         subtitle: playlist.family ? "By Family · \(songsCount(playlist.songCount))" : songsCount(playlist.songCount),
                         onServer: playlist.onServer) {
                PlaylistCover(playlist: playlist, size: 56)
            }
        }
        .buttonStyle(SqueezeStyle())
        .id(playlist.id)
        .contentShape(.contextMenuPreview, RoundedRectangle(cornerRadius: OctoRadius.row))
        .contextMenu { CollectionMenuItems(subject: .playlist(playlist)) }
    }
}

/// A folder's row, as tall as a playlist's: its glyph on a 56 point tile, its name, and how many
/// playlists and folders it holds; it opens the folder's page, and a long press opens its menu.
struct PlaylistFolderNavRow: View {
    let entry: FolderEntry

    var body: some View {
        NavigationLink(value: Route.library(.playlistFolder(id: entry.folder.id))) {
            PlaylistLine(name: entry.folder.name, subtitle: PlaylistFolderWords.folderSubtitle(playlists: entry.playlists, folders: entry.folders)) {
                FolderTile(size: 56)
            }
        }
        .buttonStyle(SqueezeStyle())
        .contentShape(.contextMenuPreview, RoundedRectangle(cornerRadius: OctoRadius.row))
        .contextMenu { FolderMenuItems(folderId: entry.folder.id) }
        .accessibilityHint(PlaylistFolderWords.playlistFolder)
    }
}

/// The playlist folder glyph on a tile, as a folder's picture: the accent on the raised surface,
/// so the tile steps off the page like a cover.
struct FolderTile: View {
    var size: CGFloat = 56

    var body: some View {
        RoundedRectangle(cornerRadius: OctoRadius.artS, style: .continuous)
            .fill(OctoColor.surfaceRaised)
            .frame(width: size, height: size)
            .overlay {
                Image(systemName: OctoIcon.playlistFolder.symbol)
                    .font(.system(size: size * 0.45))
                    .foregroundStyle(OctoColor.accent)
            }
            .accessibilityHidden(true)
    }
}

/// A folder's menu, from a long press on its row or its page's More: a new playlist or folder in
/// it, Add playlists, Rename, Move to folder, and last, Delete folder.
struct FolderMenuItems: View {
    let folderId: String
    @Environment(LibraryActions.self) private var actions

    var body: some View {
        let tree = actions.state.data.folders.tree
        Section(tree.folder(folderId)?.name ?? "") {
            Button(PlaylistFolderWords.newPlaylistHere, systemImage: OctoIcon.addToLibrary.symbol) {
                actions.askNewPlaylist(.tracks([]), in: folderId)
            }
            if tree.canHoldFolder(folderId) {
                Button(PlaylistFolderWords.newFolderHere, systemImage: "folder.badge.plus") { actions.askNewFolder(in: folderId) }
            }
            Button(PlaylistFolderWords.addPlaylists, systemImage: "checklist") { actions.addPlaylists = AddPlaylists(folderId: folderId) }
            Button(PlaylistFolderWords.rename, systemImage: OctoIcon.rename.symbol) { actions.askRenameFolder(folderId) }
            Button(PlaylistFolderWords.moveToFolder, systemImage: OctoIcon.playlistFolder.symbol) {
                actions.folderMove = FolderMove(folderId: folderId)
            }
        }
        Section {
            Button(PlaylistFolderWords.deleteFolder, systemImage: OctoIcon.delete.symbol, role: .destructive) { actions.askDeleteFolder(folderId) }
        }
    }
}

/// Octo's library upkeep lists, below the playlists: a group header in the section style, as
/// "From Octo" after it, with the system's disclosure chevron on the title's line, closed until
/// opened (remembered on the phone). While it is closed, a note in the helper style under the
/// header says which notice lists hold songs, "Review has 3 songs". Never inside a folder.
struct UpkeepGroup: View {
    let lists: [PlaylistSummary]
    @Binding var open: Bool

    var body: some View {
        let note = PlaylistFolderWords.upkeepNote(lists.filter { $0.octoNotice != nil }.map { (name: $0.name, count: $0.songCount) })
        VStack(alignment: .leading, spacing: 4) {
            DisclosureGroup(isExpanded: $open) {
                VStack(spacing: 0) {
                    ForEach(lists) { PlaylistNavRow(playlist: $0) }
                }
                .padding(.horizontal, -20)
            } label: {
                Text(PlaylistFolderWords.libraryUpkeep)
                    .font(.octoSection)
                    .foregroundStyle(OctoColor.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityAddTraits(.isHeader)
            }
            .tint(OctoColor.textSecondary)
            if !open, let note {
                Label {
                    Text(note).foregroundStyle(OctoColor.textSecondary)
                } icon: {
                    Image(systemName: OctoIcon.info.symbol).foregroundStyle(OctoColor.accent)
                }
                .font(.octoFormDescription)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 24)
    }
}

/// A playlist folder's page: line one the back button and the folder's whole name; line two Sort
/// and More (New playlist here, New folder here, Add playlists, Rename, Move to folder, Delete
/// folder); then its folders A to Z, a gap, and its playlists in the chosen order. An empty one
/// says so, with the ways to fill it. A folder deleted meanwhile closes its page.
struct PlaylistFolderPage: View {
    let id: String
    @Environment(Library.self) private var library
    @Environment(LibraryState.self) private var state
    @Environment(\.dismiss) private var dismiss
    @State private var playlists: [PlaylistSummary]?

    private var folders: PlaylistFolderStore { state.data.folders }

    var body: some View {
        let tree = folders.tree
        let rows = playlistPageRows(sortPlaylists(playlists ?? [], state.order(.playlists)), key: \.placeKey, upkeep: { $0.upkeep != nil },
                                    tree: tree, folder: id)
        let name = tree.folder(id)?.name ?? ""
        LibraryList {
            VStack(spacing: 0) {
                PageHeader(name)
                ControlLine(overflow: playlists == nil ? [] : [.sort(.playlists)]) {
                    GlassMoreMenu { FolderMenuItems(folderId: id) }
                }
            }
            .padding(.top, pageTopGap)
            if playlists != nil, rows.folders.isEmpty, rows.playlists.isEmpty {
                // The system's own empty state, as other iPhone apps show one.
                ContentUnavailableView(PlaylistFolderWords.nothingHereYet, systemImage: OctoIcon.playlistFolder.symbol,
                                       description: Text(PlaylistFolderWords.emptyFolderNote))
                    .padding(.vertical, 16)
            }
            ForEach(rows.folders) { entry in PlaylistFolderNavRow(entry: entry) }
            if !rows.folders.isEmpty && !rows.playlists.isEmpty { Color.clear.frame(height: 16) }
            ForEach(rows.playlists) { playlist in PlaylistNavRow(playlist: playlist) }
            Color.clear.frame(height: 20)
        }
        .inlineTitle(name)
        .barClearance()
        .task(id: library.stamp(.playlists, .library)) { playlists = await library.playlists() }
        .onChange(of: tree.folder(id) == nil) { _, gone in
            if gone && playlists != nil { dismiss() }
        }
    }
}

/// Move to Folder, on a sheet: "Not in a folder", then every folder in tree order, set in by its
/// depth; where the playlists (or the folder) sit now carries the accent fill and a check. Moving a
/// folder, it and its own folders are dimmed and say a folder can't go inside itself, and a folder
/// past four levels says so. New folder sits in the bar at the bottom: it is made at the top level
/// and what is moving goes in it.
struct MoveToFolderSheet: View {
    let move: FolderMove
    @Environment(LibraryActions.self) private var actions
    @Environment(Router.self) private var router
    @Environment(\.dismiss) private var dismiss
    @State private var naming: NameRequest?

    private var folders: PlaylistFolderStore { actions.state.data.folders }

    var body: some View {
        let tree = folders.tree
        let here = currentPlace(tree)
        NavigationStack {
            List {
                row(PlaylistFolderWords.notInAFolder, depth: 0, target: nil, here: here == .some(nil), refusal: refusal(nil, tree))
                ForEach(Array(tree.inTreeOrder().enumerated()), id: \.offset) { _, entry in
                    row(entry.folder.name, depth: entry.depth, target: entry.folder.id, here: here == .some(entry.folder.id),
                        refusal: refusal(entry.folder.id, tree))
                }
            }
            .navigationTitle(PlaylistFolderWords.moveToFolder)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(PlaylistFolderWords.cancel) { dismiss() } }
            }
            .safeAreaInset(edge: .bottom) {
                Button {
                    naming = NameRequest(title: PlaylistFolderWords.newFolder, action: "Create", placeholder: PlaylistFolderWords.folderName) { name in
                        Task {
                            if let id = await folders.create(name, in: nil) { moveTo(id) }
                        }
                    }
                } label: {
                    Label(PlaylistFolderWords.newFolder, systemImage: OctoIcon.playlistFolder.symbol).frame(maxWidth: .infinity)
                }
                .buttonStyle(.glass)
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
            }
        }
        .presentationDetents([.medium, .large])
        .nameAlert($naming)
    }

    private func row(_ name: String, depth: Int, target: String?, here: Bool, refusal: FolderMoveRefusal?) -> some View {
        Button {
            if let refusal {
                router.show(refusal.words)
            } else {
                moveTo(target)
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: (target == nil ? OctoIcon.playlists : OctoIcon.playlistFolder).symbol)
                    .frame(width: 24)
                Text(name).lineLimit(1)
                Spacer(minLength: 0)
                if here { Image(systemName: OctoIcon.check.symbol).fontWeight(.semibold) }
            }
            .padding(.leading, CGFloat(max(depth - 1, 0)) * 16)
            .foregroundStyle(here ? OctoColor.onAccentFill : OctoColor.textPrimary)
            .opacity(refusal == nil ? 1 : 0.4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(here ? Rectangle().fill(OctoColor.accentFill) : nil)
        .accessibilityAddTraits(here ? .isSelected : [])
        .accessibilityHint(refusal?.words ?? "")
    }

    /// Where what is moving sits now: `.some(nil)` at the top, `.some(id)` in a folder, nil for
    /// playlists in several places.
    private func currentPlace(_ tree: PlaylistFolderTree) -> String?? {
        if let folderId = move.folderId { return .some(tree.parentOf(folderId)) }
        let places = Set(move.playlistKeys.map { tree.folderOf($0) })
        return places.count == 1 ? places.first : nil
    }

    private func refusal(_ target: String?, _ tree: PlaylistFolderTree) -> FolderMoveRefusal? {
        guard let folderId = move.folderId else { return nil }
        return tree.moveRefusal(folderId, into: target)
    }

    private func moveTo(_ target: String?) {
        dismiss()
        Task {
            if let folderId = move.folderId {
                if let refusal = await folders.move(folderId, into: target) { router.show(refusal.words) }
            } else {
                await folders.place(move.playlistKeys, in: target)
            }
        }
    }
}

/// Add Playlists, on a sheet: the playlists not in this folder (never Octo's upkeep lists), each
/// with a check (picked: the accent fill and a check), and Move here (N) in the bar at the bottom,
/// which moves them all in one change.
struct AddPlaylistsSheet: View {
    let folderId: String
    @Environment(LibraryState.self) private var state
    @Environment(Library.self) private var library
    @Environment(\.dismiss) private var dismiss
    @State private var playlists: [PlaylistSummary] = []
    @State private var picked = Set<String>()

    var body: some View {
        let folders = state.data.folders
        let offered = sortPlaylists(playlists, state.order(.playlists))
            .filter { $0.upkeep == nil && folders.tree.folderOf($0.placeKey) != folderId }
        NavigationStack {
            List(offered) { playlist in
                let on = picked.contains(playlist.placeKey)
                Button { toggle(playlist.placeKey) } label: {
                    HStack(spacing: 12) {
                        PlaylistCover(playlist: playlist, size: 44, radius: 6)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(playlist.name).lineLimit(1).foregroundStyle(on ? OctoColor.onAccentFill : OctoColor.textPrimary)
                            Text(songsCount(playlist.songCount)).font(.footnote).foregroundStyle(on ? OctoColor.onAccentFill : OctoColor.textMuted)
                        }
                        Spacer(minLength: 0)
                        Image(systemName: on ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(on ? OctoColor.onAccentFill : OctoColor.textMuted)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .listRowBackground(on ? Rectangle().fill(OctoColor.accentFill) : nil)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
            .navigationTitle(PlaylistFolderWords.addPlaylists)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(PlaylistFolderWords.cancel) { dismiss() } }
            }
            .safeAreaInset(edge: .bottom) {
                Button {
                    let keys = offered.map(\.placeKey).filter(picked.contains)
                    dismiss()
                    Task { await folders.place(keys, in: folderId) }
                } label: {
                    Text(PlaylistFolderWords.moveHere(picked.count)).frame(maxWidth: .infinity)
                }
                .buttonStyle(.glassProminent)
                .disabled(picked.isEmpty)
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
            }
        }
        .presentationDetents([.medium, .large])
        .task { playlists = await library.playlists() }
    }

    private func toggle(_ key: String) {
        if picked.contains(key) { picked.remove(key) } else { picked.insert(key) }
    }
}
