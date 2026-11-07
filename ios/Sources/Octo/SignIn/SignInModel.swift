import Foundation
import Observation
import OctoKit

/// The sign-in page's state and rules, the same as the other apps' sign-in: the ordinary form,
/// the server list's forms, and joining a family from an invite or another device's sign-in.
@MainActor
@Observable
final class SignInModel {
    enum Form: Equatable, Identifiable {
        /// Signing in from scratch, or from Settings.
        case signIn
        /// From the server list: keep another server.
        case add
        /// From the server list: change a kept one.
        case edit(StoredServer)
        /// From the server list, or a switch that needs a password: sign in to a kept one again.
        /// The note starts as the form's error line.
        case again(StoredServer, note: String? = nil)
        /// From the Server and sync page: change how the server in use is reached.
        case editConnection(StoredServer)
        /// A family link: an invite, a sign-in to fill in, or a sign-in handed over.
        case join(String)

        var id: String {
            switch self {
            case .signIn: "signIn"
            case .add: "add"
            case .edit(let server): "edit." + server.id
            case .again(let server, _): "again." + server.id
            case .editConnection(let server): "connection." + server.id
            case .join(let link): "join." + link
            }
        }
    }

    struct Header: Identifiable, Equatable {
        let id = UUID()
        var name: String
        var value: String
        /// The value is kept in the keychain and is not shown.
        var saved: Bool
    }

    let form: Form
    var rest = "" {
        didSet {
            typed()
            if rest != oldValue { error = nil }
            // A sign-in page met at another address is forgotten.
            useGatewayForm(addressChanged(gatewayForm, address: sentAddress))
        }
    }
    private(set) var scheme: ServerAddress.Scheme = .https
    private var schemePicked = false
    var label = ""
    var username = ""
    var password = ""
    var apiKey = ""
    var legacy = false
    var useApiKey = false
    /// Whether the server takes API keys: nil until known, which is before signing in, when a
    /// key may still be tried.
    var takesApiKeys: Bool?
    var homeAddress = ""
    var headers: [Header] = []
    /// A certificate chosen on this form, checked but not in use until the server is saved.
    var certificate: (name: String, data: Data, password: String)?
    /// The kept server has a certificate the keychain can still read.
    var hadCertificate = false
    var removesCertificate = false
    /// The saved certificate's file name.
    private(set) var savedCertificateName: String?
    /// The kept server says it has a certificate, but the keychain no longer holds it (a phone
    /// restored from a backup); saving clears that.
    private var staleCertificate = false
    var advancedOpen = false
    /// The kind of sign-in the sign-in page in front of the server takes from apps, and what is
    /// typed for it: a username and password, or a Client ID and Client Secret.
    private(set) var gatewayKind: GatewayKind = .none
    var gatewayFirst = ""
    var gatewaySecond = ""
    /// The kind the saved headers make up; its values stay while its fields are left empty.
    private(set) var gatewaySaved: GatewayKind = .none
    /// The sign-in page the last failed attempt met, and the address it met it at.
    private(set) var gatewayMet: Gateway?
    private var gatewayMetFor: String?

    var busy = false
    var error: String?
    var question: ServerTrust.Untrusted?
    private(set) var notice: String?
    private(set) var signedIn = false
    /// Moves the phone between servers and says how a sign-in's switch went.
    @ObservationIgnored var serverSwitch: ServerSwitch?

    // MARK: Joining a family

    /// The join part shows in place of the username and password.
    private(set) var joining = false
    /// The camera scanner is over the screen.
    var scanning = false
    private(set) var invite: FamilyInviteLink?
    private(set) var handOver: FamilyHandOverLink?
    private(set) var handOverStep: String?
    var joinName = ""
    var joinPassword = ""
    var joinAgain = ""
    /// An account the invite made, kept until it signs in, since an invite works once.
    private var signedUp: (username: String, password: String)?
    /// A sign-in received from another device, kept until signed in with: a hand-over works once,
    /// so a certificate asked about afterwards does not redeem it again.
    private var received: HandOverSignIn?
    private var receiving: Task<Void, Never>?
    /// What the saved server looked like, for telling a rename from a real change.
    private let saved: StoredServer?
    private let savedHeaderNames: [String]

    init(form: Form, session: Session) {
        self.form = form
        saved = form.server
        savedHeaderNames = form.server.map { session.savedHeaderNames($0.id) } ?? []
        guard let server = form.server else { return }
        let (scheme, rest) = ServerAddress.split(ServerAddress.shown(server.address))
        self.rest = rest
        self.scheme = scheme ?? .https
        schemePicked = true
        label = server.label ?? ""
        username = server.username
        legacy = server.mode == .legacyPassword
        useApiKey = server.mode == .apiKey
        homeAddress = server.homeAddress.map(ServerAddress.shown) ?? ""
        headers = savedHeaderNames.map { Header(name: $0, value: "", saved: true) }
        // The saved sign-in page's kind; its values stay in the keychain and are kept while its
        // fields are left empty.
        gatewaySaved = session.gatewayKind(server.id)
        gatewayKind = gatewaySaved
        let certificateKept = server.hasClientCertificate && CertificateCheck.isSaved(forServer: server.id)
        hadCertificate = certificateKept
        staleCertificate = server.hasClientCertificate && !certificateKept
        savedCertificateName = certificateKept ? server.clientCertificateName : nil
        advancedOpen = server.mode != .token || server.homeAddress != nil || !headers.isEmpty || hadCertificate || gatewaySaved != .none
        takesApiKeys = server.takesApiKeys
        if case .again(_, let note) = form { error = note }
    }

    /// Editing a kept server's details, so empty secrets keep the saved ones.
    var editing: Bool {
        switch form {
        case .edit, .editConnection: true
        default: false
        }
    }

    /// The Name field shows only for the server list's Add and Edit.
    var showsName: Bool {
        switch form {
        case .add, .edit: true
        default: false
        }
    }

    /// Scanning a family code is offered only on the plain sign-in screen.
    var offersScan: Bool { form == .signIn }

    /// The heading for the server list's forms; nil shows Octo's name.
    var heading: String? {
        switch form {
        case .signIn, .editConnection, .join: nil
        case .add: "Add a server"
        case .edit(let server): "Edit \(server.name)"
        case .again(let server, _): "Sign in to \(server.name)"
        }
    }

    var subheading: String {
        switch form {
        case .add: return "Keep another server here and switch to it in one tap."
        case .edit: return "Octo signs in with the new details before it saves them."
        case .again(let server, _): return accountLine(username: server.username, address: ServerAddress.written(server.address))
        case .signIn, .editConnection, .join:
            if invite != nil { return "Sign up for your family's server" }
            if handOver != nil { return "Signing in with your other device's login" }
            if joining { return "Scan the QR code from your invite or your other device" }
            if case .editConnection = form { return "Change how Octo connects" }
            return "Sign in to your music server"
        }
    }

    var buttonTitle: String {
        switch form {
        case .signIn, .again, .join: "Sign in"
        case .add: "Add"
        case .edit: "Save"
        case .editConnection: "Save and reconnect"
        }
    }

    var url: URL? { ServerAddress.url(scheme, rest) }

    var ready: Bool {
        guard !busy else { return false }
        return signInReady(hasAddress: url != nil, mode: mode, username: username, password: password, apiKey: apiKey,
                           editing: editing, savedMode: saved?.mode)
    }

    /// The way of signing in the form is set to.
    private var mode: AuthMode { useApiKey ? .apiKey : (legacy ? .legacyPassword : .token) }

    /// The client certificate's name to show, or nil for none.
    var certificateShown: String? {
        if let certificate { return certificate.name }
        guard hadCertificate, !removesCertificate else { return nil }
        return savedCertificateName ?? "Saved certificate"
    }

    /// A scheme typed or pasted moves into the button; until one is picked the scheme follows
    /// the address, http on the home network and https anywhere else.
    private func typed() {
        let (typedScheme, remainder) = ServerAddress.split(rest)
        if let typedScheme {
            scheme = typedScheme
            schemePicked = true
            rest = remainder
            return
        }
        if !schemePicked, !rest.isEmpty { scheme = ServerAddress.automaticScheme(rest) }
    }

    /// What the address field takes: a family link starts the join instead of being typed in.
    func typeAddress(_ text: String, session: Session) {
        if let link = parseFamilyLink(text) {
            startJoin(link, session: session)
        } else {
            rest = text
        }
    }

    func toggleScheme() {
        scheme = scheme == .https ? .http : .https
        schemePicked = true
        error = nil
        useGatewayForm(addressChanged(gatewayForm, address: sentAddress))
    }

    /// How safe the connection is, for the line under the card.
    enum Note: Equatable {
        case encrypted, homeNetwork, legacyInTheOpen, tokenInTheOpen
    }

    var note: Note? {
        guard let url else { return nil }
        if url.scheme == "https" { return .encrypted }
        if ServerAddress.isPrivate(url) { return .homeNetwork }
        return legacy && !useApiKey ? .legacyInTheOpen : .tokenInTheOpen
    }

    func addHeader() {
        headers.append(Header(name: "", value: "", saved: false))
    }

    func renameHeader(_ id: Header.ID, to name: String) {
        guard let index = headers.firstIndex(where: { $0.id == id }) else { return }
        // A renamed saved header no longer has a value to keep.
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if headers[index].saved, trimmed.caseInsensitiveCompare(headers[index].name.trimmingCharacters(in: .whitespaces)) != .orderedSame {
            headers[index].saved = false
        }
        headers[index].name = name
    }

    // MARK: The sign-in page in front of the server

    /// Picks the kind of sign-in the sign-in page takes. Another kind starts with empty fields,
    /// since they mean other things.
    func pickGateway(_ kind: GatewayKind) {
        if kind != gatewayKind {
            gatewayFirst = ""
            gatewaySecond = ""
        }
        gatewayKind = kind
        error = nil
    }

    /// The sign-in page fields as typed, with the kind saved for this server.
    var gatewayDraft: GatewayDraft {
        GatewayDraft(kind: gatewayKind, first: gatewayFirst, second: gatewaySecond, saved: gatewaySaved)
    }

    /// Whether the fields are set for a plain http address across the internet, where they would
    /// cross readable and are never sent.
    var gatewayInsecure: Bool {
        guard gatewayKind != .none, let url else { return false }
        return !gatewayAllowed(mainHttps: url.scheme?.lowercased() == "https", mainPrivate: ServerAddress.isPrivate(url))
    }

    private var gatewayForm: GatewayFormState {
        GatewayFormState(advancedOpen: advancedOpen, kind: gatewayKind, detected: gatewayMet, detectedFor: gatewayMetFor)
    }

    private func useGatewayForm(_ state: GatewayFormState) {
        advancedOpen = state.advancedOpen
        gatewayKind = state.kind
        gatewayMet = state.detected
        gatewayMetFor = state.detectedFor
    }

    /// The address as a sign-in sends it, which what a sign-in met belongs to.
    private var sentAddress: String {
        url?.absoluteString ?? scheme.prefix + rest.trimmingCharacters(in: .whitespaces)
    }

    /// An attempt at `attempt` that met a sign-in page opens Advanced at its fields, unless the
    /// address has changed since.
    private func met(_ attempt: String, _ gateway: Gateway?) {
        useGatewayForm(reactToFailure(gatewayForm, attemptAddress: attempt, currentAddress: sentAddress, gateway: gateway))
    }

    /// The headers a family link's calls carry: the rows and the sign-in page fields, for the
    /// link's address. Nothing is saved for a link's server yet.
    private func linkHeaders(main: URL, rows: [HeaderRules.Row]) -> ResolvedHeaders {
        resolveConnectionHeaders(rows: rows, gateway: gatewayDraft, saved: [:], mainHttps: main.scheme?.lowercased() == "https",
                                 mainPrivate: ServerAddress.isPrivate(main))
    }

    // MARK: Submitting

    /// The home address field, read: nil when blank, or the error words when unreadable.
    private func readHome() -> Result<URL?, SignInFailure> {
        switch ServerAddress.home(homeAddress, main: url) {
        case .none: .success(nil)
        case .address(let home): .success(home)
        case .unreadable: .failure(.words(SignInWords.badHomeAddress))
        }
    }

    /// The header rows that count, trimmed, or nil when one is not valid.
    private func headerRows() -> [HeaderRules.Row]? {
        HeaderRules.rows(headers.map { HeaderRules.Row(name: $0.name, value: $0.value, saved: $0.saved) })
    }

    func submit(_ session: Session, sync: ServerSync? = nil) async {
        guard ready, let url else { return }
        error = nil
        let attempt = sentAddress
        let home: URL?
        switch readHome() {
        case .success(let read): home = read
        case .failure(let failure):
            error = failure.localizedDescription
            return
        }
        guard let rows = headerRows() else {
            error = SignInWords.badHeaders
            return
        }
        let mode = self.mode
        let typedLabel = label.trimmingCharacters(in: .whitespaces)
        // Only the server list's Add and Edit name the server; the rest keep the saved name.
        let name: String? = showsName ? typedLabel : nil
        if case .edit(let server) = form, onlyRenamed(server, url: url, home: home, mode: mode, rows: rows) {
            session.rename(server.id, label: typedLabel)
            notice = "Saved \(session.servers.first { $0.id == server.id }?.name ?? server.name)."
            signedIn = true
            return
        }
        let fields = Session.Form(
            address: url, homeAddress: home,
            label: name,
            username: username.trimmingCharacters(in: .whitespaces),
            secret: useApiKey ? apiKey.trimmingCharacters(in: .whitespacesAndNewlines) : password,
            mode: mode,
            headers: rows,
            gateway: gatewayDraft,
            certificate: certificate.map { ($0.data, $0.password) },
            removesCertificate: removesCertificate || (staleCertificate && certificate == nil),
            certificateName: certificate?.name)
        let purpose: Session.Purpose = switch form {
        case .signIn, .join: .signIn
        // A kept server's own form signs in to that server, whatever its address is now.
        case .again(let server, _): .signInAgain(server.id)
        case .add: .add
        case .edit(let server), .editConnection(let server): .edit(server.id)
        }
        busy = true
        defer { busy = false }
        let previous = session.inUse != nil ? session.active : nil
        do {
            let kept = try await session.keepServer(fields, for: purpose)
            if kept.addressChanged {
                // A folder belongs to the address it was chosen on.
                session.setFolder(nil, for: kept.server.id)
                sync?.chooseMusicFolder(nil, for: kept.server.id)
            }
            notice = switch purpose {
            case .add: "Added \(kept.server.name). Switch to it whenever you like."
            case .edit: "Saved \(kept.server.name)."
            case .signIn, .signInAgain: serverSwitch?.signInNotice(previous: previous)
            }
            password = ""
            apiKey = ""
            gatewayFirst = ""
            gatewaySecond = ""
            signedIn = true
        } catch let untrusted as ServerTrust.Untrusted {
            question = untrusted
        } catch let failure as SignInFailure {
            error = failure.localizedDescription
        } catch {
            switch legacyRetry(error, mode: mode, url: url) {
            case .retry:
                // Servers that check passwords against a directory cannot check a token. Over
                // https or on the home network the password may go as it is, so try that at once.
                legacy = true
                advancedOpen = true
                busy = false
                await submit(session, sync: sync)
            case .unsafe:
                self.error = legacyUnsafeMessage
            case .none:
                self.error = userMessage(plainError(error))
                // A sign-in page in front of the server opens Advanced at its fields.
                met(attempt, SubsonicError(error).gateway)
            }
        }
    }

    /// An edit that only gave the server another name: nothing to check with the server.
    private func onlyRenamed(_ server: StoredServer, url: URL, home: URL?, mode: AuthMode, rows: [HeaderRules.Row]) -> Bool {
        let sameHeaders = rows.allSatisfy { $0.saved && $0.value.isEmpty } && rows.map(\.name) == savedHeaderNames
        return url == server.address && home == server.homeAddress
            && username.trimmingCharacters(in: .whitespaces) == server.username && mode == server.mode
            && password.isEmpty && apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && sameHeaders && certificate == nil && !removesCertificate
            && gatewayKind == gatewaySaved && gatewayFirst.isEmpty && gatewaySecond.isEmpty
    }

    func trust(_ session: Session, sync: ServerSync? = nil) async {
        guard let question else { return }
        ServerTrust.shared.trust(question)
        self.question = nil
        if handOver != nil {
            receive(session)
        } else if joining {
            await signUp(session)
        } else {
            await submit(session, sync: sync)
        }
    }

    func distrust() {
        question = nil
        error = SignInWords.notTrusted
    }

    // MARK: Join

    /// Turns the join part on. With a link: types its server in, takes its home address, and
    /// shows the sign-up for an invite, receives a hand-over at once, or fills in the ordinary
    /// form for a sign-in to fill in.
    func startJoin(_ link: FamilyLink? = nil, session: Session? = nil) {
        scanning = false
        error = nil
        guard let link else {
            joining = true
            return
        }
        rest = link.server
        if let home = link.home { homeAddress = home }
        signedUp = nil
        received = nil
        invite = nil
        handOver = nil
        handOverStep = nil
        switch link {
        case .invite(let held):
            joining = true
            invite = held
        case .handOver(let held):
            joining = true
            handOver = held
            if let session { receive(session) }
        case .prefill(let prefill):
            joining = false
            if !prefill.username.trimmingCharacters(in: .whitespaces).isEmpty { username = prefill.username }
            password = ""
            useApiKey = false
        }
    }

    /// Back to the ordinary password form. A hand-over still running can still finish.
    func stopJoin() {
        joining = false
        invite = nil
        handOver = nil
        handOverStep = nil
        received = nil
        error = nil
    }

    /// What the sign-up still needs, never shown as words: only the button's state shows it.
    var joinMissing: String? {
        guard invite != nil else { return "Scan or open your invite first" }
        guard url != nil else { return "Type the server's address" }
        return inviteProblem(displayName: joinName, password: joinPassword, again: joinAgain)
    }

    var strength: String? { passwordStrength(joinPassword) }

    /// Accepts the invite with the name and password chosen, then signs in with the account it
    /// made, like a typed sign-in.
    func signUp(_ session: Session) async {
        guard !busy, joinMissing == nil, let url, let invite else { return }
        busy = true
        error = nil
        defer { busy = false }
        let home: URL?
        switch readHome() {
        case .success(let read): home = read
        case .failure(let failure):
            error = failure.localizedDescription
            return
        }
        let rows = headerRows() ?? []
        let attempt = sentAddress
        if signedUp == nil {
            // The invite is accepted as signing in would ask the server: through a session of its
            // own, with the form's headers, sign-in page fields and certificates.
            let client: SubsonicClient
            switch linkHeaders(main: url, rows: rows) {
            case .problem(let text):
                error = text
                return
            case .ready(let made):
                do {
                    client = try session.linkClient(at: url, home: home, headers: made, certificate: certificate.map { ($0.data, $0.password) })
                } catch {
                    self.error = error.localizedDescription
                    return
                }
            }
            let outcome = await joinWithInvite(client: client, token: invite.token,
                                               displayName: joinName.trimmingCharacters(in: .whitespacesAndNewlines),
                                               password: joinPassword, home: home)
            client.session.finishTasksAndInvalidate()
            switch outcome {
            case .signedUp(let name, let chosen, _):
                signedUp = (name, chosen)
            case .untrusted(let host):
                if let rejected = ServerTrust.shared.rejection(for: host) {
                    question = rejected
                } else {
                    error = "This server's certificate is not trusted."
                }
                return
            case .failed(let words, _, let gateway, _):
                // A sign-in page that turns the invite away opens Advanced at its fields.
                error = words
                met(attempt, gateway)
                return
            }
        }
        guard let account = signedUp else { return }
        let fields = Session.Form(
            address: url, homeAddress: home, label: nil, username: account.username, secret: account.password, mode: .token,
            headers: rows, gateway: gatewayDraft,
            certificate: certificate.map { ($0.data, $0.password) }, removesCertificate: false, certificateName: certificate?.name)
        let previous = session.inUse != nil ? session.active : nil
        do {
            _ = try await session.keepServer(fields, for: .signIn)
            notice = serverSwitch?.signInNotice(previous: previous)
            joinPassword = ""
            joinAgain = ""
            gatewayFirst = ""
            gatewaySecond = ""
            self.invite = nil
            signedUp = nil
            signedIn = true
        } catch let untrusted as ServerTrust.Untrusted {
            question = untrusted
        } catch let failure as SignInFailure {
            error = failure.localizedDescription
        } catch {
            self.error = userMessage(plainError(error))
            met(attempt, SubsonicError(error).gateway)
        }
    }

    /// Redeems the hand-over, waits for the other device to allow it, and signs in with what it
    /// sent. Each step's words show as they come. The redeem goes through a session of its own
    /// with the form's headers, sign-in page fields and certificates; a sign-in page that turns it
    /// away opens Advanced at its fields, and Try again uses what is typed there. A sign-in
    /// received already (before a certificate question) is not redeemed again.
    func receive(_ session: Session) {
        guard let link = handOver, !busy else { return }
        guard let base = ServerAddress.httpURL(link.base) else { return }
        let home: URL?
        switch readHome() {
        case .success(let read): home = read
        case .failure(let failure):
            error = failure.localizedDescription
            return
        }
        let client: SubsonicClient
        switch linkHeaders(main: base, rows: headerRows() ?? []) {
        case .problem(let text):
            error = text
            return
        case .ready(let made):
            do {
                client = try session.linkClient(at: base, home: home, headers: made, certificate: certificate.map { ($0.data, $0.password) })
            } catch {
                self.error = error.localizedDescription
                return
            }
        }
        busy = true
        error = nil
        let attempt = sentAddress
        let kept = received
        let device = Session.device().name
        let step: @Sendable (String) async -> Void = { [weak self] words in await self?.show(step: words) }
        receiving = Task { [weak self] in
            let result: HandOverResult
            if let kept {
                result = .received(kept)
            } else {
                result = await receiveHandOver(link, connect: { client.at($0) }, deviceName: device, platform: .iOS, step: step)
            }
            client.session.finishTasksAndInvalidate()
            await self?.finish(result, session: session, host: base.host, attempt: attempt)
        }
    }

    private func show(step words: String) { handOverStep = words }

    private func finish(_ result: HandOverResult, session: Session, host: String?, attempt: String) async {
        defer { busy = false }
        switch result {
        case .refused(let words, let gateway, _):
            handOverStep = nil
            // A certificate the phone does not trust is asked about, as signing in asks; trusting
            // it tries again.
            if gateway == nil, words == handOverUnreachable, let rejected = ServerTrust.shared.rejection(for: host) {
                question = rejected
                return
            }
            error = words
            // A sign-in page that turns the hand-over away opens Advanced at its fields.
            met(attempt, gateway)
        case .received(let signIn):
            guard let address = ServerAddress.parse(signIn.server) else {
                handOverStep = nil
                error = SignInWords.badAddress
                return
            }
            let homeURL: URL?
            switch ServerAddress.home(signIn.home ?? "", main: address) {
            case .none: homeURL = nil
            case .address(let home): homeURL = home
            case .unreadable:
                handOverStep = nil
                error = SignInWords.badHomeAddress
                return
            }
            let mode: AuthMode = switch signIn.mode {
            case "LegacyPassword": .legacyPassword
            case "ApiKey": .apiKey
            default: .token
            }
            // The sign-in carries the form's headers, sign-in page fields and certificate.
            let fields = Session.Form(address: address, homeAddress: homeURL, label: nil, username: signIn.username,
                                      secret: signIn.secret, mode: mode, headers: headerRows() ?? [], gateway: gatewayDraft,
                                      certificate: certificate.map { ($0.data, $0.password) }, removesCertificate: false,
                                      certificateName: certificate?.name)
            received = signIn
            let previous = session.inUse != nil ? session.active : nil
            do {
                _ = try await session.keepServer(fields, for: .signIn)
                notice = serverSwitch?.signInNotice(previous: previous)
                handOverStep = handOverSignedIn
                handOver = nil
                received = nil
                gatewayFirst = ""
                gatewaySecond = ""
                signedIn = true
            } catch let untrusted as ServerTrust.Untrusted {
                question = untrusted
            } catch let failure as SignInFailure {
                handOverStep = nil
                error = failure.localizedDescription
            } catch {
                handOverStep = nil
                self.error = userMessage(plainError(error))
                met(attempt, SubsonicError(error).gateway)
            }
        }
    }

    /// Leaving the screen stops a wait still running.
    func leave() {
        receiving?.cancel()
    }
}

#if DEBUG
extension SignInModel {
    /// A launch step's sample state, in memory only: the kind saved for the server, and the
    /// sign-in page an attempt at the typed address met with the words it showed, as a failed
    /// sign-in leaves them.
    func driveSample(saved: GatewayKind? = nil, met gateway: Gateway? = nil, words: String? = nil) {
        if let saved {
            gatewaySaved = saved
            gatewayKind = saved
            advancedOpen = true
        }
        if let gateway { met(sentAddress, gateway) }
        if let words { error = words }
    }
}
#endif

extension SignInModel.Form {
    var server: StoredServer? {
        switch self {
        case .edit(let server), .again(let server, _), .editConnection(let server): server
        case .signIn, .add, .join: nil
        }
    }
}
