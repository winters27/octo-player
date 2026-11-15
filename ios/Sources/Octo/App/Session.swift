import Foundation
import Network
import Observation
import OctoKit
import UIKit

/// The servers kept on this phone and the one in use. Octo works with no server at all (the
/// music on the phone); a server is an add-on whose music joins the library. Several can be
/// kept, with one in use: signing in, adding another, switching between them, editing, signing
/// out and removing all go through here. Secrets live only in the keychain.
@MainActor
@Observable
final class Session {
    private(set) var servers: [StoredServer] = []
    private(set) var activeId: String?
    private(set) var client: SubsonicClient?
    private(set) var profile: ServerProfile?
    /// True while the home network address is the one in use.
    private(set) var atHome = false
    /// Counts up each time the connection in use is made anew: another server, a changed
    /// connection, another music folder, or extensions read again.
    private(set) var generation = 0
    var quality: StreamQuality {
        didSet { Self.store(quality) }
    }
    /// A host of the server in use showing another certificate than the one trusted there,
    /// waiting for the person's answer (`MainShell` asks); one at a time.
    private(set) var certificateChange: ServerTrust.Untrusted?

    /// Runs before the server in use changes to another (its id) or none, while the old one is
    /// still the one in use: what plays stops and counts for it, and its queue is put away.
    @ObservationIgnored var leaving: (@MainActor (_ from: StoredServer, _ to: String?) async -> Void)?

    var active: StoredServer? { servers.first { $0.id == activeId } }
    var isOcto: Bool { profile?.isOcto ?? active?.isOcto ?? false }
    var serverName: String { active?.name ?? "" }

    /// The extensions the server in use lists, as last read.
    var extensions: [String] { active?.extensions ?? profile?.extensions ?? [] }

    /// Whether the server in use runs Octo: its status page answered at sign-in, it calls itself
    /// Octo, or it lists an Octo extension.
    var runsOcto: Bool {
        guard let active else { return false }
        return isOctoServer(probed: active.isOcto, serverType: active.serverType ?? profile?.info.type, extensions: extensions)
    }

    /// Whether the server in use has Family on.
    var family: Bool { client != nil && extensions.contains("\(octoFamily):1") }

    /// Where the server in use is reached from, when it has a home address.
    var place: ServerPlace? {
        guard client != nil, let server = active, Self.homeAddress(of: server) != nil else { return nil }
        return atHome ? .home : .away
    }

    @ObservationIgnored private let trust = ServerTrust.shared
    @ObservationIgnored private let network = NWPathMonitor()
    @ObservationIgnored private var onLocalNetwork = false
    /// The network last seen, nil before the first and after one was lost.
    @ObservationIgnored private var seenNetwork: SeenNetwork?
    @ObservationIgnored private var lastFailureCheck = Date.distantPast
    /// The looks at the home address running now, and whether another was asked for meanwhile.
    @ObservationIgnored private var checks: Task<Void, Never>?
    @ObservationIgnored private var checkAgain = false
    @ObservationIgnored private let prefs = UserDefaults.standard
    /// When the app started, for anything that waits on the saved sign-in only as it comes back.
    @ObservationIgnored let startedAt = Date()
    /// What app start left for the person: the sign-in form of the server that was in use, when it
    /// was not signed in to again, with why. Taken once (`takeStartAsk`).
    @ObservationIgnored private var startAsk: SwitchResult?

    private static let serversKey = "servers"
    private static let activeKey = "servers.active"
    private static let deviceKey = "device.id"
    private static let qualityKey = "stream.quality"
    private static let sharedCookiesKey = "cookies.shared.cleared"

    /// The app's version from its Info.plist, readable from any thread.
    nonisolated static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    init() {
        quality = Self.storedQuality()
        load()
        forgetSharedCookies()
        network.pathUpdateHandler = { [weak self] path in
            let now = SeenNetwork(path)
            Task { @MainActor in self?.networkChanged(now) }
        }
        network.start(queue: DispatchQueue(label: "app.winters.octo.network"))
        trust.onConnectionFailure { [weak self] in
            Task { @MainActor in self?.callFailedToConnect() }
        }
        trust.onCertificateChange { [weak self] question in
            Task { @MainActor in
                guard let self, self.client != nil, self.certificateChange == nil else { return }
                self.certificateChange = question
            }
        }
        restore()
    }

    // MARK: Signing in

    /// Everything the sign-in page collects.
    struct Form {
        var address: URL
        var homeAddress: URL?
        /// The name to give the server; nil keeps the saved one, and empty clears it.
        var label: String?
        var username: String
        /// Empty keeps the saved one, when editing.
        var secret: String
        var mode: AuthMode
        /// The header rows; a saved row left empty keeps its saved value.
        var headers: [HeaderRules.Row]
        /// The sign-in page fields.
        var gateway = GatewayDraft()
        var certificate: (data: Data, password: String)?
        var removesCertificate = false
        /// The picked certificate file's name, to show on the edit form later.
        var certificateName: String? = nil
    }

    enum Purpose: Equatable {
        /// Signs in and makes the server the one in use.
        case signIn
        /// Keeps another server without leaving the one in use.
        case add
        /// Changes a kept server; the one in use is signed in to again.
        case edit(String)
        /// Signs in to a kept server from its own form (signed out of, a refused password, a
        /// sign-in page in front of it, https asked for) and makes it the one in use: the same
        /// server, whatever its address is now.
        case signInAgain(String)

        /// The kept server the form was opened for, nil for a new one.
        var serverId: String? {
            switch self {
            case .edit(let id), .signInAgain(let id): id
            case .signIn, .add: nil
            }
        }
    }

    /// What a finished keep came to: the server kept, and whether its main address changed.
    struct Kept {
        var server: StoredServer
        var addressChanged: Bool
    }

    /// Checks the server and keeps it. Throws `SignInFailure` with words, or the certificate
    /// question as `ServerTrust.Untrusted`.
    @discardableResult
    func keep(_ form: Form, for purpose: Purpose) async throws -> String? {
        let kept = try await keepServer(form, for: purpose)
        switch purpose {
        case .add: return "Added \(kept.server.name). Switch to it whenever you like."
        case .edit: return "Saved \(kept.server.name)."
        case .signIn, .signInAgain: return nil
        }
    }

    func keepServer(_ form: Form, for purpose: Purpose) async throws -> Kept {
        let main = form.address
        let home = form.homeAddress.flatMap { $0 == main ? nil : $0 }
        let username = form.username.trimmingCharacters(in: .whitespacesAndNewlines)
        let before: StoredServer?
        switch keepBase(formFor: purpose.serverId, address: main, username: username, servers: servers) {
        case .gone: throw SignInFailure.words(SignInWords.gone)
        case .alreadyKept(let twin): throw SignInFailure.words(SignInWords.alreadyKept(twin.name))
        case .kept(let server): before = server
        case .new: before = nil
        }

        var secret = form.secret
        if secret.isEmpty, let before, Self.sameKind(before.mode, form.mode), !before.signedOut {
            secret = Keychain.read(for: Self.secretAccount(before.id)) ?? ""
        }
        guard !secret.isEmpty else { throw SignInFailure.words(SignInWords.missingSecret) }

        // The rows and the sign-in page fields, read against the saved values.
        let headers: [String: String]
        switch resolveConnectionHeaders(rows: form.headers, gateway: form.gateway, saved: before.map { Self.headers(for: $0.id) } ?? [:],
                                        mainHttps: main.scheme?.lowercased() == "https", mainPrivate: ServerAddress.isPrivate(main)) {
        case .ready(let made): headers = made
        case .problem(let text): throw SignInFailure.words(text)
        }
        // The server is asked through a session of its own, with its own client certificate, so
        // the one in use keeps its own until this one comes into use.
        let own = try ownSession(main: main, home: home, picked: form.certificate,
                                 saved: form.certificate == nil && !form.removesCertificate ? before : nil, seconds: 30)
        defer { own.finishTasksAndInvalidate() }

        let use: Bool = switch purpose {
        case .signIn, .signInAgain: true
        case .add: false
        case .edit(let id): id == activeId && client != nil
        }
        var credentials = Credentials(username: username, password: secret, mode: form.mode)
        var client = makeClient(main, home: home, credentials, headers, session: own)
        let profile: ServerProfile
        do {
            let reached = try await reach(client, main: main, home: home)
            client = reached.0
            profile = reached.1
            // A home address with a certificate the phone does not trust is asked about now.
            if let home, client.baseURL != home {
                do {
                    _ = try await client.at(home, timeout: 2).ping()
                } catch {
                    if case .unreachable = SubsonicError(error), let question = trust.rejection(for: home.host) { throw question }
                }
            }
        } catch {
            restoreSecurity()
            throw error
        }
        if form.mode == .apiKey, let owner = try? await client.tokenInfo(), !owner.isEmpty {
            credentials = credentials.named(owner)
            client = client.with(credentials)
        }
        let probed = await Self.probeOcto(client)
        let taken = Set(servers.map(\.id)).subtracting([before?.id].compactMap { $0 })
        let id = keptServerId(before: before, username: credentials.username, address: main, taken: taken)
        let label = form.label.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) } ?? before?.label
        let keepsCertificate = form.certificate != nil || (before?.hasClientCertificate == true && !form.removesCertificate)
        let certificateName = form.certificate != nil ? form.certificateName : keepsCertificate ? before?.clientCertificateName : nil
        let server = StoredServer(
            id: id, label: label, address: main, homeAddress: home,
            username: credentials.username, mode: form.mode, headerNames: Array(headers.keys).sorted(),
            hasClientCertificate: keepsCertificate, clientCertificateName: certificateName,
            serverType: profile.info.type, serverVersion: profile.info.serverVersion,
            isOcto: probed, extensions: profile.extensions)
        // The secrets go under the server's id first: every old value read before anything is
        // written, and put back when a write fails. The old id's are let go only once the list
        // naming the new id is saved.
        let change: CertificateKeep = if let certificate = form.certificate {
            .picked(data: certificate.data, password: certificate.password)
        } else if form.removesCertificate {
            .removed
        } else {
            .unchanged
        }
        let encodedHeaders = try JSONEncoder().encode(headers)
        let forgetOld: String?
        do {
            forgetOld = try keepSecrets(KeychainItems(), from: before?.id, to: id, carriesCertificate: before?.hasClientCertificate == true,
                                        secret: secret, headers: encodedHeaders, certificate: change, names: Self.secretNames)
        } catch let failure as KeepSecretsError {
            restoreSecurity()
            throw SignInFailure.words(failure.words)
        }
        let addressChanged = before.map { $0.address != main } ?? false
        if addressChanged { prefs.removeObject(forKey: Self.folderKey(id)) }

        guard use else {
            restoreSecurity()
            servers = serversAfterKeep(servers, before: before, now: server)
            save()
            if let forgetOld { forgetSecrets(forgetOld) }
            return Kept(server: server, addressChanged: addressChanged)
        }
        let from = self.client == nil ? nil : active.flatMap { $0.id == id || $0.id == before?.id || $0.signedOut ? nil : $0 }
        if let from { await leaving?(from, id) }
        servers = serversAfterKeep(servers, before: before, now: server)
        activate(server, credentials: client.credentials, headers: headers, profile: profile)
        if let forgetOld { forgetSecrets(forgetOld) }
        return Kept(server: server, addressChanged: addressChanged)
    }

    /// Gives a kept server a name of its own; an empty one shows its host.
    func rename(_ id: String, label: String) {
        guard let index = servers.firstIndex(where: { $0.id == id }) else { return }
        servers[index].label = label.trimmingCharacters(in: .whitespacesAndNewlines)
        save()
    }

    // MARK: Switching

    /// How moving to another kept server went.
    enum SwitchResult: Equatable {
        case done
        /// Its password is not kept here, or the server no longer takes it; the note says why,
        /// when there is something to say.
        case needsPassword(StoredServer, String?)
        case failed(String)
    }

    /// How long a kept server that is not the one in use has to answer, connecting included.
    private static let glanceSeconds: TimeInterval = 6

    /// Makes another kept server the one in use, signed in with its saved secret. It is asked
    /// first with a session of its own, so one out of reach leaves everything as it was.
    private func switchServer(_ id: String) async -> SwitchResult {
        guard let server = servers.first(where: { $0.id == id }) else { return .failed(SignInWords.gone) }
        let from = client == nil ? nil : active
        if from?.id == id { return .done }
        guard !server.signedOut, let secret = Keychain.read(for: Self.secretAccount(id)), !secret.isEmpty else {
            return .needsPassword(server, nil)
        }
        // A saved connection that can no longer be opened is asked for again.
        let home = Self.homeAddress(of: server)
        guard let headers = Self.savedHeaders(of: server),
              let own = try? ownSession(main: server.address, home: home, saved: server, strict: true,
                                        seconds: Self.glanceSeconds)
        else { return .needsPassword(server, nil) }
        defer { own.finishTasksAndInvalidate() }
        let credentials = Credentials(username: server.username, password: secret, mode: server.mode)
        let asked = makeClient(server.address, home: home, credentials, headers, session: own)
        // A sign-in page's password kept for a plain http address across the internet is never
        // sent: the form opens and asks for https.
        if needsHttps(headers, scope: asked.scope) { return .needsPassword(server, GatewayWords.needsHttps) }
        let reached: (SubsonicClient, ServerProfile)
        do {
            reached = try await reach(asked, main: server.address, home: server.homeAddress)
        } catch {
            if case .wrongCredentials = SubsonicError(error) {
                return .needsPassword(server, serverSwitchPasswordRefusedWords(server.name))
            }
            // A sign-in page in front of the server opens the form with its words, where its sign-in
            // page fields are filled in.
            if SubsonicError(error).gateway != nil { return .needsPassword(server, userMessage(plainError(error))) }
            // A certificate refused while switching reads as the server being out of reach.
            let why = error is ServerTrust.Untrusted ? SignInWords.unreachable : userMessage(plainError(error))
            return .failed(serverSwitchFailedWords(server.name, why: why))
        }
        var fresh = server
        fresh.serverType = reached.1.info.type ?? server.serverType
        fresh.serverVersion = reached.1.info.serverVersion ?? server.serverVersion
        if !reached.1.extensions.isEmpty { fresh.extensions = reached.1.extensions }
        if let from, !from.signedOut { await leaving?(from, id) }
        if let index = servers.firstIndex(where: { $0.id == id }) { servers[index] = fresh }
        activate(fresh, credentials: credentials, headers: headers, profile: reached.1)
        return .done
    }

    /// Uses a kept server, checking it first with its saved password. The servers list switches
    /// through `ServerSwitch`, which says how it went.
    func switchTo(_ id: String) async throws {
        switch await switchServer(id) {
        case .done: return
        case .needsPassword(let server, let note): throw SignInFailure.needsPassword(server.id, note)
        case .failed(let words): throw SignInFailure.words(words)
        }
    }

    /// How a kept server answers a quick look, through a session of its own (its own headers,
    /// trusted certificates and client certificate). One signed out of is not asked, and neither
    /// is one kept with a sign-in page's password for a plain http address across the internet,
    /// since that password never goes: its row asks for https.
    func check(_ id: String) async -> ServerCheck {
        guard let server = servers.first(where: { $0.id == id }) else { return ServerCheck(.unknown) }
        var own: URLSession?
        defer { own?.finishTasksAndInvalidate() }
        let asked: SubsonicClient
        let headers: [String: String]
        if id == activeId, let client {
            asked = client
            headers = Self.headers(for: id)
        } else {
            let home = Self.homeAddress(of: server)
            guard !server.signedOut, let secret = Keychain.read(for: Self.secretAccount(id)), !secret.isEmpty,
                  let saved = Self.savedHeaders(of: server),
                  let session = try? ownSession(main: server.address, home: home, saved: server, strict: true,
                                                seconds: Self.glanceSeconds)
            else { return ServerCheck(.unknown) }
            own = session
            headers = saved
            asked = makeClient(server.address, home: home, Credentials(username: server.username, password: secret, mode: server.mode),
                               saved, session: session)
        }
        if needsHttps(headers, scope: asked.scope) { return ServerCheck(.needsHttps) }
        let start = Date()
        do {
            _ = try await asked.ping()
            return ServerCheck(.answers, Int64(Date().timeIntervalSince(start) * 1_000))
        } catch {
            // A refused password, a sign-in page in front of the server, or out of reach; never a
            // sign-out.
            return ServerCheck(reachOf(error))
        }
    }

    // MARK: Signing out and removing

    /// Signs out of a kept server, the one in use when `id` is nil. It stays in the list without
    /// its password, so signing in again needs only that; its header values stay. What plays is
    /// put away first by `ServerSwitch`, which the pages sign out through.
    func signOut(_ id: String? = nil) {
        guard let id = id ?? activeId, let index = servers.firstIndex(where: { $0.id == id }) else { return }
        servers[index].signedOut = true
        Keychain.delete(for: Self.secretAccount(id))
        if id == activeId { leaveSession() }
        save()
    }

    /// Takes a server off the list, with its password. Its music and playlists stay on the server.
    /// Answers the server removed, if it was kept. `ServerSwitch` puts away what plays first and
    /// removes its copy of the library after.
    @discardableResult
    func remove(_ id: String) -> StoredServer? {
        guard let server = servers.first(where: { $0.id == id }) else { return nil }
        servers.removeAll { $0.id == id }
        forgetSecrets(id)
        prefs.removeObject(forKey: Self.folderKey(id))
        if id == activeId { leaveSession() }
        save()
        return server
    }

    private func leaveSession() {
        activeId = nil
        client = nil
        profile = nil
        atHome = false
        restoreSecurity()
    }

    /// Saved custom header names for a server, to show on the edit form: all but the ones that
    /// make up its sign-in page fields (`gatewayKind`), which show as those fields.
    func savedHeaderNames(_ id: String) -> [String] {
        let saved = Self.headers(for: id)
        let fields = savedGatewayNames(saved, kind: OctoKit.savedGatewayKind(saved))
        return saved.keys.filter { !fields.contains($0) }.sorted()
    }

    /// The kind of sign-in page fields a kept server's saved headers make up, read from their
    /// values; the values themselves stay in the keychain.
    func gatewayKind(_ id: String) -> GatewayKind {
        OctoKit.savedGatewayKind(Self.headers(for: id))
    }

    /// Whether the server in use keeps a sign-in page's header, so a sign-in page stands in front
    /// of it.
    var behindSignInPage: Bool {
        active?.headerNames.contains(where: GatewayNames.isGatewayName) ?? false
    }

    /// A client for a family link's address, made from the sign-in form: the form's headers (the
    /// sign-in page fields among them), the certificates trusted here and the one picked, and the
    /// home address in its scope, through a session of its own. It carries no sign-in. The caller
    /// ends its session with `finishTasksAndInvalidate()` once done. Throws when the certificate
    /// cannot be opened.
    func linkClient(at address: URL, home: URL?, headers: [String: String],
                    certificate: (data: Data, password: String)?) throws -> SubsonicClient {
        let own = try ownSession(main: address, home: home, picked: certificate, saved: nil, seconds: 30)
        return makeClient(address, home: home, Credentials(username: "", password: ""), headers, session: own)
    }

    // MARK: The server in use

    /// The signed-in account's change of password, one at a time from every form that sends one
    /// (Settings' Server page, Family's sheet): it outlives the form it came from.
    @ObservationIgnored let passwordChanges = PasswordChanges()

    /// Changes the signed-in user's password on the server. The current one is checked first; on
    /// success the new one is kept in place of the old, and the client signs in with it.
    func changePassword(current: String, new: String, family: Bool = false) async -> PasswordChange {
        guard let client, let id = activeId else { return .failed("Connect a server first.") }
        let result = await changeOwnPassword(client, current: current, new: new, family: family)
        guard result == .changed else { return result }
        // An update in place that fails would leave the old password, which the server no longer
        // takes; with none kept, Octo asks for it next time.
        if !Keychain.save(new, for: Self.secretAccount(id)) { Keychain.delete(for: Self.secretAccount(id)) }
        if self.activeId == id, let now = self.client {
            self.client = now.with(Credentials(username: now.credentials.username, password: new, mode: now.credentials.mode))
        }
        return result
    }

    /// How quickly the server in use answers, in milliseconds, and how its folders scan stands.
    func look() async -> (answerMs: Int64?, scan: ServerScanStatus?) {
        guard let client else { return (nil, nil) }
        let start = Date()
        let answered = (try? await client.ping()) != nil
        let ms = answered ? Int64(Date().timeIntervalSince(start) * 1_000) : nil
        return (ms, try? await client.serverScanStatus())
    }

    /// A session of its own for the server in use: the same addresses, trusted certificates and
    /// client certificate, no connection kept from earlier calls, and at most `seconds` for each
    /// call. For asking a server that may be restarting, where a connection kept alive can point at
    /// what is no longer there. The caller ends it with `finishTasksAndInvalidate()`.
    func freshSession(seconds: TimeInterval) -> URLSession? {
        guard client != nil, let server = active else { return nil }
        return try? ownSession(main: server.address, home: Self.homeAddress(of: server), saved: server, seconds: seconds)
    }

    /// Reads the server in use's kind, version and extensions again, as after it was updated.
    func readServerAgain() async {
        guard let client, let id = activeId, let read = try? await client.profile(), activeId == id else { return }
        profile = read
        refreshDetails(read, for: id)
    }

    /// The music folder the library is limited to on a kept server, when one is chosen.
    func folder(for id: String) -> (id: String, name: String)? {
        guard let saved = prefs.array(forKey: Self.folderKey(id)) as? [String], saved.count == 2 else { return nil }
        return (saved[0], saved[1])
    }

    /// Keeps the chosen folder, nil for all of them. For the server in use, the connection is made
    /// anew, so every library call from now on carries it.
    func setFolder(_ folder: ServerMusicFolder?, for id: String) {
        if let folder { prefs.set([folder.id, folder.name], forKey: Self.folderKey(id)) } else { prefs.removeObject(forKey: Self.folderKey(id)) }
        guard id == activeId, let client else { return }
        self.client = client.limited(to: folder?.id)
        generation += 1
    }

    // MARK: Connecting

    /// At app start, the server in use signs in again with its saved secret. One whose secret can
    /// no longer be read stays in the list signed out, and nothing is in use. One kept with a
    /// sign-in page's password for a plain http address across the internet is never signed in to,
    /// since that password never goes: nothing is in use, and its sign-in form opens asking for
    /// https, as a switch to it does.
    private func restore() {
        guard let server = active else { return }
        let secret = server.signedOut ? "" : Keychain.read(for: Self.secretAccount(server.id)) ?? ""
        let home = Self.homeAddress(of: server)
        let headers = Self.headers(for: server.id)
        switch serverRestore(signedOut: server.signedOut, hasSecret: !secret.isEmpty, headers: headers,
                             scope: ServerScope(main: server.address, home: home)) {
        case .leave:
            activeId = nil
            save()
            return
        case .signOut:
            if let index = servers.firstIndex(where: { $0.id == server.id }) { servers[index].signedOut = true }
            activeId = nil
            save()
            return
        case .needsHttps:
            activeId = nil
            save()
            startAsk = .needsPassword(server, GatewayWords.needsHttps)
            return
        case .signIn:
            break
        }
        useSecurity(of: server)
        client = makeClient(server.address, home: home,
                            Credentials(username: server.username, password: secret, mode: server.mode),
                            headers).limited(to: folder(for: server.id)?.id)
        generation += 1
        let id = server.id
        Task {
            await chooseAddress()
            guard let client, activeId == id, let read = try? await client.profile() else { return }
            profile = read
            refreshDetails(read, for: id)
        }
    }

    /// What app start left for the person, once: the sign-in form to open, with why. Nil when
    /// there is nothing, or once it was taken.
    func takeStartAsk() -> SwitchResult? {
        defer { startAsk = nil }
        return startAsk
    }

    /// A server is updated over time, so each start asks again: its kind and version, and its
    /// extensions when it lists some and they differ from the saved ones.
    private func refreshDetails(_ read: ServerProfile, for id: String) {
        guard activeId == id, let index = servers.firstIndex(where: { $0.id == id }) else { return }
        var server = servers[index]
        if let type = read.info.type, !type.isEmpty { server.serverType = type }
        if let version = read.info.serverVersion, !version.isEmpty { server.serverVersion = version }
        let extensionsChanged = !read.extensions.isEmpty && Set(read.extensions) != Set(server.extensions)
        if extensionsChanged { server.extensions = read.extensions }
        guard server != servers[index] else { return }
        servers[index] = server
        if extensionsChanged { generation += 1 }
        save()
    }

    /// Makes a server the one in use: its client certificate and addresses for the shared
    /// session, a client at its main address limited to its music folder, and a fresh look at
    /// its home address. Each new connection starts away from home until that look says otherwise.
    private func activate(_ server: StoredServer, credentials: Credentials, headers: [String: String], profile: ServerProfile) {
        activeId = server.id
        useSecurity(of: server)
        client = makeClient(server.address, home: Self.homeAddress(of: server), credentials, headers).limited(to: folder(for: server.id)?.id)
        self.profile = profile
        atHome = false
        generation += 1
        save()
        recheck()
    }

    // MARK: Home or away

    /// The home address worth looking at: set, and not the main address.
    private static func homeAddress(of server: StoredServer) -> URL? {
        server.homeAddress.flatMap { $0 == server.address ? nil : $0 }
    }

    /// The phone's network changed. A new network, or a move between local and not local, is
    /// looked at again, and so is losing the network; the same network seen again is not.
    private func networkChanged(_ now: SeenNetwork?) {
        onLocalNetwork = now?.local ?? false
        guard let now else {
            seenNetwork = nil
            recheck()
            return
        }
        guard now != seenNetwork else { return }
        seenNetwork = now
        recheck()
    }

    /// Asks for a look at the home address, when the server in use has one. One look runs at a
    /// time; asks that come in meanwhile fold into one more.
    private func recheck() {
        guard client != nil, let server = active, Self.homeAddress(of: server) != nil else { return }
        if checks != nil {
            checkAgain = true
            return
        }
        checks = Task { [weak self] in
            guard let self else { return }
            repeat {
                self.checkAgain = false
                await self.lookAtHome()
            } while self.checkAgain
            self.checks = nil
        }
    }

    /// Looks at the home address now, and waits for the looks running to end.
    func chooseAddress() async {
        recheck()
        await checks?.value
    }

    /// On Wi-Fi or Ethernet the home network address is used when it answers within two
    /// seconds; anywhere else, or when it does not answer, the main address.
    private func lookAtHome() async {
        guard let server = active, let client, let home = Self.homeAddress(of: server) else { return }
        let local = onLocalNetwork
        var answered = false
        if local { answered = await Self.answers(client.at(home, timeout: 2)) }
        // The server in use may have changed while the home address was asked.
        guard let now = self.client, activeId == server.id else { return }
        let place = choosePlace(hasHome: true, onLocalNetwork: local, homeAnswered: answered)
        let target = place == .home ? home : server.address
        if target != now.baseURL { self.client = now.at(target) }
        atHome = place == .home
    }

    /// The answer to a changed certificate: trusted, its pin is kept for its host and the next
    /// call goes through; said no to, it is not asked about again and the server stays out of
    /// reach until it is signed in to again.
    func answerCertificateChange(trusted: Bool) {
        guard let question = certificateChange else { return }
        trust.answerChange(question, trusted: trusted)
        certificateChange = nil
    }

    /// After a call to the server could not connect: looks at the home address again, at most
    /// once every five seconds.
    func callFailedToConnect() {
        guard client != nil, let server = active, Self.homeAddress(of: server) != nil,
              Date().timeIntervalSince(lastFailureCheck) > 5 else { return }
        lastFailureCheck = Date()
        recheck()
    }

    /// Whether an address answers like the server: an error from the server itself still means
    /// it is there; only no connection, or not a music server, is a no.
    private static func answers(_ client: SubsonicClient) async -> Bool {
        do {
            _ = try await client.ping()
            return true
        } catch {
            switch SubsonicError(error) {
            case .unreachable, .notSubsonic: return false
            default: return true
            }
        }
    }

    /// A client for a server at its main address, through the shared session of the server in
    /// use unless given one of its own. Its scope is the main and home addresses (a sign-in page's
    /// headers go only to its gateway addresses), and it starts with a cookie store of its own.
    private func makeClient(_ address: URL, home: URL?, _ credentials: Credentials, _ headers: [String: String],
                            session: URLSession? = nil) -> SubsonicClient {
        SubsonicClient(baseURL: address, credentials: credentials, device: Self.device(), appVersion: Self.appVersion,
                       customHeaders: headers, session: session ?? trust.session, scope: ServerScope(main: address, home: home))
    }

    /// A session of its own for a server being asked: its main and home addresses, and the client
    /// certificate picked now (which has to open) or else its saved one. A saved one that can no
    /// longer be opened is left out, unless `strict`, when that throws too.
    private func ownSession(main: URL, home: URL?, picked: (data: Data, password: String)? = nil, saved: StoredServer?,
                            strict: Bool = false, seconds: TimeInterval? = nil) throws -> URLSession {
        if let picked { return try trust.ownSession(main: main, home: home, certificate: picked, seconds: seconds) }
        if let saved, saved.hasClientCertificate {
            do {
                guard let kept = Self.savedCertificate(saved.id) else { throw ClientCertificateError.unreadable }
                return try trust.ownSession(main: main, home: home, certificate: kept, seconds: seconds)
            } catch {
                if strict { throw error }
            }
        }
        return try trust.ownSession(main: main, home: home, certificate: nil, seconds: seconds)
    }

    /// Pings the main address and reads extensions; when it cannot connect and there is a home
    /// address, the home one instead. An untrusted certificate becomes a question for the person.
    private func reach(_ client: SubsonicClient, main: URL, home: URL?) async throws -> (SubsonicClient, ServerProfile) {
        do {
            return (client, try await client.profile())
        } catch let error as URLError {
            if isCertificateFailure(error, host: main.host) {
                if let question = trust.rejection(for: main.host) { throw question }
                throw error
            }
            guard let home else { throw error }
            let atHome = client.at(home)
            do {
                return (atHome, try await atHome.profile())
            } catch let homeError as URLError where isCertificateFailure(homeError, host: home.host) {
                if let question = trust.rejection(for: home.host) { throw question }
                throw error
            } catch {
                throw error
            }
        }
    }

    /// A refused certificate shows as the call being cancelled, since the trust check cancels it.
    private func isCertificateFailure(_ error: URLError, host: String?) -> Bool {
        SubsonicError.isCertificate(error) || (error.code == .cancelled && trust.rejection(for: host) != nil)
    }

    /// Octo's own page answers with an "octo" key; only on the home network, usually.
    private static func probeOcto(_ client: SubsonicClient) async -> Bool {
        let request = URLRequest(url: client.baseURL.appendingPathComponent("api/admin/status"), timeoutInterval: 5)
        guard let answer = try? await ServerExchange.answer(for: request, client: client), answer.isOK,
              let json = try? JSONSerialization.jsonObject(with: answer.data) as? [String: Any]
        else { return false }
        return json["octo"] != nil
    }

    private static func savedCertificate(_ id: String) -> (data: Data, password: String)? {
        guard let data = Keychain.readData(for: certificateAccount(id)) else { return nil }
        return (data, Keychain.read(for: certificatePasswordAccount(id)) ?? "")
    }

    /// The shared session serves this server from now on, none for nil: its addresses, and its
    /// client certificate, or no certificate at all when it has none (one left from another
    /// server is never offered to it).
    private func useSecurity(of server: StoredServer?) {
        guard let server else {
            trust.useServer(main: nil, home: nil)
            try? trust.useClientCertificate(nil, password: "")
            return
        }
        trust.useServer(main: server.address, home: Self.homeAddress(of: server))
        if server.hasClientCertificate, let kept = Self.savedCertificate(server.id),
           (try? trust.useClientCertificate(kept.data, password: kept.password)) != nil {
            return
        }
        try? trust.useClientCertificate(nil, password: "")
    }

    /// Puts the security of the server in use back, so a failed or side try never disturbs it.
    private func restoreSecurity() {
        useSecurity(of: client == nil ? nil : active)
    }

    /// A password serves token and legacy sign-in; a key serves only a key.
    private static func sameKind(_ a: AuthMode, _ b: AuthMode) -> Bool {
        (a == .apiKey) == (b == .apiKey)
    }

    // MARK: Storage

    private func load() {
        if let data = prefs.data(forKey: Self.serversKey),
           let kept = try? JSONDecoder().decode([StoredServer].self, from: data) {
            servers = kept
        }
        activeId = prefs.string(forKey: Self.activeKey)
        migrateFirstSignIn()
    }

    /// The first builds kept one server under single keys; it becomes the first kept server.
    private func migrateFirstSignIn() {
        guard servers.isEmpty, let text = prefs.string(forKey: "server.address"), let address = URL(string: text),
              let username = prefs.string(forKey: "server.username")
        else { return }
        let oldAccount = "\(username)@\(ServerAddress.shown(address))"
        let server = StoredServer(id: freshServerId(username: username, address: ServerAddress.written(address), taken: []),
                                  address: address, username: username,
                                  mode: prefs.bool(forKey: "server.legacyPassword") ? .legacyPassword : .token)
        // The old item goes only once the new one is kept.
        if let secret = Keychain.read(for: oldAccount) {
            if Keychain.save(secret, for: Self.secretAccount(server.id)) { Keychain.delete(for: oldAccount) }
        }
        servers = [server]
        activeId = server.id
        for key in ["server.address", "server.username", "server.legacyPassword"] { prefs.removeObject(forKey: key) }
        save()
    }

    private func save() {
        prefs.set(try? JSONEncoder().encode(servers), forKey: Self.serversKey)
        prefs.set(activeId, forKey: Self.activeKey)
    }

    /// Nothing reads the phone's shared cookie store for a server: each connection keeps a
    /// sign-in page's cookies in its own store, in memory. What the shared store holds for the
    /// kept servers' hosts (the server's own admin and family cookies among them) is deleted once,
    /// on the first start that looks.
    private func forgetSharedCookies() {
        guard !prefs.bool(forKey: Self.sharedCookiesKey) else { return }
        let hosts = Set(servers.flatMap { [$0.address.host, $0.homeAddress?.host] }.compactMap { $0?.lowercased() })
        forgetKeptServerCookies(in: HTTPCookieStorage.shared, hosts: hosts)
        prefs.set(true, forKey: Self.sharedCookiesKey)
    }

    private func forgetSecrets(_ id: String) {
        for account in [Self.secretAccount(id), Self.headersAccount(id), Self.certificateAccount(id), Self.certificatePasswordAccount(id)] {
            Keychain.delete(for: account)
        }
    }

    private static func secretAccount(_ id: String) -> String { "server.\(id).secret" }
    private static func headersAccount(_ id: String) -> String { "server.\(id).headers" }
    private static func certificateAccount(_ id: String) -> String { "server.\(id).certificate" }
    private static func certificatePasswordAccount(_ id: String) -> String { "server.\(id).certificate.password" }
    private static func secretNames(_ id: String) -> ServerSecretNames {
        ServerSecretNames(secret: secretAccount(id), headers: headersAccount(id), certificate: certificateAccount(id),
                          certificatePassword: certificatePasswordAccount(id))
    }
    private static func folderKey(_ id: String) -> String { "server.folder@\(id)" }

    private static func headers(for id: String) -> [String: String] {
        Keychain.readData(for: headersAccount(id)).flatMap { try? JSONDecoder().decode([String: String].self, from: $0) } ?? [:]
    }

    /// A kept server's saved headers; nil when it has some and they can no longer be read.
    private static func savedHeaders(of server: StoredServer) -> [String: String]? {
        let read = Keychain.readData(for: headersAccount(server.id)).flatMap { try? JSONDecoder().decode([String: String].self, from: $0) }
        if read == nil, !server.headerNames.isEmpty { return nil }
        return read ?? [:]
    }

    /// This install's id, made once and kept in the keychain for this device only, so a backup
    /// restored to another phone never brings it along. An id kept in the defaults by an
    /// earlier version moves there unchanged.
    private static let installId: String = {
        if let kept = Keychain.read(for: deviceKey), !kept.isEmpty { return kept }
        let defaults = UserDefaults.standard
        let made = defaults.string(forKey: deviceKey).flatMap { $0.isEmpty ? nil : $0 } ?? UUID().uuidString.lowercased()
        Keychain.save(made, for: deviceKey)
        if Keychain.read(for: deviceKey) == made { defaults.removeObject(forKey: deviceKey) }
        return made
    }()

    /// This install, as the server knows it: an id made once, and the phone's name.
    static func device() -> DeviceIdentity {
        DeviceIdentity(id: installId, name: UIDevice.current.name)
    }

    private static func store(_ quality: StreamQuality) {
        let value = switch quality {
        case .original: 0
        case .mp3(let kbps): kbps
        }
        UserDefaults.standard.set(value, forKey: qualityKey)
    }

    private static func storedQuality() -> StreamQuality {
        let value = UserDefaults.standard.integer(forKey: qualityKey)
        return value > 0 ? .mp3(kbps: value) : .original
    }
}

enum SignInFailure: LocalizedError {
    case words(String)
    /// The saved password is missing or did not work; the form opens for this server, with the
    /// note when there is one.
    case needsPassword(String, String?)

    var errorDescription: String? {
        switch self {
        case .words(let text): text
        case .needsPassword(_, let note): note
        }
    }
}

/// What the phone's network is, as far as choosing an address goes: whether it is local (Wi-Fi
/// or a cable), and the interfaces and gateways it goes through. Nil while there is none.
/// Signal changes leave it as it is.
private struct SeenNetwork: Equatable, Sendable {
    let local: Bool
    let interfaces: [String]
    let gateways: [String]

    init?(_ path: NWPath) {
        guard path.status == .satisfied else { return nil }
        local = path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet)
        interfaces = path.availableInterfaces.map(\.name)
        gateways = path.gateways.map { "\($0)" }
    }
}
