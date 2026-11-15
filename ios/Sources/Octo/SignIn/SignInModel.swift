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
        /// A family link: an invite, a sign-in to fill in, or a sign-in handed over. `opened` counts
        /// each opening, so the same link opened again presents a cover of its own.
        case join(String, opened: Int)

        var id: String {
            switch self {
            case .signIn: "signIn"
            case .add: "add"
            case .edit(let server): "edit." + server.id
            case .again(let server, _): "again." + server.id
            case .editConnection(let server): "connection." + server.id
            case .join(let link, let opened): "join.\(opened)." + link
            }
        }
    }

    /// A custom header row; a saved one keeps its value in the keychain and shows none.
    typealias Header = HeaderDraftRow

    let form: Form
    var rest = "" {
        didSet {
            // A scheme typed in moves into the button, which sets `rest` again: that inner set
            // returns at once, and the pair is compared once the split is done.
            guard !splitting else { return }
            let before = (scheme, oldValue)
            splitting = true
            typed()
            splitting = false
            if rest != oldValue { error = nil }
            // A sign-in page met at another address is forgotten.
            useGatewayForm(addressChanged(gatewayForm, address: sentAddress))
            // The kept server's own address pasted again changes nothing.
            if before != (scheme, rest) { linkEdited() }
        }
    }
    private(set) var scheme: ServerAddress.Scheme = .https {
        didSet { if !splitting, scheme != oldValue { linkEdited() } }
    }
    /// The address is being split into its scheme and the rest.
    @ObservationIgnored private var splitting = false
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
    var homeAddress = "" {
        didSet { if homeAddress != oldValue { linkEdited() } }
    }
    /// The custom header rows; every change goes through `HeaderList`, which keeps each row's id.
    private var headerList = HeaderList.empty
    var headers: [Header] { headerList.rows }
    /// A certificate chosen on this form, checked but not in use until the server is saved, with its
    /// last valid day when it names one.
    var certificate: (name: String, data: Data, password: String, expires: Date?)?
    /// The kept server has a certificate the keychain can still read.
    var hadCertificate = false
    var removesCertificate = false
    /// The saved certificate's file name.
    private(set) var savedCertificateName: String?
    /// The saved certificate's last valid day, read once off the main actor when the page appears; nil
    /// until then, and when its file cannot be opened.
    private(set) var savedCertificateExpiry: Date?
    @ObservationIgnored private var expiryRead: Task<Void, Never>?
    /// The page has appeared, and the saved certificate's read has been asked for.
    @ObservationIgnored private var expiryAsked = false
    /// The kept server says it has a certificate, but the keychain no longer holds it (a phone
    /// restored from a backup); saving clears that.
    private var staleCertificate = false
    var advancedOpen = false
    /// The kind of sign-in the sign-in page in front of the server takes from apps, and what is
    /// typed for it: a username and password, or a Client ID and Client Secret.
    private(set) var gatewayKind: GatewayKind = .none
    /// An edit of either field is the person's answer to the failure: the error and the callout go.
    var gatewayFirst = "" {
        didSet { if gatewayFirst != oldValue { pageFieldEdited() } }
    }
    var gatewaySecond = "" {
        didSet { if gatewaySecond != oldValue { pageFieldEdited() } }
    }
    /// The kind the saved headers make up; its values stay while its fields are left empty.
    private(set) var gatewaySaved: GatewayKind = .none
    /// The sign-in page the last failed attempt met, and the address it met it at.
    private(set) var gatewayMet: Gateway?
    private var gatewayMetFor: String?
    /// The last failure met a sign-in page at this address: its group shows that failure, outlined.
    private(set) var callout = false
    /// The sign-in page group still has to be brought into view.
    private(set) var calloutPending = false
    /// The API key row, just asked for, still has to be brought into view.
    private(set) var keyPending = false

    var busy = false
    /// Any write ends the callout and its scroll; a failure that met a sign-in page calls it out
    /// again right after its words are written.
    var error: String? {
        didSet { useGatewayForm(OctoKit.calm(gatewayForm)) }
    }
    var question: ServerTrust.Untrusted?
    private(set) var notice: String?
    private(set) var signedIn = false
    /// Moves the phone between servers and says how a sign-in's switch went.
    @ObservationIgnored var serverSwitch: ServerSwitch?
    /// The link this page was opened with has been taken: a page shown again never takes it twice.
    @ObservationIgnored var didOpenJoin = false
    /// Where family links may take this form (see `LinkFormState`); its note is the link note.
    private var linkState = LinkFormState(.fresh)
    /// Why the last link was refused, shown under the address field.
    var linkNote: String? { linkState.note }
    /// Bumped when a link pasted into the address is refused, so the field is drawn again without
    /// the pasted text.
    private(set) var addressResets = 0

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
    /// An account the invite made, kept until it signs in, since an invite works once, with the
    /// main address the sign-up started from: the sign-in goes there, and the address field stays
    /// as it is while this is held.
    private var signedUp: (username: String, password: String, main: URL)?
    /// A sign-in received from another device, kept until signed in with: a hand-over works once,
    /// so a certificate asked about afterwards does not redeem it again.
    private var received: HandOverSignIn?
    private var receiving: Task<Void, Never>?
    /// A hand-over waits for the other device to allow it: leaving now is harmless, so every way
    /// out stays open and cancels it.
    private(set) var waiting = false
    /// A request that commits is running (a sign-up, a sign-in, a hand-over's sign-in after Allow):
    /// every way out waits for it.
    var committing: Bool { busy && !waiting }
    /// A finished sign-up is held: its account is made but not yet signed in to.
    var holdsSignUp: Bool { signedUp != nil }
    /// A sign-in is open here: running, a certificate question waiting, or a received sign-in or a
    /// finished sign-up held, each of which works only once. No link replaces it: one from outside
    /// waits as a message, and one taken here (pasted, or the page's own camera) is refused.
    var signInOpen: Bool {
        OctoKit.signInOpen(busy: busy, question: question != nil, holdsReceived: received != nil, holdsSignUp: signedUp != nil)
    }
    /// What the saved server looked like, for telling a rename from a real change.
    private let saved: StoredServer?
    private let savedHeaderNames: [String]

    init(form: Form, session: Session) {
        self.form = form
        saved = form.server
        savedHeaderNames = form.server.map { session.savedHeaderNames($0.id) } ?? []
        // A form opened for a kept server is about that server and account alone.
        linkState = LinkFormState(form.server == nil ? .fresh : .keptServer)
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
        headerList.replaced(savedHeaderNames.map { HeaderRules.Row(name: $0, value: "", saved: true) })
        // The saved sign-in page's kind; its values stay in the keychain and are kept while its
        // fields are left empty.
        gatewaySaved = session.gatewayKind(server.id)
        gatewayKind = gatewaySaved
        // A server with none reads as one whose certificate is gone; one the keychain can't read now
        // still shows, with its name and Remove, and is never taken off by itself.
        let certificateState: CertificateState = server.hasClientCertificate ? CertificateCheck.state(forServer: server.id) : .stale
        hadCertificate = certificateState.shown
        staleCertificate = server.hasClientCertificate && certificateState == .stale
        savedCertificateName = certificateState.shown ? server.clientCertificateName : nil
        advancedOpen = server.mode != .token || server.homeAddress != nil || !headers.isEmpty || hadCertificate || gatewaySaved != .none
        takesApiKeys = server.takesApiKeys
        if case .again(_, let note) = form { error = note }
        // After the fill's own writes, each of which ends an anchor.
        linkState.filled(LinkAnchor(main: server.address.absoluteString, home: server.homeAddress?.absoluteString,
                                    username: server.username))
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
    var mode: AuthMode { authModeOf(legacy: legacy, apiKey: useApiKey) }

    /// Whether the password field may stay empty and keep the saved password.
    var keepsPassword: Bool { OctoKit.keepsPassword(keepsSecret: editing, savedMode: saved?.mode, mode: mode) }

    /// Whether the API key field may stay empty and keep the saved key.
    var keepsKey: Bool { OctoKit.keepsKey(keepsSecret: editing, savedMode: saved?.mode, mode: mode) }

    /// Picks the way of signing in. Picking API key with no key kept for it brings the key row,
    /// which takes the password's place, into view.
    func pickMethod(_ newMode: AuthMode) {
        let oldMode = mode
        let flags = flagsOf(newMode)
        legacy = flags.legacy
        useApiKey = flags.apiKey
        useGatewayForm(methodPicked(gatewayForm, oldMode: oldMode, newMode: newMode, keepsKey: keepsKey))
    }

    /// The client certificate's name to show, or nil for none.
    var certificateShown: String? {
        if let certificate { return certificate.name }
        guard hadCertificate, !removesCertificate else { return nil }
        return savedCertificateName ?? "Saved certificate"
    }

    /// The shown certificate's last valid day, when it is known.
    var certificateExpiry: Date? {
        if let certificate { return certificate.expires }
        guard hadCertificate, !removesCertificate else { return nil }
        return savedCertificateExpiry
    }

    /// The page appeared: the saved certificate's last valid day is read, once. Never in `init`, which
    /// runs for every model SwiftUI builds while it makes the page's view, most of them never shown.
    func appeared() {
        guard !expiryAsked else { return }
        expiryAsked = true
        if hadCertificate, let server = saved { readSavedExpiry(server.id) }
    }

    /// Reads the saved certificate's last valid day: its file is opened with its password once, off the
    /// main actor. An answer that comes after the form moved on (the page left, the certificate removed
    /// or another picked) is dropped.
    private func readSavedExpiry(_ id: String) {
        expiryRead = Task { [weak self] in
            let expires = await Task.detached(priority: .utility) { CertificateCheck.savedExpiry(forServer: id) }.value
            guard !Task.isCancelled, let self, self.hadCertificate, !self.removesCertificate, self.certificate == nil else { return }
            self.savedCertificateExpiry = expires
        }
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

    /// The address, its scheme or the home changed: the form is no longer tied to a saved server or
    /// to the links taken so far.
    private func linkEdited() {
        let home: String? = switch ServerAddress.home(homeAddress, main: url) {
        case .address(let read): read.absoluteString
        case .none, .unreadable: nil
        }
        linkState.edited(address: url?.absoluteString, home: home,
                         addressNonBlank: !rest.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }

    /// What the address field takes: a family link starts the join instead of being typed in.
    func typeAddress(_ text: String, session: Session) {
        if let link = parseFamilyLink(text) {
            if !startJoin(link, session: session) { addressResets += 1 }
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
        case encrypted, homeNetwork, legacyInTheOpen, apiKeyInTheOpen, tokenInTheOpen
    }

    /// While joining, the link decides how to sign in, so the note reads the Method as Normal.
    var note: Note? {
        guard let url else { return nil }
        if url.scheme == "https" { return .encrypted }
        if ServerAddress.isPrivate(url) { return .homeNetwork }
        switch joining ? .token : mode {
        case .legacyPassword: return .legacyInTheOpen
        case .apiKey: return .apiKeyInTheOpen
        case .token: return .tokenInTheOpen
        }
    }

    /// A blank header row at the end; its id lets the page put focus in it once it is drawn.
    @discardableResult
    func addHeader() -> Header.ID {
        headerList.added()
    }

    /// A header row's new name; a saved row renamed no longer has a value to keep.
    func renameHeader(_ id: Header.ID, to name: String) {
        headerList.renamed(id, name)
    }

    func setHeaderValue(_ id: Header.ID, to value: String) {
        headerList.valued(id, value)
    }

    func removeHeader(_ id: Header.ID) {
        headerList.remove(id)
    }

    // MARK: The sign-in page in front of the server

    /// Picks the kind of sign-in the sign-in page takes. Another kind starts with empty fields,
    /// since they mean other things.
    func pickGateway(_ kind: GatewayKind) {
        if kind != gatewayKind {
            gatewayFirst = ""
            gatewaySecond = ""
        }
        useGatewayForm(kindPicked(gatewayForm, kind: kind))
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
        GatewayFormState(advancedOpen: advancedOpen, kind: gatewayKind, detected: gatewayMet, detectedFor: gatewayMetFor,
                         callout: callout, calloutPending: calloutPending, keyPending: keyPending)
    }

    private func useGatewayForm(_ state: GatewayFormState) {
        advancedOpen = state.advancedOpen
        gatewayKind = state.kind
        gatewayMet = state.detected
        gatewayMetFor = state.detectedFor
        callout = state.callout
        calloutPending = state.calloutPending
        keyPending = state.keyPending
    }

    /// A sign-in page field edited: the failure is the person's to fix now.
    private func pageFieldEdited() {
        useGatewayForm(OctoKit.gatewayEdited(gatewayForm))
        error = nil
    }

    /// The sign-in page group calls out the last failure: its error note, its outline and the
    /// pointer under the button draw only while this holds.
    var calloutShowing: Bool { callout && advancedOpen && error != nil }

    /// The pointer under the button: the group is brought into view again.
    func pointTo() {
        useGatewayForm(OctoKit.pointTo(gatewayForm))
    }

    /// The group was brought into view, or a field took focus first.
    func calloutShown() {
        useGatewayForm(OctoKit.calloutShown(gatewayForm))
    }

    /// The API key row was brought into view, or a field took focus first.
    func keyShown() {
        useGatewayForm(OctoKit.keyShown(gatewayForm))
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
        guard !busy, ready, let url else { return }
        useGatewayForm(OctoKit.sending(gatewayForm))
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
        waiting = false
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
                busy = false
                await submit(session, sync: sync)
            case .unsafe:
                self.error = SignInWords.legacyUnsafe
            case .none:
                // A sign-in page in front of the server opens Advanced at its fields.
                self.error = userMessage(plainError(error))
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

    /// Scan a family QR code, from the plain page: the join part with the camera over it. Never
    /// while a sign-in runs or its certificate question waits, which is never switched halfway: the
    /// join part stays off and the camera closed.
    func scanFamilyCode() {
        guard startJoin() else { return }
        scanning = true
    }

    /// Turns the join part on; with no link, never while a request runs or its certificate question
    /// waits. A link goes to the form's link state first: a link the form takes types its server in,
    /// takes its home address, and shows the sign-up for an invite, receives a hand-over at once, or
    /// fills in the ordinary form for a sign-in to fill in; a link to another server first forgets
    /// everything typed or saved for the last one. While a finished sign-up or a received sign-in is
    /// held, each of which works once, only its own invite or hand-over for the same server is taken,
    /// and any other link is refused with the held step left as it is. A refused link changes nothing
    /// but the link note; the camera closes either way. Answers whether it was taken.
    @discardableResult
    func startJoin(_ link: FamilyLink? = nil, session: Session? = nil) -> Bool {
        scanning = false
        guard let link else {
            guard !busy, question == nil else { return false }
            error = nil
            joining = true
            return true
        }
        let action = linkState.take(link.facts, busy: busy || question != nil, home: homeAddress)
        guard case .take(let kind, let address, let home, _) = action else {
            linkState.took(action)
            return false
        }
        if heldStepRefuses(link, take: kind, signedUp: signedUp != nil, invite: invite, received: received != nil, handOver: handOver) {
            linkState.noted(SignInWords.linkWhileSignInOpen)
            return false
        }
        error = nil
        if kind == .forget {
            // Nothing of the last server goes to this one; a prefill's username is written below.
            let named: String? = if case .prefill(let prefill) = link { prefill.username } else { nil }
            forgetLastServer(linkUsername: named)
        }
        if let address { rest = address }
        if let home { homeAddress = home }
        linkState.took(action)
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
        return true
    }

    /// A link or a received sign-in for another server: nothing typed or saved for the last one
    /// stays, as `LinkFields.forgotten` lists it. The username stays only for a link that names one,
    /// which then writes it in.
    private func forgetLastServer(linkUsername: String?) {
        let fields = LinkFields(headers: headerList, gatewayKind: gatewayKind, gatewaySaved: gatewaySaved, gatewayFirst: gatewayFirst,
                                gatewaySecond: gatewaySecond, legacy: legacy, useApiKey: useApiKey, apiKey: apiKey, password: password,
                                joinName: joinName, joinPassword: joinPassword, joinAgain: joinAgain, username: username,
                                takesApiKeys: takesApiKeys, certificatePicked: certificate != nil)
            .forgotten(linkUsername: linkUsername)
        headerList = fields.headers
        var state = gatewayForm
        state.kind = fields.gatewayKind
        useGatewayForm(state)
        gatewaySaved = fields.gatewaySaved
        gatewayFirst = fields.gatewayFirst
        gatewaySecond = fields.gatewaySecond
        legacy = fields.legacy
        useApiKey = fields.useApiKey
        apiKey = fields.apiKey
        password = fields.password
        joinName = fields.joinName
        joinPassword = fields.joinPassword
        joinAgain = fields.joinAgain
        username = fields.username
        takesApiKeys = fields.takesApiKeys
        if !fields.certificatePicked { certificate = nil }
    }

    /// Back to the ordinary password form. Refused while a request that commits runs; a hand-over
    /// still waiting for Allow is cancelled. A finished sign-up's username stays in the username
    /// field, since the server chose it.
    func stopJoin() {
        guard !committing else { return }
        receiving?.cancel()
        receiving = nil
        busy = false
        waiting = false
        handOverStep = nil
        leaveJoin()
    }

    /// Leaves the link: the sign-up or hand-over it held is spent.
    private func leaveJoin() {
        if let account = signedUp { username = account.username }
        joining = false
        invite = nil
        handOver = nil
        handOverStep = nil
        received = nil
        signedUp = nil
        linkState.left()
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
        useGatewayForm(OctoKit.sending(gatewayForm))
        busy = true
        waiting = false
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
                // Kept with the main address the sign-up started from, never the home address it may
                // have fallen back to: the sign-in goes there.
                signedUp = (name, chosen, url)
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
            address: account.main, homeAddress: home, label: nil, username: account.username, secret: account.password, mode: .token,
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
        useGatewayForm(OctoKit.sending(gatewayForm))
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
        // Only a redeem waits for the other device; a sign-in received already commits at once.
        waiting = received == nil
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
            guard !Task.isCancelled else { return }
            await self?.finish(result, session: session, host: base.host, attempt: attempt)
        }
    }

    private func show(step words: String) { handOverStep = words }

    /// The hand-over's result. A cancelled run (the person left the link) ends here with nothing
    /// shown, since a cancel reads as a refused, expired link. The sealed sign-in is checked against
    /// the form before anything is sent, on every retry that reuses it too.
    private func finish(_ result: HandOverResult, session: Session, host: String?, attempt: String) async {
        guard !Task.isCancelled else { return }
        waiting = false
        defer { busy = false }
        var attempt = attempt
        switch result {
        case .refused(let words, let gateway, _):
            handOverStep = nil
            // A certificate the phone does not trust is asked about, as signing in asks; trusting
            // it tries again.
            if gateway == nil, words == handOverUnreachable, let rejected = ServerTrust.shared.rejection(for: host) {
                question = rejected
                return
            }
            // A sign-in page that turns the hand-over away opens Advanced at its fields.
            error = words
            met(attempt, gateway)
        case .received(let signIn):
            let check = linkState.received(signIn.facts)
            switch check {
            case .refuse(let why):
                // The hand-over is spent: the form leaves it, then says why.
                leaveJoin()
                linkState.noted(why.words)
                return
            case .forgetAndAdopt(let address, let home, _):
                // Another server: nothing of the last one goes to it, and the form shows where the
                // sign-in goes, so a retry checks against it and failures belong to it.
                forgetLastServer(linkUsername: nil)
                rest = address
                homeAddress = home
                linkState.adopted(check)
                attempt = sentAddress
            case .keep:
                break
            }
            let address: URL
            let homeURL: URL?
            if check == .keep(useForm: true) {
                // The kept server's own addresses, the ones the redeem just used.
                guard let url else {
                    handOverStep = nil
                    error = SignInWords.badAddress
                    return
                }
                switch readHome() {
                case .success(let read): homeURL = read
                case .failure(let failure):
                    handOverStep = nil
                    error = failure.localizedDescription
                    return
                }
                address = url
            } else {
                guard let parsed = ServerAddress.parse(signIn.server) else {
                    handOverStep = nil
                    error = SignInWords.badAddress
                    return
                }
                switch ServerAddress.home(signIn.home ?? "", main: parsed) {
                case .none: homeURL = nil
                case .address(let home): homeURL = home
                case .unreadable:
                    handOverStep = nil
                    error = SignInWords.badHomeAddress
                    return
                }
                address = parsed
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
            // A kept server's own form signs in to that server again, so it is updated in place.
            let purpose: Session.Purpose = form.server.map { .signInAgain($0.id) } ?? .signIn
            let previous = session.inUse != nil ? session.active : nil
            do {
                _ = try await session.keepServer(fields, for: purpose)
                notice = serverSwitch?.signInNotice(previous: previous)
                handOverStep = handOverSignedIn
                handOver = nil
                received = nil
                gatewayFirst = ""
                gatewaySecond = ""
                signedIn = true
            } catch let untrusted as ServerTrust.Untrusted {
                handOverStep = nil
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

    /// Leaving the screen stops a wait still running, and the saved certificate's read.
    func leave() {
        receiving?.cancel()
        expiryRead?.cancel()
    }

    /// Words for the link note from outside the form: a link that came in while this sign-in was open.
    func noteLink(_ words: String) {
        linkState.noted(words)
    }
}

#if DEBUG
extension SignInModel {
    /// A launch step's sample state, in memory only: the kind saved for the server, and the
    /// sign-in page an attempt at the typed address met with the words it showed, written the way
    /// a failed sign-in writes them: the words first, then the group's reaction.
    func driveSample(saved: GatewayKind? = nil, met gateway: Gateway? = nil, words: String? = nil) {
        if let saved {
            gatewaySaved = saved
            gatewayKind = saved
            advancedOpen = true
        }
        if let words { error = words }
        if let gateway { met(sentAddress, gateway) }
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
