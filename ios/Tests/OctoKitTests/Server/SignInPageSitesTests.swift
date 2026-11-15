import Foundation
import Testing

/// The iPhone sign-in page's wiring, checked in its own sources: the app target has no test target,
/// so what the page and its model do with the shared rules is pinned here by their text.
@Suite struct SignInPageSitesTests {
    private static let view = "Octo/SignIn/SignInView.swift"
    private static let groups = "Octo/SignIn/FormGroups.swift"
    private static let model = "Octo/SignIn/SignInModel.swift"

    /// Red when the holder drops the callout or the key scroll on its way to or from the shared state.
    @Test func theHolderCarriesTheCalloutBothWays() throws {
        let model = try SourceText.read(Self.model)
        let getter = try SourceText.slice(model, from: "    private var gatewayForm: GatewayFormState {", to: "\n    }\n")
        for field in ["callout: callout", "calloutPending: calloutPending", "keyPending: keyPending"] {
            #expect(getter.contains(field), "\(field)")
        }
        let setter = try SourceText.slice(model, from: "    private func useGatewayForm(_ state: GatewayFormState) {", to: "\n    }\n")
        for field in ["callout = state.callout", "calloutPending = state.calloutPending", "keyPending = state.keyPending"] {
            #expect(setter.contains(field), "\(field)")
        }
    }

    /// Red when an error write stops calming the callout, or a failure calls the group out before its
    /// words are written (the write would then end the callout it had just started).
    @Test func everyFailureWritesItsWordsBeforeTheGroupReacts() throws {
        let model = try SourceText.read(Self.model)
        #expect(model.contains("    var error: String? {\n        didSet { useGatewayForm(OctoKit.calm(gatewayForm)) }\n    }"))
        let lines = model.components(separatedBy: "\n")
        var calls = 0
        for (index, line) in lines.enumerated() where line.contains("met(") && !line.contains("func met(") {
            calls += 1
            #expect(index > 0 && lines[index - 1].contains("error = "), "line \(index + 1): \(line)")
        }
        #expect(calls == 6)
        #expect(model.contains("        if let words { error = words }\n        if let gateway { met(sentAddress, gateway) }"))
    }

    /// Red when an attempt starts without dropping the last one's callout, or a second tap can start
    /// one while one runs.
    @Test func everyAttemptStartsFresh() throws {
        let model = try SourceText.read(Self.model)
        #expect(model.contains("        guard !busy, ready, let url else { return }\n        useGatewayForm(OctoKit.sending(gatewayForm))"))
        for start in ["    func submit(_ session: Session", "    func signUp(_ session: Session)", "    func receive(_ session: Session)"] {
            let body = try SourceText.slice(model, from: start, to: "\n    }\n")
            let guarded = try SourceText.at("!busy", in: body)
            let sending = try SourceText.at("useGatewayForm(OctoKit.sending(gatewayForm))", in: body)
            let firstError = try SourceText.at("error = ", in: body)
            #expect(guarded < sending && sending < firstError, "\(start)")
        }
    }

    /// Red when a header row is written outside the reducer that keeps every row's id.
    @Test func everyHeaderWriteGoesThroughHeaderList() throws {
        let model = try SourceText.read(Self.model)
        #expect(model.contains("    typealias Header = HeaderDraftRow"))
        #expect(model.contains("    private var headerList = HeaderList.empty\n    var headers: [Header] { headerList.rows }"))
        #expect(model.contains("headerList.replaced(savedHeaderNames.map { HeaderRules.Row(name: $0, value: \"\", saved: true) })"))
        for op in ["headerList.added()", "headerList.renamed(id, name)", "headerList.valued(id, value)", "headerList.remove(id)"] {
            #expect(model.contains(op), "\(op)")
        }
        for banned in ["headers.append(", "headers.remove", "headers ="] {
            #expect(!model.contains(banned), "\(banned)")
        }
    }

    /// Red when the header rows are drawn by position again, Add header focuses a row in the action
    /// that adds it, or Remove leaves a field focused whose return key may change.
    @Test func headerRowsAreKeyedAndFocusFollowsThem() throws {
        let view = try SourceText.read(Self.view)
        let groups = try SourceText.read(Self.groups)
        #expect(view.contains("ForEach(Array(model.headers.enumerated()), id: \\.element.id) { i, header in"))
        #expect(!view.contains("ForEach($model.headers)"))
        #expect(view.contains("focusNewHeader = model.addHeader()"))
        #expect(view.contains("remove: { model.removeHeader(header.id); focus = nil }"))
        #expect(view.contains("valueNext: i + 1 < model.headers.count ? .headerName(model.headers[i + 1].id) : nil"))
        #expect(SourceText.squashed(groups).contains(
            ".task(id: focusNewHeader.wrappedValue) { if focusNewHeader.wrappedValue == id { focusNewHeader.wrappedValue = nil focus.wrappedValue = .headerName(id) } }"))
        #expect(groups.contains("onSubmit: { focus.wrappedValue = .headerValue(id) }"))
        #expect(groups.contains("submitLabel: valueNext == nil ? .done : .next"))
        #expect(groups.contains("onSubmit: { if let next = valueNext { focus.wrappedValue = next } }"))
        #expect(groups.contains("RemoveButton(name: SignInWords.removeHeader(header.name), action: remove)"))
        for path in [Self.view, Self.groups, Self.model] {
            let text = try SourceText.read(path)
            #expect(!text.contains("\"Remove header"), "\(path)")
        }
    }

    /// Red when a field's return key can change under it, a Next has no drawn target, or a field
    /// with no target is not Done.
    @Test func returnKeysFollowTheNextList() throws {
        let view = SourceText.squashed(try SourceText.read(Self.view))
        let anchors = [
            "struct JoinLabels: Hashable { let joining: Bool let invite: Bool let handOver: Bool let scanning: Bool }",
            ".onChange(of: JoinLabels(joining: model.joining, invite: model.invite != nil, handOver: model.handOver != nil, scanning: model.scanning)) { focus = nil }",
            "if model.joining && model.invite == nil && !model.showsName { return nil } return { if model.showsName { focus = .label } else if model.invite != nil { focus = .joinName } else { focus = .username } }",
            "submitLabel: addressNext == nil ? .done : .next, leading: { schemeToggle }, onSubmit: addressNext,",
            "if model.joining && model.invite == nil { return nil } return { focus = model.invite != nil ? .joinName : .username }",
            "submitLabel: nameNext == nil ? .done : .next,",
            "onSubmit: nameNext,",
            "if model.headers.isEmpty { return nil } return { if let first = model.headers.first { focus = .headerName(first.id) } }",
            "submitLabel: homeNext == nil ? .done : .next, onSubmit: homeNext,",
            "onSubmit: { focus = model.useApiKey ? .apiKey : .password }",
            "onSubmit: { focus = .joinPassword }",
            "onSubmit: { focus = .joinAgain }",
            "onSubmit: { focus = .gatewaySecond }",
        ]
        for anchor in anchors {
            #expect(view.contains(anchor), "\(anchor)")
        }
    }

    /// Red when a field's title, Optional tag, placeholder, content type, keyboard or return key drifts
    /// from the page's field table.
    @Test func everyFieldIsAsTheFieldTableSays() throws {
        let view = SourceText.squashed(try SourceText.read(Self.view))
        let anchors = [
            "FormFieldRow(title: SignInWords.serverAddress, value: model.rest, onChange: { model.typeAddress($0, session: session) }, placeholder: \"music.example.com\", contentType: .URL, keyboardType: .URL, submitLabel: addressNext == nil ? .done : .next,",
            "FormFieldRow(title: SignInWords.serverName, optional: true, value: model.label, onChange: { model.label = $0 }, placeholder: SignInWords.namePlaceholder, submitLabel: nameNext == nil ? .done : .next, capitalization: .words, autocorrect: true,",
            "FormFieldRow(title: SignInWords.username, optional: model.useApiKey, value: model.username, onChange: { model.username = $0 }, placeholder: model.useApiKey ? \"\" : SignInWords.required, contentType: .username, submitLabel: .next,",
            "FormFieldRow(title: SignInWords.password, value: model.password, onChange: { model.password = $0 }, placeholder: model.keepsPassword ? SignInWords.unchanged : SignInWords.required, secret: true, contentType: .password, submitLabel: .done,",
            "FormFieldRow(title: SignInWords.apiKey, value: model.apiKey, onChange: { model.apiKey = $0 }, placeholder: model.keepsKey ? SignInWords.unchanged : SignInWords.required, secret: true, submitLabel: .done,",
            "FormFieldRow(title: labels.first, value: model.gatewayFirst, onChange: { model.gatewayFirst = $0 }, placeholder: keptPage ? SignInWords.unchanged : SignInWords.required, keyboardType: model.gatewayKind == .cloudflareToken ? .asciiCapable : .default, submitLabel: .next,",
            "FormFieldRow(title: labels.second, value: model.gatewaySecond, onChange: { model.gatewaySecond = $0 }, placeholder: keptPage ? SignInWords.unchanged : SignInWords.required, secret: true, submitLabel: gatewaySubmit == nil ? .done : .go,",
            "FormFieldRow(title: SignInWords.homeAddress, optional: true, value: model.homeAddress, onChange: { model.homeAddress = $0 }, placeholder: SignInWords.homePlaceholder, keyboardType: .URL, submitLabel: homeNext == nil ? .done : .next,",
            "FormFieldRow(title: SignInWords.yourName, value: model.joinName, onChange: { model.joinName = $0 }, placeholder: SignInWords.required, contentType: .name, submitLabel: .next, capitalization: .words, autocorrect: true,",
            "FormFieldRow(title: SignInWords.password, description: model.strength, value: model.joinPassword, onChange: { model.joinPassword = $0 }, placeholder: SignInWords.required, secret: true, contentType: .newPassword, submitLabel: .next,",
            "FormFieldRow(title: SignInWords.passwordAgain, value: model.joinAgain, onChange: { model.joinAgain = $0 }, placeholder: SignInWords.required, secret: true, contentType: .newPassword, submitLabel: .done,",
        ]
        for anchor in anchors {
            #expect(view.contains(anchor), "\(anchor)")
        }
        let groups = SourceText.squashed(try SourceText.read(Self.groups))
        #expect(groups.contains(
            "PlainEntry(label: SignInWords.headerName, value: header.name, onChange: rename, placeholder: SignInWords.headerName, keyboardType: .asciiCapable, submitLabel: .next,"))
        #expect(groups.contains(
            "SecretEntry(label: SignInWords.headerValue, value: header.value, onChange: setValue, placeholder: header.saved ? SignInWords.unchanged : SignInWords.headerValue, submitLabel: valueNext == nil ? .done : .next,"))
    }

    /// Red when a field that submits loses its action, or the second sign-in page field sends the
    /// wrong request while joining.
    @Test func theSubmittingFieldsSubmit() throws {
        let view = SourceText.squashed(try SourceText.read(Self.view))
        #expect(SourceText.count("onSubmit: { Task { await model.submit(session, sync: sync) } }, focus: $focus, field: .password", in: view) == 1)
        #expect(SourceText.count("onSubmit: { Task { await model.submit(session, sync: sync) } }, focus: $focus, field: .apiKey", in: view) == 1)
        #expect(view.contains("onSubmit: { Task { await model.signUp(session) } }, focus: $focus, field: .joinAgain"))
        #expect(view.contains("submitLabel: gatewaySubmit == nil ? .done : .go, onSubmit: gatewaySubmit, focus: $focus, field: .gatewaySecond"))
        #expect(view.contains(
            "if model.invite != nil { return { Task { await model.signUp(session) } } } if model.handOver != nil { return { model.receive(session) } } if model.joining { return nil } return { Task { await model.submit(session, sync: sync) } }"))
    }

    /// Red when the second sign-in page field reads Go with nothing to send, or its key can change
    /// under it when a hand-over comes or goes.
    @Test func theSecondSignInPageFieldIsDoneWithNothingToSend() throws {
        let raw = try SourceText.read(Self.view)
        let view = SourceText.squashed(raw)
        #expect(view.contains("submitLabel: gatewaySubmit == nil ? .done : .go, onSubmit: gatewaySubmit, focus: $focus, field: .gatewaySecond"))
        #expect(!raw.contains("submitLabel: .go"))
        let submit = try SourceText.slice(raw, from: "    private var gatewaySubmit: (() -> Void)? {", to: "\n    }\n")
        let invite = try SourceText.at("if model.invite != nil {", in: submit)
        let handOver = try SourceText.at("if model.handOver != nil {", in: submit)
        let nothing = try SourceText.at("if model.joining { return nil }", in: submit)
        #expect(invite < nothing && handOver < nothing)
        #expect(view.contains(
            "JoinLabels(joining: model.joining, invite: model.invite != nil, handOver: model.handOver != nil, scanning: model.scanning)"))
    }

    /// Red when the certificate row stops naming its last valid day through the shared words, a saved
    /// certificate's file is opened on the main actor, or a late read lands on a form that has moved on.
    @Test func theCertificateRowShowsItsDayReadOffTheMainActor() throws {
        let view = SourceText.squashed(try SourceText.read(Self.view))
        #expect(view.contains(
            "FormTrailingRow(title: SignInWords.certTitle, description: certificateRowLine(name: model.certificateShown, expires: model.certificateExpiry, format: { $0.formatted(date: .abbreviated, time: .omitted) }))"))
        #expect(view.contains(
            "let expires = try CertificateCheck.validate(pending.data, password: certificatePassword) model.certificate = (pending.name, pending.data, certificatePassword, expires)"))
        let line = try SourceText.read("OctoKit/Server/CertificateLine.swift")
        #expect(line.contains("return SignInWords.certificateLine(name, date: format(expires))"))
        let check = try SourceText.read("Octo/SignIn/CertificateCheck.swift")
        #expect(check.contains("SecIdentityCopyCertificate(found as! SecIdentity, &certificate)"))
        #expect(check.contains("SecCertificateCopyNotValidAfterDate(certificate)"))
        #expect(check.contains("return try? validate(data, password: password)"))
        let raw = try SourceText.read(Self.model)
        let model = SourceText.squashed(raw)
        for anchor in [
            "var certificate: (name: String, data: Data, password: String, expires: Date?)?",
            "if hadCertificate, let server = saved { readSavedExpiry(server.id) }",
            "let expires = await Task.detached(priority: .utility) { CertificateCheck.savedExpiry(forServer: id) }.value",
            "guard !Task.isCancelled, let self, self.hadCertificate, !self.removesCertificate, self.certificate == nil else { return } self.savedCertificateExpiry = expires",
            "receiving?.cancel() expiryRead?.cancel()",
        ] {
            #expect(model.contains(anchor), "\(anchor)")
        }
        #expect(SourceText.count("CertificateCheck.savedExpiry(", in: raw) == 1)
    }

    /// Red when the saved certificate's file is opened for a model SwiftUI builds and never shows: the
    /// read is asked for once, when the page appears, and never in `init`.
    @Test func theSavedCertificatesDayIsReadOnceThePageAppears() throws {
        let model = try SourceText.read(Self.model)
        let initializer = try SourceText.slice(model, from: "    init(form: Form, session: Session) {", to: "\n    }\n")
        #expect(!initializer.contains("readSavedExpiry("))
        let appeared = SourceText.squashed(try SourceText.slice(model, from: "    func appeared() {", to: "\n    }\n"))
        #expect(appeared.contains(
            "guard !expiryAsked else { return } expiryAsked = true if hadCertificate, let server = saved { readSavedExpiry(server.id) }"))
        #expect(model.contains("@ObservationIgnored private var expiryAsked = false"))
        // Its declaration and its one call.
        #expect(SourceText.count("readSavedExpiry(", in: model) == 2)
        let view = SourceText.squashed(try SourceText.read(Self.view))
        #expect(view.contains(".onAppear { model.serverSwitch = serverSwitch model.appeared()"))
        #expect(SourceText.count("model.appeared()", in: view) == 1)
    }

    /// Red when a field skips the row that applies focus, a raw text field comes back to the page,
    /// or an entry gets a second focus binding.
    @Test func everyFieldIsAFormFieldRowWithOneFocusBinding() throws {
        let view = try SourceText.read(Self.view)
        let groups = try SourceText.read(Self.groups)
        let rows = SourceText.count("FormFieldRow(", in: view)
        #expect(rows == 11)
        #expect(SourceText.count("focus: $focus, field: .", in: SourceText.squashed(view)) == rows)
        #expect(SourceText.count(".focused(", in: view) == 0)
        #expect(SourceText.count("TextField(", in: view) == 0)
        #expect(SourceText.count("SecureField(", in: view) == 1)
        #expect(view.contains("SecureField(SignInWords.certPasswordField, text: $certificatePassword)"))
        #expect(SourceText.count(".focused(focus, equals: field)", in: groups) == 2)
        #expect(SourceText.count(".focused(", in: groups) == 2)
        let secret = try SourceText.slice(groups, from: "struct SecretEntry: View {", to: ".focused(focus, equals: field)")
        let branches = try SourceText.slice(secret, from: "Group {", to: "SecureField(")
        #expect(!branches.contains(".focused("))
        #expect(SourceText.count("leading: {", in: view) == 1)
        #expect(view.contains("leading: { schemeToggle }"))
    }

    /// Red when VoiceOver can reach the page behind the camera, or a certificate question asked while
    /// the camera is open is drawn under it.
    @Test func theCameraCoversThePageForVoiceOverAndTheQuestionCoversTheCamera() throws {
        let view = SourceText.squashed(try SourceText.read(Self.view))
        let back = try SourceText.at(".overlay(alignment: .topLeading) {", in: view)
        let snappy = try SourceText.at(".animation(.snappy, value: model.advancedOpen)", in: view)
        let hidden = try SourceText.at(".accessibilityHidden(model.scanning)", in: view)
        let scanner = try SourceText.at(".overlay { if model.scanning {", in: view)
        let question = try SourceText.at(".overlay { if let question = model.question {", in: view)
        #expect(back < snappy && snappy < hidden && hidden < scanner && scanner < question)
    }

    /// Red when a callout moves inside Advanced (closing it would end the callout), loses its focus
    /// flags or its markers, or the keyboard tracker clears before the keyboard has gone.
    @Test func theCalloutRunsAtTheHostWithItsFlags() throws {
        let raw = try SourceText.read(Self.view)
        let view = SourceText.squashed(raw)
        let host = try SourceText.slice(raw, from: "    private var scrolled: some View {", to: "\n    }\n")
        #expect(host.contains(".task(id: CalloutKey(pending: model.calloutPending, open: model.advancedOpen)) { await gatewayCallout(proxy) }"))
        #expect(host.contains(".task(id: model.keyPending) { await keyCallout(proxy) }"))
        #expect(SourceText.count("CalloutKey(", in: raw) == 1)
        let advanced = try SourceText.slice(raw, from: "    private var advanced: some View {", to: "\n    }\n")
        #expect(!advanced.contains("CalloutKey") && !advanced.contains("gatewayCallout"))
        let anchors = [
            "guard model.calloutPending, model.advancedOpen else { return } let wasUp = keyboardUp await runCallout(.gateway, wait: wait, focusedAtStart: { focusedWhilePending }, keyboardWasUp: { wasUp },",
            "guard model.keyPending else { return } let wasUp = keyboardUp await runCallout(.key, wait: wait, focusedAtStart: { focusedWhileKeyPending }, keyboardWasUp: { wasUp },",
            ".onChange(of: focus) { _, now in guard now != nil else { return } if model.calloutPending { focusedWhilePending = true } if model.keyPending { focusedWhileKeyPending = true } wait.send(.focused) }",
            ".onChange(of: model.calloutPending) { _, pending in guard pending else { return } focusedWhilePending = false",
            ".onChange(of: model.keyPending) { _, pending in if pending { focusedWhileKeyPending = false } }",
            ".onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in keyboardUp = true }",
            ".onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidHideNotification)) { _ in keyboardUp = false wait.send(.hidden) }",
            "let landing = UnitPoint(x: 0, y: CGFloat(calloutLanding) / max(visibleHeight ?? 730, 1))",
            "proxy.scrollTo(\"gatewayMarker\", anchor: landing)",
            "let end = gatewayErrorFoot == nil ? \"gatewayEnd\" : \"gatewayErrorEnd\"",
            "proxy.scrollTo(end, anchor: .bottom)",
            "proxy.scrollTo(\"keyMarker\", anchor: landing)",
            "FormGroup(title: AdvancedGroup.gettingThere.title, marker: \"gatewayMarker\", outlined: model.calloutShowing) {",
            "{ $0.size.height } action: { height in gatewayHeight = height wait.send(.measured) } .calloutEnd(\"gatewayEnd\")",
            ".calloutMarker(\"keyMarker\")",
            "focus = nil UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)",
        ]
        for anchor in anchors {
            #expect(view.contains(anchor), "\(anchor)")
        }
        // The declaration's default and the hide notification's handler: nothing else clears it.
        #expect(SourceText.count("keyboardUp = false", in: raw) == 2)
        #expect(!raw.contains("scrollPosition"))
        #expect(!raw.contains("ScrollPosition("))
        let groups = SourceText.squashed(try SourceText.read(Self.groups))
        // The top mark is part of the layout: the scroll reads an overlay's place without its shift,
        // so a 68 point overlay above the title landed the title itself under the Back button.
        #expect(groups.contains(
            "func calloutMarker(_ id: String) -> some View { VStack(alignment: .leading, spacing: 0) { Color.clear.frame(height: 0).allowsHitTesting(false).accessibilityHidden(true).id(id) self } }"))
        #expect(!groups.contains("Color.clear.frame(height: 68)"))
        #expect(groups.contains(
            "overlay(alignment: .bottom) { Color.clear.frame(height: 8).alignmentGuide(.bottom) { $0[.top] } .allowsHitTesting(false).accessibilityHidden(true).id(id) }"))
    }

    /// Red when the wait OctoKit tests on Linux reaches for UIKit, or its marker height and the page's
    /// drift apart.
    @Test func theCalloutWaitStaysPortable() throws {
        let wait = try SourceText.read("OctoKit/Server/CalloutWait.swift")
        #expect(!wait.contains("UIApplication"))
        #expect(!wait.contains("import UIKit"))
        #expect(wait.contains("group <= visible - calloutLanding"))
        #expect(wait.contains("public let calloutLanding: Double = 78"))
        #expect(!SourceText.squashed(try SourceText.read(Self.view)).contains("68 /"))
    }

    /// Red when the view's height for a callout is read behind the scroll again (it took the safe
    /// area off twice and came out 82 points short, so groups that fit were shown by their end and
    /// every landing sat low), or read anywhere but the window's safe area.
    @Test func aCalloutMeasuresTheViewByTheWindowsSafeArea() throws {
        let view = SourceText.squashed(try SourceText.read(Self.view))
        #expect(view.contains(
            "private var visibleHeight: CGFloat? { let window = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first guard let height = window?.safeAreaLayoutGuide.layoutFrame.height, height > 0 else { return nil } return height }"))
        #expect(!view.contains("@State private var visibleHeight"))
        #expect(!view.contains("visibleHeight = "))
    }

    /// Red when a tall group's callout fits the whole group instead of the part it must show (its
    /// title through its error note), so at large text the first row's name lands under the Back
    /// button, or the end it shows is the group's last hint instead of its failure.
    @Test func aTallGroupsCalloutShowsItsTitleThroughItsFailure() throws {
        let view = SourceText.squashed(try SourceText.read(Self.view))
        #expect(view.contains("groupHeight: { (gatewayErrorFoot ?? gatewayHeight).map { Double($0) } },"))
        #expect(view.contains(
            "FormNote(.error, GatewayWords.inGroup(error)) .onGeometryChange(for: CGFloat.self) { $0.frame(in: .named(\"gatewayGroup\")).maxY } action: { foot in gatewayErrorFoot = foot wait.send(.measured) } .onDisappear { gatewayErrorFoot = nil } .calloutEnd(\"gatewayErrorEnd\")"))
        #expect(view.contains(".coordinateSpace(.named(\"gatewayGroup\")) .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height in gatewayHeight = height"))
        #expect(view.contains("gatewayHeight = nil gatewayErrorFoot = nil"))
    }

    /// Red when the error note, the outline or the pointer draws outside a callout, or VoiceOver is
    /// not told the failure.
    @Test func theCalloutDrawsOnlyWhileItCallsOut() throws {
        let model = try SourceText.read(Self.model)
        #expect(model.contains("var calloutShowing: Bool { callout && advancedOpen && error != nil }"))
        let view = SourceText.squashed(try SourceText.read(Self.view))
        #expect(view.contains("if model.calloutShowing, let error = model.error { FormNote(.error, GatewayWords.inGroup(error)) .onGeometryChange("))
        #expect(view.contains("Button { model.pointTo() } label: { FormNote(.error, GatewayWords.pointer) }"))
        #expect(view.contains("AccessibilityNotification.Announcement(GatewayWords.inGroup(error)).post()"))
    }

    /// Red when a page shown again takes its link a second time, or the same link opened again reuses
    /// the cover that is up.
    @Test func eachOpeningOfALinkHasItsOwnCover() throws {
        let view = try SourceText.read(Self.view)
        let model = try SourceText.read(Self.model)
        let hub = try SourceText.read("Octo/Family/FamilyHub.swift")
        let hooks = try SourceText.read("Octo/Settings/Server/AccountHooks.swift")
        #expect(view.contains(
            "if !model.didOpenJoin, case .join(let text, _) = model.form, let link = parseFamilyLink(text) { model.didOpenJoin = true; model.startJoin(link, session: session) }"))
        #expect(model.contains("@ObservationIgnored var didOpenJoin = false"))
        #expect(model.contains("case join(String, opened: Int)"))
        #expect(model.contains("case .join(let link, let opened): \"join.\\(opened).\" + link"))
        #expect(hub.contains("@ObservationIgnored private var joinOpens = 0"))
        let counted = try SourceText.at("joinOpens += 1", in: hub)
        let cover = try SourceText.at("router.signIn = .join(familyAppLink(link), opened: joinOpens)", in: hub)
        #expect(counted < cover)
        #expect(SourceText.count("router.signIn = .join(", in: hub) == 1)
        #expect(hooks.contains(
            "if let link = parseFamilyLink(rest) { accounts.family.open(.join(link)) } else { router.signIn = .join(rest, opened: 0) }"))
    }

    /// Red when "optional" is typed into the page's words (only the tag says it), or the Name field's
    /// placeholder stops reading the shared words.
    @Test func thePageSaysOptionalOnlyThroughTheTag() throws {
        for path in [Self.view, Self.groups, Self.model] {
            let text = try SourceText.read(path)
            for literal in SourceText.literals(text) {
                #expect(!literal.lowercased().contains("optional"), "\(path): \(literal)")
            }
        }
        let groups = try SourceText.read(Self.groups)
        #expect(groups.contains("Text(SignInWords.optionalTag)"))
        #expect(groups.contains("SignInWords.optionalName(title)"))
        let view = SourceText.squashed(try SourceText.read(Self.view))
        #expect(view.contains(
            "FormFieldRow(title: SignInWords.serverName, optional: true, value: model.label, onChange: { model.label = $0 }, placeholder: SignInWords.namePlaceholder,"))
        #expect(SourceText.count("SignInWords.namePlaceholder", in: view) == 1)
        #expect(!view.contains("Home or Work"))
    }

    /// Red when an API key over plain http across the internet is no longer the danger note, or a
    /// join reads the Method instead of Normal.
    @Test func theConnectionNoteWarnsForEitherSecretInTheOpen() throws {
        let model = SourceText.squashed(try SourceText.read(Self.model))
        #expect(model.contains(
            "switch joining ? .token : mode { case .legacyPassword: return .legacyInTheOpen case .apiKey: return .apiKeyInTheOpen case .token: return .tokenInTheOpen }"))
        let view = try SourceText.read(Self.view)
        #expect(view.contains("case .apiKeyInTheOpen: FormNote(.error, SignInWords.apiKeyInTheOpen)"))
        #expect(view.contains("case .legacyInTheOpen: FormNote(.error,"))
    }

    /// Red when the Method or a sign-in page field is read or written past the shared rules, or the
    /// legacy retry forces Advanced open again.
    @Test func theMethodAndTheSignInPageFieldsGoThroughTheSharedRules() throws {
        let raw = try SourceText.read(Self.model)
        let model = SourceText.squashed(raw)
        for anchor in [
            "var mode: AuthMode { authModeOf(legacy: legacy, apiKey: useApiKey) }",
            "useGatewayForm(methodPicked(gatewayForm, oldMode: oldMode, newMode: newMode, keepsKey: keepsKey))",
            "OctoKit.keepsPassword(keepsSecret: editing, savedMode: saved?.mode, mode: mode)",
            "OctoKit.keepsKey(keepsSecret: editing, savedMode: saved?.mode, mode: mode)",
            "useGatewayForm(kindPicked(gatewayForm, kind: kind))",
            "var gatewayFirst = \"\" { didSet { if gatewayFirst != oldValue { pageFieldEdited() } } }",
            "var gatewaySecond = \"\" { didSet { if gatewaySecond != oldValue { pageFieldEdited() } } }",
            "private func pageFieldEdited() { useGatewayForm(OctoKit.gatewayEdited(gatewayForm)) error = nil }",
        ] {
            #expect(model.contains(anchor), "\(anchor)")
        }
        let retry = try SourceText.slice(raw, from: "            case .retry:", to: "            case .unsafe:")
        #expect(!retry.contains("advancedOpen"))
        let view = try SourceText.read(Self.view)
        #expect(view.contains("advancedLayout(joining: model.joining, takesApiKeys: model.takesApiKeys, apiKeyOn: model.useApiKey,"))
        #expect(view.contains("pick: { model.pickMethod($0) }"))
    }
}
