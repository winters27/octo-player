import OctoKit
import SwiftUI
import UIKit

/// What Octo is doing, from its own admin pages on the home network: its health and its
/// services', the stations and how far they have learned, the latest downloads, and where
/// downloads land. Everything else is on the full admin pages.
struct OctoAdminScreen: View {
    @Environment(Session.self) private var session
    @Environment(\.openURL) private var openURL
    @State private var model: OctoAdminModel?
    @SceneStorage("admin.showAll") private var showAll = false

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                PageHeader("Octo admin")
                if let model { content(model) }
            }
            .padding(.top, pageTopGap)
        }
        .refreshable { await model?.refresh() }
        .barClearance()
        .onAppear {
            guard model == nil else { return }
            let made = OctoAdminModel(admin: OctoAdminPages.shared, signIn: { [session] in OctoAdminPages.signIn(session) })
            model = made
            made.open()
        }
        .onChange(of: session.activeId) { model?.serverChanged() }
        .onDisappear { model?.stop() }
    }

    @ViewBuilder
    private func content(_ model: OctoAdminModel) -> some View {
        switch model.place {
        case .looking:
            LoadingSpinner(size: 22).frame(maxWidth: .infinity).padding(.top, 24)
        case .away:
            VStack(spacing: 16) {
                Text("Octo's admin is only available on your home network")
                    .font(.octoBodySmall)
                    .foregroundStyle(OctoColor.textSecondary)
                    .multilineTextAlignment(.center)
                AccentButton("Try again", action: model.tryAgain)
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 32)
            .padding(.vertical, 48)
        case .found:
            health(model)
            stations(model)
            downloads(model)
            library(model)
            openFull(model)
        }
    }

    private func sectionTitle(_ text: String, extra: CGFloat) -> some View {
        SectionTitle(text).padding(.top, extra)
    }

    @ViewBuilder
    private func section<T>(_ load: AdminLoad<T>, @ViewBuilder ready: (T) -> some View) -> some View {
        switch load {
        case .loading: LoadingSpinner(size: 22).frame(maxWidth: .infinity).padding(.vertical, 8)
        case .failed(let words): note(words)
        case .ready(let value): ready(value)
        }
    }

    private func note(_ text: String) -> some View {
        Text(text).font(.octoBodySmall).foregroundStyle(OctoColor.textMuted).padding(.horizontal, 20)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: Health

    @ViewBuilder
    private func health(_ model: OctoAdminModel) -> some View {
        sectionTitle("Health", extra: 8)
        section(model.status) { status in
            HealthRow(name: "Octo", health: status.octo)
            ForEach(status.services, id: \.key) { service in
                HealthRow(name: serviceName(service.key), health: service.health, onPass: passAction(model, service))
            }
        }
    }

    /// Opens the full admin pages at the card a service's row leads to, or nil when it leads nowhere.
    private func passAction(_ model: OctoAdminModel, _ service: AdminService) -> (() -> Void)? {
        guard let card = dashboardSection(service.key, service.health) else { return nil }
        return { if let url = model.cardUrl(card) { openURL(url) } }
    }

    // MARK: Stations

    @ViewBuilder
    private func stations(_ model: OctoAdminModel) -> some View {
        sectionTitle("Stations", extra: 18)
        section(model.radio) { radio in
            if !radio.enabled {
                note("Stations are switched off in Octo")
            } else {
                if let learning = radio.learning {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(learningLine(learning)).font(.octoBodySmall).foregroundStyle(OctoColor.textPrimary)
                        Text(adminAgo(learning.lastRefreshSuccessUtc, nowMs: epochNowMs()).map { "Last refreshed \($0)" } ?? "Not refreshed yet")
                            .font(.octoCaption)
                            .foregroundStyle(OctoColor.textMuted)
                        if let problem = refreshProblem(learning.lastRefreshError) {
                            Text(problem).font(.octoCaption).foregroundStyle(OctoColor.error).lineLimit(2)
                        }
                    }
                    .padding(.horizontal, 20)
                }
                if radio.stations.isEmpty { note("No stations yet") }
                ForEach(Array(radio.stations.enumerated()), id: \.offset) { _, station in
                    VStack(alignment: .leading, spacing: 1) {
                        Text(station.name).font(.octoBodySmall).foregroundStyle(OctoColor.textPrimary).lineLimit(1)
                        Text(stationSongs(station.trackCount)).font(.octoCaption).foregroundStyle(OctoColor.textMuted)
                    }
                    .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                    .padding(.horizontal, 20)
                    .accessibilityElement(children: .combine)
                }
                let busy = model.refreshingStations || radio.learning?.refreshing == true
                GlazeButton(busy ? "Refreshing" : "Refresh stations", enabled: !busy, action: model.refreshStations)
                    .padding(.horizontal, 20)
                    .padding(.top, 8)
                if let problem = model.stationsProblem {
                    Text(problem).font(.octoCaption).foregroundStyle(OctoColor.error).padding(.horizontal, 20).padding(.top, 4)
                }
            }
        }
    }

    // MARK: Downloads

    @ViewBuilder
    private func downloads(_ model: OctoAdminModel) -> some View {
        sectionTitle("Downloads", extra: 18)
        section(model.downloads) { records in
            if records.isEmpty {
                note("Nothing downloaded yet")
            } else {
                let now = epochNowMs()
                let base = adminBase(model)
                ForEach(Array((showAll ? records : Array(records.prefix(30))).enumerated()), id: \.offset) { _, record in
                    DownloadRow(record: record, nowMs: now, client: session.client, base: base)
                }
                if records.count > 30 {
                    GlazeButton(showAll ? "Show fewer" : "Show all") { showAll.toggle() }
                        .padding(.leading, 20)
                        .padding(.top, 8)
                }
            }
        }
    }

    /// Where the admin pages answer, once found.
    private func adminBase(_ model: OctoAdminModel) -> URL? {
        if case .found(let base) = model.place { base } else { nil }
    }

    // MARK: Library

    @ViewBuilder
    private func library(_ model: OctoAdminModel) -> some View {
        sectionTitle("Library", extra: 18)
        section(model.library) { status in
            VStack(alignment: .leading, spacing: 6) {
                if !status.effectiveDownloadPath.trimmingCharacters(in: .whitespaces).isEmpty {
                    Text(status.effectiveDownloadPath).font(.system(size: 12, design: .monospaced)).foregroundStyle(OctoColor.textMuted)
                }
                let problems = libraryProblems(status)
                if problems.isEmpty {
                    Text("Downloads go straight into your library").font(.octoBodySmall).foregroundStyle(OctoColor.textSecondary)
                }
                ForEach(problems, id: \.self) { problem in
                    Text(problem).font(.octoBodySmall).foregroundStyle(OctoColor.error)
                }
            }
            .padding(.horizontal, 20)
        }
    }

    private func openFull(_ model: OctoAdminModel) -> some View {
        Button {
            if let url = model.pageUrl { openURL(url) }
        } label: {
            HStack {
                Text("Open full admin").font(.octoBody).foregroundStyle(OctoColor.textPrimary)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 15, weight: .semibold))
                    .frame(width: 22, height: 22)
                    .foregroundStyle(OctoColor.textMuted)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 20)
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.top, 18)
    }
}

/// One service's health: its dot, its name and what it says. With `onPass`, a quiet "Pass on
/// the dashboard" under the words, lined up with them, opens the full admin pages where the fix is.
private struct HealthRow: View {
    let name: String
    let health: AdminHealth
    var onPass: (() -> Void)?

    private static let dotSize: CGFloat = 8
    private static let dotGap: CGFloat = 14
    /// How far a QuietButton's words sit inside its edge.
    private static let quietInset: CGFloat = 10

    var body: some View {
        let dot = healthDot(health)
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: Self.dotGap) {
                Circle()
                    .fill(color(dot))
                    .frame(width: Self.dotSize, height: Self.dotSize)
                    .padding(.top, 6)
                    .accessibilityLabel(word(dot))
                VStack(alignment: .leading, spacing: 2) {
                    Text(name).font(.octoBodySmall).foregroundStyle(dot == .notSetUp ? OctoColor.textMuted : OctoColor.textPrimary)
                    let detail = dot == .notSetUp ? "Not set up" : health.detail
                    if !detail.trimmingCharacters(in: .whitespaces).isEmpty {
                        Text(detail).font(.octoCaption).foregroundStyle(OctoColor.textMuted).lineLimit(3)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .accessibilityElement(children: .combine)
            if let onPass {
                // Its own element, so the row's words stay one and the button stays a button.
                QuietButton(passOnDashboardText, action: onPass)
                    .padding(.leading, Self.dotSize + Self.dotGap - Self.quietInset)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 8)
    }

    private func color(_ dot: HealthDot) -> AnyShapeStyle {
        switch dot {
        case .ok: AnyShapeStyle(Color(hex: 0x8FBFA0))
        case .warning: AnyShapeStyle(Color(hex: 0xE3B35B))
        case .down: AnyShapeStyle(OctoColor.error)
        case .notSetUp: AnyShapeStyle(OctoColor.accent.opacity(0.3))
        }
    }

    private func word(_ dot: HealthDot) -> String {
        switch dot {
        case .ok: "Working"
        case .warning: "Needs a look"
        case .down: "Not working"
        case .notSetUp: "Not set up"
        }
    }
}

/// A song Octo downloaded: its cover, title, who it is by and the kind of file, and when.
private struct DownloadRow: View {
    let record: DownloadRecord
    let nowMs: Int64
    /// The server in use, which the server's own covers come through.
    let client: SubsonicClient?
    /// Where the admin pages answer.
    let base: URL?

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 6).fill(OctoColor.backgroundTertiary)
                if let cover = record.coverArtUrl, let url = URL(string: cover) {
                    DownloadCover(url: url, client: client, base: base)
                        .accessibilityHidden(true)
                }
            }
            .frame(width: 44, height: 44)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 1) {
                Text(record.title).font(.octoBodySmall).foregroundStyle(OctoColor.textPrimary).lineLimit(1)
                Text(downloadLine(record)).font(.octoCaption).foregroundStyle(OctoColor.textMuted).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let when = adminAgo(record.downloadedAt, nowMs: nowMs) {
                Text(when).font(.octoCaption).foregroundStyle(OctoColor.textMuted)
            }
        }
        .padding(.horizontal, 20)
        .frame(minHeight: 56)
    }
}

/// A fetched song's cover. One at the server's own addresses, or where its admin pages answer,
/// comes through the server's connection (its headers and its sign-in page's cookies, a sign-in
/// page refused, never shown); any other comes from where it is, with nothing of the server's.
private struct DownloadCover: View {
    let url: URL
    let client: SubsonicClient?
    let base: URL?
    @State private var picture: UIImage?

    var body: some View {
        if let client, isServerPicture(url, client: client, adminBase: base) {
            Color.clear
                .overlay {
                    if let picture { Image(uiImage: picture).resizable().scaledToFill() }
                }
                .task(id: url) { picture = await Self.load(url, client: client) }
        } else {
            AsyncImage(url: url) { image in image.resizable().scaledToFill() } placeholder: { Color.clear }
        }
    }

    private static func load(_ url: URL, client: SubsonicClient) async -> UIImage? {
        guard let (data, _) = try? await ServerExchange.media(for: URLRequest(url: url), client: client, endpoint: "downloads/cover")
        else { return nil }
        return UIImage(data: data)
    }
}

/// Where the admin pages were found, kept per server on the phone, and who is asking.
@MainActor
enum OctoAdminPages {
    static let shared = OctoAdmin(store: AdminPrefsStore())

    static func signIn(_ session: Session) -> AdminSignIn? {
        guard let client = session.client, let server = session.active else { return nil }
        return AdminSignIn(server: server.id, client: client, primary: server.address, keptServers: session.servers.count)
    }
}

/// The "octo_admin" store for the addresses found.
struct AdminPrefsStore: AdminAddressStore {
    private let prefs = Prefs("octo_admin")

    func string(_ key: String) -> String? { prefs.string(key) }
    func set(_ value: String?, _ key: String) { prefs.set(value, key) }
}
