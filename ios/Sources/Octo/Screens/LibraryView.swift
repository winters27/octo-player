import OctoKit
import SwiftUI

/// One way into the library, like Albums or Songs.
private struct LibrarySection {
    let icon: OctoIcon
    let label: String
    let route: Route
}

private let librarySections = [
    LibrarySection(icon: .playlists, label: "Playlists", route: .playlists),
    LibrarySection(icon: .like, label: "Favorites", route: .favorites),
    LibrarySection(icon: .artist, label: "Artists", route: .artists),
    LibrarySection(icon: .album, label: "Albums", route: .albums),
    LibrarySection(icon: .songs, label: "Songs", route: .library(.songs)),
    LibrarySection(icon: .genres, label: "Genres", route: .library(.genres)),
    LibrarySection(icon: .folder, label: "Folders", route: .library(.folders)),
    LibrarySection(icon: .downloaded, label: "Downloads", route: .library(.downloads)),
    LibrarySection(icon: .history, label: "History", route: .library(.history(mostPlayed: false))),
    LibrarySection(icon: .check, label: "Library health", route: .library(.health)),
]

/// The library's front page: a menu of ways in, then the newest albums, two covers to a row.
struct LibraryView: View {
    @Environment(Library.self) private var library
    @Environment(ServerDownloads.self) private var serverDownloads
    @Environment(Router.self) private var router
    @State private var recent: [LibraryAlbum] = []

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ScreenTitle("Library")
                ForEach(Array(librarySections.enumerated()), id: \.offset) { index, section in
                    if index > 0 { LibraryHairline() }
                    NavigationLink(value: section.route) {
                        SectionRow(icon: section.icon, label: section.label)
                    }
                    .buttonStyle(SqueezeStyle())
                }
                // What the server is downloading, on a server that keeps a log of it.
                if serverDownloads.supported {
                    LibraryHairline()
                    Button { router.showServerDownloads() } label: {
                        // "2 on the way, 1 waits for you".
                        SectionRow(icon: .cloud, label: "Server downloads", detail: drawerState(serverDownloads.watch.rows))
                    }
                    .buttonStyle(SqueezeStyle())
                }
                if !recent.isEmpty {
                    SectionTitle("Recently added").padding(.top, 24)
                    ForEach(Array(stride(from: 0, to: recent.count, by: 2)), id: \.self) { start in
                        HStack(alignment: .top, spacing: 14) {
                            GridAlbumCard(album: recent[start])
                            if start + 1 < recent.count {
                                GridAlbumCard(album: recent[start + 1])
                            } else {
                                Color.clear.frame(maxWidth: .infinity)
                            }
                        }
                        .padding(.horizontal, 20)
                        .padding(.vertical, 9)
                    }
                }
            }
        }
        .inlineTitle("Library")
        .barClearance()
        .libraryRefreshable {}
        .task(id: library.stamp(.library)) { recent = await library.recentAlbums(limit: 20) }
    }
}

/// One row of the front page's menu: an accent icon, the label, an optional detail and a chevron.
private struct SectionRow: View {
    let icon: OctoIcon
    let label: String
    var detail: String?

    var body: some View {
        HStack(spacing: 16) {
            IconView(icon, size: 24, color: OctoColor.accent)
            Text(label).font(.octoBody).foregroundStyle(OctoColor.textPrimary).frame(maxWidth: .infinity, alignment: .leading)
            if let detail { Text(detail).font(.octoCaption).foregroundStyle(OctoColor.textMuted) }
            IconView(.chevron, size: 22, color: OctoColor.textMuted)
        }
        .padding(.horizontal, 20)
        .frame(height: 52)
        .contentShape(Rectangle())
    }
}

/// A hairline between menu rows, starting under the labels.
struct LibraryHairline: View {
    var leading: CGFloat = 60
    var trailing: CGFloat = 20

    var body: some View {
        Rectangle().fill(OctoColor.hairline).frame(height: 1).padding(.leading, leading).padding(.trailing, trailing)
    }
}

/// Runs of rows under their letters, for a list ordered by name.
struct LetterRuns<Item, Row: View>: View {
    let items: [Item]
    let headings: [String]?
    let id: (Item) -> String
    @ViewBuilder let row: (Int, Item) -> Row

    /// One row's place in the list and its id.
    private struct Entry: Identifiable {
        let position: Int
        let id: String
    }

    private func entries(_ positions: Range<Int>) -> [Entry] {
        positions.map { Entry(position: $0, id: id(items[$0])) }
    }

    var body: some View {
        if let headings, headings.count == items.count {
            let runs = letterRuns(headings)
            ForEach(Array(runs.enumerated()), id: \.offset) { index, run in
                Section {
                    ForEach(entries(run.start..<(run.start + run.count))) { entry in
                        row(entry.position, items[entry.position]).id(entry.id)
                    }
                } header: {
                    LetterHeading(letter: run.letter).plainListRow().id("run-\(index)")
                }
            }
        } else {
            ForEach(entries(items.indices)) { entry in
                row(entry.position, items[entry.position]).id(entry.id)
            }
        }
    }
}

/// Where the letter rail jumps in a list with headings: each letter's first run.
func railTargets(_ headings: [String]?) -> [RailStop] {
    guard let headings else { return [] }
    var seen = Set<Character>()
    return letterRuns(headings).enumerated().compactMap { index, run in
        seen.insert(run.letter).inserted ? RailStop(run.letter, index) : nil
    }
}

extension View {
    /// The letter rail beside a long list ordered by name, clear of the system bar above and the
    /// floating bar below.
    func letterRail(_ headings: [String]?, count: Int, byName: Bool, jump: @escaping (Int) -> Void) -> some View {
        modifier(LetterRailPlace(headings: headings, count: count, byName: byName, jump: jump))
    }
}

private struct LetterRailPlace: ViewModifier {
    let headings: [String]?
    let count: Int
    let byName: Bool
    let jump: (Int) -> Void
    @Environment(PageTop.self) private var top: PageTop?

    func body(content: Content) -> some View {
        content.overlay(alignment: .trailing) {
            if byName, count >= railMinItems, let headings {
                LetterRail(stops: railTargets(headings), jump: jump)
                    .padding(.top, railTop)
                    .padding(.bottom, 132)
            }
        }
    }

    /// 8 under the system bar: the overlay starts at the content's top, which may sit inside the bar.
    private var railTop: CGFloat {
        guard let top else { return 8 }
        return max(8, top.barBottom - top.contentTop + 8)
    }
}

// MARK: Albums

/// Every album, as a grid of covers, in the chosen order.
struct AlbumsView: View {
    @Environment(Library.self) private var library
    @Environment(LibraryState.self) private var state
    @Environment(LibraryActions.self) private var actions
    @Environment(Router.self) private var router
    @State private var sorted: Sorted<LibraryAlbum>?

    private let columns = [GridItem(.adaptive(minimum: 150), spacing: 14)]

    var body: some View {
        let order = state.order(.albums)
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    VStack(spacing: 0) {
                        PageHeader("Albums", count: sorted.flatMap { $0.items.isEmpty ? nil : countText($0.items.count, "album", "albums") })
                        ControlLine(overflow: [.sort(.albums)], more: randomAlbum(sorted?.items ?? [])) {
                            if let sorted, !sorted.items.isEmpty {
                                RoundPlayShuffle {
                                    playAll(sorted.items, shuffled: false)
                                } shuffle: {
                                    playAll(sorted.items, shuffled: true)
                                }
                            }
                        }
                    }
                    .padding(.top, pageTopGap)
                    if let sorted {
                        if sorted.items.isEmpty {
                            CopyAwareNote { EmptyLibraryNote() }
                        } else {
                            FirstRowMark().id("first")
                            LazyVGrid(columns: columns, alignment: .leading, spacing: 18) {
                                ForEach(sorted.items) { album in GridAlbumCard(album: album).id(album.id) }
                            }
                            .padding(.horizontal, 20)
                            .padding(.top, 4)
                            .padding(.bottom, 140)
                            .animation(.easeOut(duration: 0.25), value: sorted.items.map(\.id))
                        }
                    } else {
                        LibrarySpinner()
                    }
                }
            }
            .letterRail(sorted?.headings, count: sorted?.items.count ?? 0, byName: order.by.byName) { index in
                if let items = sorted?.items, let headings = sorted?.headings {
                    let runs = letterRuns(headings)
                    if runs.indices.contains(index) { proxy.scrollTo(items[runs[index].start].id, anchor: .top) }
                }
            }
            .topOnNewOrder(sorted?.order) { proxy.scrollTo("first", anchor: .top) }
        }
        .barClearance()
        .inlineTitle("Albums")
        .libraryRefreshable {}
        .task(id: "\(library.stamp(.library, .plays))|\(order.by.id)|\(order.descending)") {
            sorted = await library.sortedAlbums(order)
        }
    }

    /// Random album, More's own row while there are albums.
    private func randomAlbum(_ albums: [LibraryAlbum]) -> AnyView? {
        guard !albums.isEmpty else { return nil }
        return AnyView(Button("Random album", systemImage: "dice") {
            if let album = albums.randomElement() { router.go(.album(album.asAlbum)) }
        })
    }

    /// Plays every album in the order shown, each album's songs in album order.
    private func playAll(_ albums: [LibraryAlbum], shuffled: Bool) {
        Task {
            let places = await library.trackPlaces()
            let ids = songsInGroupOrder(places, groupOf: \.albumId, groups: albums.map(\.id))
            actions.play(await library.tracks(ids: ids), shuffled: shuffled)
        }
    }
}

// MARK: Artists

/// Every artist, in the chosen order, under letters when by name.
struct ArtistsView: View {
    @Environment(Library.self) private var library
    @Environment(LibraryState.self) private var state
    @Environment(LibraryActions.self) private var actions
    @State private var sorted: Sorted<LibraryArtist>?

    var body: some View {
        let order = state.order(.artists)
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0, pinnedViews: .sectionHeaders) {
                    VStack(spacing: 0) {
                        PageHeader("Artists", count: sorted.flatMap { $0.items.isEmpty ? nil : countText($0.items.count, "artist", "artists") })
                        ControlLine(overflow: [.sort(.artists)]) {
                            if let sorted, !sorted.items.isEmpty {
                                RoundPlayShuffle {
                                    playAll(sorted.items, shuffled: false)
                                } shuffle: {
                                    playAll(sorted.items, shuffled: true)
                                }
                            }
                        }
                    }
                    .padding(.top, pageTopGap)
                    if let sorted {
                        if sorted.items.isEmpty {
                            CopyAwareNote { EmptyLibraryNote() }
                        } else {
                            FirstRowMark().id("first")
                            LetterRuns(items: sorted.items, headings: sorted.headings, id: \.id) { _, artist in
                                ArtistRow(artist: artist)
                            }
                            Color.clear.frame(height: 140)
                        }
                    } else {
                        LibrarySpinner()
                    }
                }
                .animation(.easeOut(duration: 0.25), value: sorted?.order)
            }
            .letterRail(sorted?.headings, count: sorted?.items.count ?? 0, byName: order.by.byName) { index in
                proxy.scrollTo("run-\(index)", anchor: .top)
            }
            .topOnNewOrder(sorted?.order) { proxy.scrollTo("first", anchor: .top) }
        }
        .barClearance()
        .inlineTitle("Artists")
        .libraryRefreshable {}
        .task(id: "\(library.stamp(.library, .plays))|\(order.by.id)|\(order.descending)") {
            sorted = await library.sortedArtists(order)
        }
    }

    /// Plays every artist in the order shown, artist by artist, each album by album.
    /// Plays the songs of the artists shown, in the order shown, or shuffled.
    private func playAll(_ artists: [LibraryArtist], shuffled: Bool) {
        Task {
            let places = await library.trackPlaces()
            let ids = songsInGroupOrder(places, groupOf: \.artistId, groups: artists.map(\.id))
            actions.play(await library.tracks(ids: ids), shuffled: shuffled)
        }
    }
}

// MARK: Favorites

/// Favorites: hearted songs, albums and artists, in three segments.
struct FavoritesView: View {
    var opening: FavoriteSegment?
    @Environment(Library.self) private var library
    @Environment(LibraryState.self) private var state
    @Environment(LibraryActions.self) private var actions
    @Environment(Router.self) private var router
    @Environment(OfflineDownloads.self) private var offline
    @State private var segment: FavoriteSegment?
    @State private var songs: [LibraryTrack]?
    @State private var albums: [LikedAlbum]?
    @State private var artists: [LikedArtist]?
    /// The orders the lists shown are in.
    @State private var songsOrder: ListOrder?
    @State private var favoritesOrder: ListOrder?

    private let columns = [GridItem(.adaptive(minimum: 150), spacing: 14)]

    /// Liked songs sit in a list, for their swipes; albums and artists in a plain scroll.
    var body: some View {
        ScrollViewReader { proxy in
            Group {
                if segment == .songs {
                    LibraryList {
                        top
                        songsSegment
                        Color.clear.frame(height: 20)
                    }
                    .animation(.easeOut(duration: 0.25), value: songs?.map(\.id))
                    .topOnNewOrder(songsOrder) { proxy.scrollTo("liked-top", anchor: .top) }
                } else {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 0) {
                            top
                            switch segment {
                            case .albums: albumsSegment
                            case .artists: artistsSegment
                            case .songs, nil: LibrarySpinner()
                            }
                        }
                        .animation(.easeOut(duration: 0.25), value: favoritesShown)
                    }
                    .topOnNewOrder(favoritesOrder) { proxy.scrollTo("favorites-top", anchor: .top) }
                }
            }
        }
        .barClearance()
        .inlineTitle("Favorites")
        .backStopsPicking()
        .selectableSongs(state.pickables(songs ?? []))
        .libraryRefreshable {}
        .task(id: "\(library.stamp(.library, .likes, .favorites))|\(state.order(.liked).by.id)|\(state.order(.liked).descending)|\(state.order(.favorites).by.id)|\(state.order(.favorites).descending)") {
            await read()
        }
    }

    /// The title with the segment's count, then one line: the sort button, the segments, and for
    /// Liked songs Play, Shuffle and More, which holds Keep downloaded.
    private var top: some View {
        VStack(spacing: 0) {
            PageHeader("Favorites", count: countWords)
            if let segment {
                let selection = Binding(get: { segment }, set: { self.segment = $0 })
                ControlLine(fills: true, overflow: [.sort(segment == .songs ? .liked : .favorites)], more: keepDownloaded(segment),
                            chipsMenu: AnyView(segmentsMenu(selection))) {
                    GlassSegments(options: FavoriteSegment.allCases.map { ($0, $0.rawValue) }, selection: selection)
                } actions: {
                    if segment == .songs, let songs, !songs.isEmpty {
                        RoundPlayShuffle {
                            actions.play(songs, source: "Favorites")
                        } shuffle: {
                            actions.play(songs, shuffled: true, source: "Favorites")
                        }
                    }
                }
            }
        }
        .padding(.top, pageTopGap)
    }

    /// Keep downloaded, More's own row on Liked songs while it is offered.
    private func keepDownloaded(_ segment: FavoriteSegment) -> AnyView? {
        guard segment == .songs, let songs, offersKeepDownloaded(offline, tracks: songs, playlistId: nil) else { return nil }
        return AnyView(KeepDownloadedToggle(tracks: songs))
    }

    /// The three parts as one menu button, for a phone where even shrunk the segments do not fit.
    private func segmentsMenu(_ selection: Binding<FavoriteSegment>) -> some View {
        Menu {
            Picker("Show", selection: selection) {
                ForEach(FavoriteSegment.allCases, id: \.self) { part in Text(part.rawValue).tag(part) }
            }
            .pickerStyle(.inline)
        } label: {
            ChoiceChip(text: selection.wrappedValue.rawValue)
        }
    }

    /// The count of the segment shown, and for Liked songs kept downloaded how far that is.
    private var countWords: String? {
        switch segment {
        case .songs:
            guard let songs, !songs.isEmpty else { return nil }
            return [songsCount(songs.count), keptDownloadedWords(offline, tracks: songs, playlistId: nil)]
                .compactMap { $0 }.joined(separator: " · ")
        case .albums: return albums.flatMap { $0.isEmpty ? nil : countText($0.count, "album", "albums") }
        case .artists: return artists.flatMap { $0.isEmpty ? nil : countText($0.count, "artist", "artists") }
        case nil: return nil
        }
    }

    private func read() async {
        let liked = await library.likedTracks()
        let order = state.order(.liked)
        if let sorted = try? await library.store.tracks(sql: songQuery(order.by.song ?? .dateLiked, descending: order.descending, scope: .liked).sql) {
            songs = sorted.map(\.track)
        } else {
            songs = liked
        }
        songsOrder = order
        let favorites = state.order(.favorites)
        albums = sortFavorites(await library.likedAlbums(), order: favorites, likedAt: \.likedAt, name: { $0.album.sortKey }, id: { $0.album.id })
        artists = sortFavorites(await library.likedArtists(), order: favorites, likedAt: \.likedAt, name: { $0.artist.sortKey }, id: { $0.artist.id })
        favoritesOrder = favorites
        // Picked once all three are read, then kept, so the page never jumps while it is open.
        if segment == nil {
            segment = opening ?? firstSegment(songs: songs?.count ?? 0, albums: albums?.count ?? 0, artists: artists?.count ?? 0)
        }
    }

    /// The favorite albums and artists as shown, for their move into a new order.
    private var favoritesShown: [String] { (albums ?? []).map(\.album.id) + (artists ?? []).map(\.artist.id) }

    /// Play and Shuffle, the keep-downloaded switch, then the liked songs in their order. A swipe
    /// left unlikes a song, with an Undo.
    @ViewBuilder
    private var songsSegment: some View {
        if let songs {
            if songs.isEmpty {
                CopyAwareNote(likes: true) {
                    MutedNote("No liked songs yet. Tap the heart in the player to add the song that is playing, or long press any song and choose Add to Liked songs.")
                }
            } else {
                FirstRowMark().id("liked-top")
                ForEach(Array(songs.enumerated()), id: \.element.id) { position, track in
                    TrackRow(track: track, swipe: .remove { unlike(track) }) {
                        actions.play(songs, from: position, source: "Favorites")
                    }
                }
            }
        } else {
            LibrarySpinner()
        }
    }

    /// Unlikes at once, with an Undo that likes it again at its own time, so it returns to its place.
    private func unlike(_ track: LibraryTrack) {
        songs?.removeAll { $0.id == track.id }
        actions.unlikeUndoable(track)
    }

    @ViewBuilder
    private var albumsSegment: some View {
        if let albums {
            if albums.isEmpty {
                CopyAwareNote(likes: true) { MutedNote("Heart an album from its page or its menu to keep it here.") }
            } else {
                FirstRowMark().id("favorites-top")
                LazyVGrid(columns: columns, alignment: .leading, spacing: 18) {
                    ForEach(albums, id: \.album.id) { liked in GridAlbumCard(album: liked.album) }
                }
                .padding(.horizontal, 20)
                .padding(.top, 4)
                .padding(.bottom, 140)
            }
        } else {
            LibrarySpinner()
        }
    }

    @ViewBuilder
    private var artistsSegment: some View {
        if let artists {
            if artists.isEmpty {
                CopyAwareNote(likes: true) { MutedNote("Heart an artist from their page or their menu to keep them here.") }
            } else {
                FirstRowMark().id("favorites-top")
                ForEach(artists, id: \.artist.id) { liked in ArtistRow(artist: liked.artist) }
                Color.clear.frame(height: 140)
            }
        } else {
            LibrarySpinner()
        }
    }
}

// MARK: Reading sorted lists

extension Library {
    /// Every song in an order, by the order's SQL, then by plays for the play orders.
    func sortedSongs(_ order: ListOrder, scope: SongScope = .all) async -> Sorted<LibraryTrack> {
        let sort = order.by.song ?? .title
        let query = songQuery(sort, descending: order.descending, scope: scope)
        let rows = (try? await store.tracks(sql: query.sql, args: query.args)) ?? []
        let tracks = rows.map(\.track)
        if sort.byListening {
            let listening = songListening(await playedTracks())
            return Sorted(items: byListening(tracks, id: \.id, listening: listening, mostPlayed: sort == .mostPlayed,
                                             descending: order.descending), order: order, headings: nil)
        }
        return Sorted(items: tracks, order: order, headings: sort.byName ? rows.map { $0.heading ?? "" } : nil)
    }

    func sortedAlbums(_ order: ListOrder, artistId: String? = nil) async -> Sorted<LibraryAlbum> {
        let sort = order.by.album ?? .title
        let query = albumQuery(sort, descending: order.descending, artistId: artistId)
        let rows = (try? await store.albums(sql: query.sql, args: query.args)) ?? []
        let albums = rows.map(\.album)
        if sort.byListening {
            let listening = albumListening(await playedTracks(), await playedAlbums())
            return Sorted(items: byListening(albums, id: \.id, listening: listening, mostPlayed: sort == .mostPlayed,
                                             descending: order.descending), order: order, headings: nil)
        }
        return Sorted(items: albums, order: order, headings: sort.byName ? rows.map { $0.heading ?? "" } : nil)
    }

    func sortedArtists(_ order: ListOrder) async -> Sorted<LibraryArtist> {
        let sort = order.by.artist ?? .name
        let query = artistQuery(sort, descending: order.descending)
        let rows = (try? await store.artists(sql: query.sql, args: query.args)) ?? []
        let artists = rows.map(\.artist)
        if sort.byListening {
            let listening = artistListening(await playedTracks())
            return Sorted(items: byListening(artists, id: \.id, listening: listening, mostPlayed: sort == .mostPlayed,
                                             descending: order.descending), order: order, headings: nil)
        }
        return Sorted(items: artists, order: order, headings: sort.byName ? rows.map { $0.heading ?? "" } : nil)
    }
}
