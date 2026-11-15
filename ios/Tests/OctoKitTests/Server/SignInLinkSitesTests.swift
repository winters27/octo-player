import Foundation
import Testing

/// The iPhone's link and open sign-in wiring, checked in its own sources: the decisions are OctoKit's (the
/// link vectors, `LinkFields`, `signInOpen`, `heldStepRefuses`, `askOpens` and the covers' rules), and
/// these rows pin where the app calls them.
@Suite struct SignInLinkSitesTests {
    private static let model = "Octo/SignIn/SignInModel.swift"
    private static let view = "Octo/SignIn/SignInView.swift"

    /// Red when a link writes the form before the link state decides, or the decision is recorded
    /// before the writes, whose own edits would then end what it keeps.
    @Test func aLinkIsDecidedBeforeItsWritesAndRecordedAfter() throws {
        let model = try SourceText.read(Self.model)
        let start = try SourceText.slice(model, from: "    func startJoin(", to: "\n    }\n")
        #expect(start.hasPrefix("    func startJoin(_ link: FamilyLink? = nil, session: Session? = nil) -> Bool {\n        scanning = false\n"))
        let take = try SourceText.at("let action = linkState.take(link.facts, busy: busy || question != nil, home: homeAddress)", in: start)
        let address = try SourceText.at("if let address { rest = address }", in: start)
        let home = try SourceText.at("if let home { homeAddress = home }", in: start)
        let took = try SourceText.lastAt("linkState.took(action)", in: start)
        #expect(take < address && address < home && home < took)
        #expect(SourceText.squashed(start).contains(
            "guard case .take(let kind, let address, let home, _) = action else { linkState.took(action) return false }"))
        #expect(start.contains("if kind == .forget {"))
        #expect(start.contains("forgetLastServer(linkUsername: named)"))
        // A held sign-up or received sign-in works once and ends only by its own ways out. Red when a
        // taken link clears either again.
        for clear in ["signedUp = ", "received = "] {
            #expect(!start.contains(clear), "\(clear)")
        }
    }

    /// Red when a link taken on the page (pasted, or its own camera) gets past a held sign-up or
    /// received sign-in: the held step's check sits right after the take's own refusals and before
    /// every write, says the open sign-in's words under the address and changes nothing else. Which
    /// links it refuses is OctoKit's `heldStepRefuses`, fed the held step as the model holds it.
    @Test func aHeldStepRefusesEveryOtherLinkBeforeAnyWrite() throws {
        let model = try SourceText.read(Self.model)
        let start = try SourceText.slice(model, from: "    func startJoin(", to: "\n    }\n")
        let squashed = SourceText.squashed(start)
        #expect(squashed.contains(
            "guard case .take(let kind, let address, let home, _) = action else { linkState.took(action) return false } if heldStepRefuses(link, take: kind, signedUp: signedUp != nil, invite: invite, received: received != nil, handOver: handOver) { linkState.noted(SignInWords.linkWhileSignInOpen) return false } error = nil if kind == .forget {"))
        let held = try SourceText.at("if heldStepRefuses(", in: start)
        let after = String(start[held...])
        for write in ["error = nil", "forgetLastServer(", "if let address { rest = address }", "if let home { homeAddress = home }",
                      "linkState.took(action)\n        invite = nil", "handOver = nil", "handOverStep = nil", "receive(session)"] {
            #expect(after.contains(write), "\(write)")
        }
        let before = SourceText.squashed(String(start[..<held]))
        for write in ["rest = ", "homeAddress = ", "forgetLastServer(", "invite = ", "handOver = ", "handOverStep = "] {
            #expect(!before.contains(write), "\(write)")
        }
        #expect(SourceText.count("heldStepRefuses(", in: model) == 1)
        // Both ways a link reaches the page come through here.
        let view = SourceText.squashed(try SourceText.read(Self.view))
        #expect(view.contains("QrScanner { link in model.startJoin(link, session: session) } cancel: {"))
        #expect(model.contains("if !startJoin(link, session: session) { addressResets += 1 }"))
    }

    /// Red when Scan a family QR code is live while a sign-in commits, or the model opens the join
    /// part or the camera while a request runs or its certificate question waits: a tap that lands
    /// before the button is drawn off switches nothing halfway.
    @Test func scanningAFamilyCodeWaitsForASignInThatCommits() throws {
        let raw = try SourceText.read(Self.view)
        let view = SourceText.squashed(raw)
        #expect(view.contains(
            "GlazeButton(\"Scan a family QR code\", icon: .camera, size: .small, enabled: !model.busy) { model.scanFamilyCode() }"))
        // The page never opens the join part and the camera by itself.
        #expect(!view.contains("model.startJoin() model.scanning = true"))
        #expect(SourceText.count("model.scanFamilyCode()", in: raw) == 2)
        let model = SourceText.squashed(try SourceText.read(Self.model))
        #expect(model.contains(
            "func startJoin(_ link: FamilyLink? = nil, session: Session? = nil) -> Bool { scanning = false guard let link else { guard !busy, question == nil else { return false } error = nil joining = true return true }"))
        #expect(model.contains("func scanFamilyCode() { guard startJoin() else { return } scanning = true }"))
    }

    /// Red when a failure of a received sign-in's request leaves the hand-over's row on its last step
    /// ("Allowed, signing in"): every `catch` of that request clears it, the certificate question's
    /// too.
    @Test func everyFailureOfAReceivedSignInClearsItsStep() throws {
        let model = try SourceText.read(Self.model)
        let finish = try SourceText.slice(model, from: "    private func finish(", to: "\n    }\n")
        let received = String(finish[try SourceText.at("        case .received(let signIn):", in: finish)...])
        let catches = Array(received.components(separatedBy: "} catch").dropFirst())
        #expect(catches.count == 3)
        for (index, body) in catches.enumerated() {
            let first = body.components(separatedBy: "\n").dropFirst().first ?? ""
            #expect(first.trimmingCharacters(in: .whitespaces) == "handOverStep = nil", "catch \(index + 1): \(first)")
        }
        #expect(SourceText.squashed(received).contains("} catch let untrusted as ServerTrust.Untrusted { handOverStep = nil question = untrusted }"))
    }

    /// Red when an edit of the address, its scheme or the home stops ending the form's tie to its
    /// saved server, or the app's own split of a pasted scheme counts as an edit.
    @Test func everyAddressEditIsToldOnceAndOnlyWhenSomethingChanged() throws {
        let raw = try SourceText.read(Self.model)
        let model = SourceText.squashed(raw)
        for anchor in [
            "guard !splitting else { return } let before = (scheme, oldValue) splitting = true typed() splitting = false",
            "if before != (scheme, rest) { linkEdited() }",
            "private(set) var scheme: ServerAddress.Scheme = .https { didSet { if !splitting, scheme != oldValue { linkEdited() } } }",
            "var homeAddress = \"\" { didSet { if homeAddress != oldValue { linkEdited() } } }",
            "@ObservationIgnored private var splitting = false",
            "linkState.edited(address: url?.absoluteString, home: home, addressNonBlank: !rest.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)",
            "linkState = LinkFormState(form.server == nil ? .fresh : .keptServer)",
            "linkState.filled(LinkAnchor(main: server.address.absoluteString, home: server.homeAddress?.absoluteString, username: server.username))",
        ] {
            #expect(model.contains(anchor), "\(anchor)")
        }
        // The three setters and the function itself.
        #expect(SourceText.count("linkEdited()", in: raw) == 4)
        let fill = try SourceText.at("takesApiKeys = server.takesApiKeys", in: raw)
        let filled = try SourceText.at("linkState.filled(", in: raw)
        #expect(fill < filled)
    }

    /// Red when a hand-over's sealed sign-in is sent without its check, a refusal still sends it, an
    /// adopted address is not re-tagged, or a kept server's form adds the server beside itself.
    @Test func aReceivedSignInIsCheckedBeforeItsRequest() throws {
        let model = try SourceText.read(Self.model)
        let finish = try SourceText.slice(model, from: "    private func finish(", to: "\n    }\n")
        #expect(finish.hasPrefix(
            "    private func finish(_ result: HandOverResult, session: Session, host: String?, attempt: String) async {\n        guard !Task.isCancelled else { return }\n        waiting = false\n"))
        let waiting = try SourceText.at("waiting = false", in: finish)
        let branch = try SourceText.at("switch result {", in: finish)
        #expect(waiting < branch)
        let check = try SourceText.at("let check = linkState.received(signIn.facts)", in: finish)
        let request = try SourceText.at("let fields = Session.Form(", in: finish)
        #expect(check < request)
        let refuse = try SourceText.slice(finish, from: "            case .refuse(let why):", to: "return")
        #expect(refuse.contains("leaveJoin()"))
        #expect(refuse.contains("linkState.noted(why.words)"))
        #expect(!refuse.contains("keepServer"))
        let squashed = SourceText.squashed(finish)
        for anchor in [
            "forgetLastServer(linkUsername: nil) rest = address homeAddress = home linkState.adopted(check) attempt = sentAddress",
            "if check == .keep(useForm: true) {",
            "let purpose: Session.Purpose = form.server.map { .signInAgain($0.id) } ?? .signIn",
            "_ = try await session.keepServer(fields, for: purpose)",
        ] {
            #expect(squashed.contains(anchor), "\(anchor)")
        }
    }

    /// Red when the forget skips the shared field list, so a field of the last server could reach the next.
    @Test func theForgetGoesThroughLinkFields() throws {
        let raw = try SourceText.read(Self.model)
        let model = SourceText.squashed(raw)
        #expect(model.contains(
            "let fields = LinkFields(headers: headerList, gatewayKind: gatewayKind, gatewaySaved: gatewaySaved, gatewayFirst: gatewayFirst, gatewaySecond: gatewaySecond, legacy: legacy, useApiKey: useApiKey, apiKey: apiKey, password: password, joinName: joinName, joinPassword: joinPassword, joinAgain: joinAgain, username: username, takesApiKeys: takesApiKeys, certificatePicked: certificate != nil) .forgotten(linkUsername: linkUsername)"))
        for write in ["headerList = fields.headers", "state.kind = fields.gatewayKind", "useGatewayForm(state)", "gatewaySaved = fields.gatewaySaved",
                      "gatewayFirst = fields.gatewayFirst", "gatewaySecond = fields.gatewaySecond", "legacy = fields.legacy",
                      "useApiKey = fields.useApiKey", "apiKey = fields.apiKey", "password = fields.password", "joinName = fields.joinName",
                      "joinPassword = fields.joinPassword", "joinAgain = fields.joinAgain", "username = fields.username",
                      "takesApiKeys = fields.takesApiKeys", "if !fields.certificatePicked { certificate = nil }"] {
            #expect(model.contains(write), "\(write)")
        }
        // Its declaration, the link's forget and the received sign-in's.
        #expect(SourceText.count("forgetLastServer(linkUsername:", in: raw) == 3)
    }

    /// Red when a refused link leaves its text in the field, the camera's link moves focus, the note
    /// leaves the address, or the address can change while a sign-up is held.
    @Test func aRefusedLinkLeavesTheFieldAsItWasAndSaysWhyUnderIt() throws {
        let model = try SourceText.read(Self.model)
        #expect(SourceText.count("addressResets += 1", in: model) == 1)
        let type = try SourceText.slice(model, from: "    func typeAddress(", to: "\n    }\n")
        #expect(type.contains("if !startJoin(link, session: session) { addressResets += 1 }"))
        #expect(model.contains("var linkNote: String? { linkState.note }"))
        let raw = try SourceText.read(Self.view)
        let view = SourceText.squashed(raw)
        #expect(view.contains("focus: $focus, field: .address, readOnly: model.holdsSignUp) // A refused link pasted here leaves the field drawn again without it, with focus back in it. .id(model.addressResets) .task(id: model.addressResets) { if model.addressResets > 0 { focus = .address } }"))
        let address = try SourceText.at("field: .address, readOnly: model.holdsSignUp)", in: raw)
        let note = try SourceText.at("if let note = model.linkNote {", in: raw)
        let name = try SourceText.at("if model.showsName {", in: raw)
        #expect(address < note && note < name)
        #expect(view.contains("if let note = model.linkNote { FormNote(.info, note)"))
        #expect(view.contains(".buttonStyle(.plain) .disabled(model.holdsSignUp)"))
        let groups = try SourceText.read("Octo/SignIn/FormGroups.swift")
        #expect(groups.contains(".disabled(readOnly)"))
    }

    /// Red when the held sign-up forgets the address it was made at, the sign-in after it goes to the
    /// field's address, or leaving the link loses the username the server chose.
    @Test func aHeldSignUpSignsInAtItsOwnMainAddressAndKeepsItsName() throws {
        let model = try SourceText.read(Self.model)
        #expect(model.contains("private var signedUp: (username: String, password: String, main: URL)?"))
        #expect(model.contains("signedUp = (name, chosen, url)"))
        #expect(SourceText.squashed(model).contains("let fields = Session.Form( address: account.main, homeAddress: home,"))
        let leave = try SourceText.slice(model, from: "    private func leaveJoin() {", to: "\n    }\n")
        let keepName = try SourceText.at("if let account = signedUp { username = account.username }", in: leave)
        let clear = try SourceText.at("signedUp = nil", in: leave)
        #expect(keepName < clear)
        #expect(leave.contains("linkState.left()"))
        #expect(model.contains("var holdsSignUp: Bool { signedUp != nil }"))
    }

    /// Red when a way out of a link can be taken while a request commits, a hand-over waiting for
    /// Allow can no longer be left, a cancelled one shows "expired", or a request starts in no phase.
    @Test func waitingCanBeLeftAndCommittingCannot() throws {
        let model = try SourceText.read(Self.model)
        #expect(model.contains("var committing: Bool { busy && !waiting }"))
        let stop = try SourceText.slice(model, from: "    func stopJoin() {", to: "\n    }\n")
        #expect(stop.hasPrefix("    func stopJoin() {\n        guard !committing else { return }\n"))
        for reset in ["receiving?.cancel()", "receiving = nil", "busy = false", "waiting = false", "handOverStep = nil", "leaveJoin()"] {
            #expect(stop.contains(reset), "\(reset)")
        }
        #expect(SourceText.count("busy = true", in: model) == 3)
        #expect(SourceText.count("waiting = received == nil", in: model) == 1)
        let receive = try SourceText.slice(model, from: "    func receive(_ session: Session)", to: "\n    }\n")
        let squashedReceive = SourceText.squashed(receive)
        #expect(squashedReceive.contains("busy = true // Only a redeem waits for the other device; a sign-in received already commits at once. waiting = received == nil"))
        #expect(squashedReceive.contains("client.session.finishTasksAndInvalidate() guard !Task.isCancelled else { return } await self?.finish("))
        for start in ["    func submit(_ session: Session", "    func signUp(_ session: Session)"] {
            let body = try SourceText.slice(model, from: start, to: "\n    }\n")
            #expect(body.contains("busy = true\n        waiting = false"), "\(start)")
        }
        let view = SourceText.squashed(try SourceText.read(Self.view))
        #expect(view.contains("guard !model.committing else { return } if model.holdsSignUp { model.stopJoin() } else { dismiss() }"))
        #expect(view.contains(".buttonBorderShape(.circle) .disabled(model.committing)"))
        #expect(view.contains("GlazeButton(\"Sign in with a password\", size: .small, enabled: !model.committing, fillWidth: true) { model.stopJoin() }"))
    }

    /// Red when a link from outside can replace an open sign-in, an ask buries a link's form or opens
    /// over an open sign-in, or an ask's message is left for a cover the person opened.
    @Test func noOutsideLinkOrAskReplacesAnOpenSignIn() throws {
        let hub = SourceText.squashed(try SourceText.read("Octo/Family/FamilyHub.swift"))
        #expect(hub.contains(
            "guard !router.hasOpenSignIn else { router.noteLink(SignInWords.linkWhileSignInOpen) router.show(SignInWords.linkWhileSignInOpen, action: \"Use it\") { [weak self] in self?.open(.join(link)) } return } joinOpens += 1 router.setAside() router.signIn = .join(familyAppLink(link), opened: joinOpens)"))
        let router = SourceText.squashed(try SourceText.read("Octo/App/Router.swift"))
        for anchor in [
            "@ObservationIgnored weak var session: Session?",
            "@ObservationIgnored private var askedCover: SignInModel.Form.ID?",
            "let showingJoin = if case .join? = signIn { true } else { false }",
            "if askOpens(hasOpenSignIn: hasOpenSignIn, showingJoin: showingJoin) { signIn = .again(server, note: note) askedCover = signIn?.id } else { show(SignInWords.askWhileSignInOpen(server.name), action: \"Sign in\") { [weak self] in self?.askSignIn(serverId: serverId, note: note) } }",
            "else { return } show(SignInWords.askWhileSignInOpen(kept.name), action: \"Sign in\") { [weak self] in self?.askSignIn(serverId: kept.id, note: note) }",
        ] {
            #expect(router.contains(anchor), "\(anchor)")
        }
        let view = SourceText.squashed(try SourceText.read(Self.view))
        #expect(view.contains(".onChange(of: model.signInOpen, initial: true) { router.markSignIn(model, open: model.signInOpen) }"))
        #expect(view.contains(".onDisappear { router.markSignIn(model, open: false) model.leave()"))
        let model = try SourceText.read(Self.model)
        #expect(SourceText.squashed(model).contains(
            "OctoKit.signInOpen(busy: busy, question: question != nil, holdsReceived: received != nil, holdsSignUp: signedUp != nil)"))
        #expect(model.contains("    func noteLink(_ words: String) {\n        linkState.noted(words)\n    }"))
    }

    /// Red when the router decides an ask, its cover or its open sign-ins in its own text again
    /// instead of calling OctoKit's rules (`askedCoverKept`, `AskServers.target`, `askLeftOnReplace`,
    /// `OpenSignIns`), which `SignInCoversTests` tests.
    @Test func theRoutersAskDecisionsAreOctoKits() throws {
        let raw = try SourceText.read("Octo/App/Router.swift")
        let router = SourceText.squashed(raw)
        for anchor in [
            "var signIn: SignInModel.Form? { didSet { askedCover = askedCoverKept(askedCover, showing: signIn?.id) } }",
            "@ObservationIgnored private var openSignIns = OpenSignIns<SignInModel>()",
            "func markSignIn(_ model: SignInModel, open: Bool) { openSignIns.mark(model, open: open) }",
            "var hasOpenSignIn: Bool { openSignIns.anyOpen() }",
            "func noteLink(_ words: String) { for model in openSignIns.forms { model.noteLink(words) } }",
            "private var askServers: AskServers? { session.map { AskServers(kept: $0.servers, activeId: $0.activeId, signedIn: $0.client != nil) } }",
            "func askSignIn(serverId: String, note: String?) { guard let server = askServers?.target(serverId) else { return }",
            "func setAside() { guard case .again(let server, let note)? = signIn, let servers = askServers, let kept = askLeftOnReplace(asked: askedCover, showing: signIn?.id, server: server.id, servers: servers) else { return }",
        ] {
            #expect(router.contains(anchor), "\(anchor)")
        }
        // None of the rules is left in the router's own text.
        for gone in ["WeakSignIn", "ObjectIdentifier", ".filter", "session.activeId", "session.servers", "!= askedCover", "== askedCover"] {
            #expect(!raw.contains(gone), "\(gone)")
        }
    }

    /// Red when Octo's own asks open their form past the router's check.
    @Test func octosOwnAsksGoThroughTheRouter() throws {
        let shell = try SourceText.read("Octo/Shell/MainShell.swift")
        #expect(shell.contains(
            "if case .needsPassword(let server, let note)? = session.takeStartAsk() { router.askSignIn(serverId: server.id, note: note) }"))
        #expect(!shell.contains("router.signIn = .again("))
        let servers = try SourceText.read("Octo/Settings/Server/ServersSection.swift")
        #expect(servers.contains("case .needsPassword(_, let note): router.askSignIn(serverId: server.id, note: note)"))
        let app = try SourceText.read("Octo/OctoApp.swift")
        #expect(app.contains("        let router = Router()\n        router.session = session\n"))
    }

    /// Red when the sign-in cover's page is not keyed by its form, so a link or an ask that replaces the
    /// form showing keeps the last page and its model.
    @Test func aReplacedFormGetsAPageOfItsOwn() throws {
        let shell = SourceText.squashed(try SourceText.read("Octo/Shell/MainShell.swift"))
        #expect(shell.contains(
            ".fullScreenCover(item: Bindable(router).signIn) { form in SignInView(form: form, session: session) { notice in if let notice { router.done(notice) } } .id(form.id) .environment(drift) }"))
    }

    /// Red when a call a commit makes can wait without a total limit, so a held way out never ends.
    @Test func everyCommittingCallHasATimeLimit() throws {
        let session = SourceText.squashed(try SourceText.read("Octo/App/Session.swift"))
        #expect(session.contains("let own = try ownSession(main: address, home: home, picked: certificate, saved: nil, seconds: 30)"))
        #expect(session.contains(
            "let own = try ownSession(main: main, home: home, picked: form.certificate, saved: form.certificate == nil && !form.removesCertificate ? before : nil, seconds: 30)"))
    }

    /// Red when a walk step for the asks goes missing, or the stored action is read from the live
    /// message, which its own timer clears.
    @Test func theDebugStepsForTheAsksAreThere() throws {
        let hooks = SourceText.squashed(try SourceText.read("Octo/Settings/Server/AccountHooks.swift"))
        for anchor in [
            "case \"ask\": if let named { router.askSignIn(serverId: named.id, note: nil) }",
            "case \"toast\":",
            "DriveToast.held = router.toast",
            "case \"toastAction\": DriveToast.held?.perform?()",
            "case \"close\": DriveToast.coverGone = false router.signIn = nil",
            "let line = DriveToast.coverGone ? \"cover closed\" : \"cover still up\"",
            "static var held: Router.Toast? static var coverGone = true",
        ] {
            #expect(hooks.contains(anchor), "\(anchor)")
        }
        let view = SourceText.squashed(try SourceText.read(Self.view))
        #expect(view.contains("#if DEBUG DriveToast.coverGone = true #endif"))
    }
}
