import OctoKit
import SwiftUI

/// "Choose the version" for the song found online playing now, in a sheet: the version playing in
/// its own section (the accent fill and a check, never a button), then the others; a pick plays at
/// once from its start; the footer says the pick is for everyone on the server, and while a
/// person's pick stands it offers Octo's own back. Its states are the shared ones
/// (`StreamVersionsSheetState`), so it behaves and speaks as the other apps do. It closes when
/// another entry plays.
struct VersionsSheet: View {
    let entryId: String
    /// The song's id on the server.
    let serverId: String
    let song: Song
    let dismiss: () -> Void
    @Environment(Session.self) private var session
    @Environment(Player.self) private var player
    @State private var state: StreamVersionsSheetState
    @State private var asks = 0

    init(entryId: String, serverId: String, song: Song, dismiss: @escaping () -> Void) {
        self.entryId = entryId
        self.serverId = serverId
        self.song = song
        self.dismiss = dismiss
        let opened = VersionsSong(entryId: entryId, songId: serverId, title: song.title, artist: song.artist ?? "")
        _state = State(initialValue: StreamVersionsSheetState.closed.on(.open(opened)))
    }

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(StreamVersionWords.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { done }
        }
        .task(id: asks) { await look() }
        .onChange(of: player.currentItem?.entryId) { _, now in
            let next = state.on(.entryChanged(now))
            guard next != state else { return }
            state = next
            dismiss()
        }
    }

    private var done: some ToolbarContent {
        ToolbarItem(placement: .confirmationAction) {
            Button(StreamVersionWords.done, action: dismiss)
        }
    }

    private var content: some View {
        list.safeAreaInset(edge: .bottom, spacing: 0) { footer }
    }

    /// The server's answer, once the sheet shows one.
    private var shown: StreamVersionsShown? {
        if case .shown(let shown) = state { shown } else { nil }
    }

    private var list: some View {
        let shown = shown
        return List {
            Section {
                Text(StreamVersionWords.songLine(title: song.title, artist: song.artist ?? ""))
                    .font(.octoBodySmall)
                    .foregroundStyle(OctoColor.textMuted)
                    .listRowBackground(Color.clear)
            }
            if let shown, let playing = shown.playing {
                Section(StreamVersionWords.playingNow) {
                    PlayingVersionRow(playing: playing, note: StreamVersionWords.playingNote(playing, heardVideoId: shown.answer.heardVideoId))
                }
            }
            if let line = StreamVersionWords.stateLine(state) {
                Section { stateRow(line) }
            }
            if let shown, shown.picksOpen {
                Section(StreamVersionWords.otherVersions) { others(shown) }
            }
        }
        .listStyle(.insetGrouped)
    }

    /// Where the sheet stands, in its own words: looking (with a spinner), could not reach (with
    /// Try again), the server's refusal, or the notice above the list.
    @ViewBuilder
    private func stateRow(_ line: String) -> some View {
        HStack(spacing: 10) {
            if case .looking = state { ProgressView() }
            Text(line).font(.octoBodySmall).foregroundStyle(OctoColor.textMuted)
        }
        if case .couldNotReach = state {
            Button(StreamVersionWords.tryAgain, action: retry)
        }
    }

    @ViewBuilder
    private func others(_ shown: StreamVersionsShown) -> some View {
        if let empty = shown.emptyText {
            Text(empty).font(.octoBodySmall).foregroundStyle(OctoColor.textMuted)
        }
        ForEach(shown.rows, id: \.videoId) { version in
            VersionChoiceRow(version: version, checking: shown.checking == version.videoId,
                             refused: shown.refused == version.videoId ? shown.refusal : nil) { pick(version.videoId) }
                .disabled(shown.checking != nil)
        }
    }

    /// The footer, above the home indicator whatever the list's length: whose the pick is for, and
    /// while a person's pick stands, the way back to Octo's.
    private var footer: some View {
        let shown = shown
        return VStack(alignment: .leading, spacing: 8) {
            Text(StreamVersionWords.note).font(.octoCaption).foregroundStyle(OctoColor.textSecondary)
            if let shown, shown.canUndo {
                HStack(spacing: 8) {
                    Button(StreamVersionWords.goBack) { pick(nil) }
                        .font(.octoLabel)
                        .disabled(shown.checking != nil)
                    if shown.checking == STREAM_VERSION_AUTO { ProgressView() }
                }
            }
            if let shown, shown.refused == STREAM_VERSION_AUTO, let words = shown.refusal {
                Text(words).font(.octoCaption).foregroundStyle(OctoColor.textMuted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(.bar)
    }

    private func look() async {
        guard case .looking = state else { return }
        guard let client = session.client else {
            state = state.on(.listFailed(message: nil))
            return
        }
        let event: StreamVersionsEvent
        do {
            event = .listed(try await client.streamVersions(serverId))
        } catch {
            guard !Task.isCancelled else { return }
            event = .listFailed(message: Self.refusal(error))
        }
        state = state.on(event)
    }

    private func retry() {
        let next = state.on(.retry)
        guard next != state else { return }
        state = next
        asks += 1
    }

    /// A pick (`video`) or the way back to Octo's pick (nil) the state takes goes to the server;
    /// its answer is taken by the player (copies dropped, the song started over) even when the
    /// sheet was closed meanwhile.
    private func pick(_ video: String?) {
        guard let client = session.client else { return }
        let item = player.currentItem
        let playingSong = item.flatMap { parseStreamUri($0.uri)?.serverId }
        let event: StreamVersionsEvent = if let video {
            .pick(videoId: video, playingEntryId: item?.entryId, playingSongId: playingSong)
        } else {
            .undo(playingEntryId: item?.entryId, playingSongId: playingSong)
        }
        let asked = state.on(event)
        guard asked != state else { return }
        state = asked
        let entryId = self.entryId
        let serverId = self.serverId
        let chosen = video ?? STREAM_VERSION_AUTO
        Task {
            let answer: StreamVersions
            do {
                answer = try await client.setStreamVersion(serverId, video: chosen)
            } catch {
                state = state.on(.pickFailed(message: Self.refusal(error) ?? StreamVersionWords.pickUnreachable))
                return
            }
            state = state.on(.picked(answer))
            await player.streamVersionTaken(entryId: entryId, answer: answer)
        }
    }

    /// The server's own words for a refusal; nil when it could not be reached or did not answer as
    /// itself.
    private static func refusal(_ error: Error) -> String? {
        switch error as? OctoError {
        case .server(_, let message)?: message.isEmpty ? nil : message
        case .notFound(let message)?: message.isEmpty ? nil : message
        default: nil
        }
    }
}

/// The version playing: the accent fill and a check; who picked it, and that it plays from the
/// listener's next start when what they hear now is another (`note`, `playingNote`).
private struct PlayingVersionRow: View {
    let playing: StreamVersionPlaying
    let note: String

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(playing.title ?? playing.videoId).font(.octoBody).lineLimit(2)
                Text(StreamVersionWords.rowDetail(channel: playing.channel, seconds: playing.duration, kind: playing.kind))
                    .font(.octoCaption)
                    .foregroundStyle(OctoColor.textSecondary)
                Text(note).font(.octoCaption).foregroundStyle(OctoColor.accent)
            }
            Spacer(minLength: 0)
            Image(systemName: "checkmark")
                .font(.body.weight(.semibold))
                .foregroundStyle(OctoColor.accent)
        }
        .listRowBackground(Rectangle().fill(OctoColor.accentSelected))
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isSelected)
    }
}

/// Another version: its title (two lines at most) and "channel · length · kind"; while it is
/// checked, a spinner; refused, the server's words under it.
private struct VersionChoiceRow: View {
    let version: StreamVersion
    let checking: Bool
    let refused: String?
    let pick: () -> Void

    var body: some View {
        Button(action: pick) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(version.title).font(.octoBody).foregroundStyle(.primary).lineLimit(2)
                    Text(StreamVersionWords.rowDetail(channel: version.channel, seconds: version.duration, kind: version.kind))
                        .font(.octoCaption)
                        .foregroundStyle(OctoColor.textSecondary)
                    if let refused {
                        Text(refused).font(.octoCaption).foregroundStyle(OctoColor.textMuted)
                    }
                }
                Spacer(minLength: 0)
                if checking {
                    ProgressView()
                    Text(StreamVersionWords.checking).font(.octoCaption).foregroundStyle(OctoColor.textMuted)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
