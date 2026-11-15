import OctoKit
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The sign-in page: the octopus over Octo's name, the main card with the address and the sign-in
/// fields, the button, then Advanced and its three groups. Joining a family swaps the username and
/// password for the invite's sign-up or a sign-in handed over from another device. A failure that
/// met a sign-in page in front of the server opens Advanced and brings that group into view, with
/// the failure as its error note and a pointer under the button.
struct SignInView: View {
    @Environment(Session.self) private var session
    @Environment(ServerSync.self) private var sync
    @Environment(ServerSwitch.self) private var serverSwitch
    @Environment(Router.self) private var router
    @Environment(\.dismiss) private var dismiss
    @Environment(\.octoPalette) private var palette
    @Environment(\.octoReduceMotion) private var still
    @State private var model: SignInModel
    @State private var choosingCertificate = false
    @State private var pendingCertificate: (name: String, data: Data)?
    @State private var certificatePassword = ""
    @State private var certificateError: String?
    @FocusState private var focus: Field?
    /// The header row whose name takes focus once it is drawn: a row is never focused in the same
    /// action that adds it.
    @State private var focusNewHeader: SignInModel.Header.ID?
    /// The on-screen keyboard: up from the moment it starts to show until it has finished hiding.
    @State private var keyboardUp = false
    /// What the callouts wait for.
    @State private var wait = CalloutWait()
    /// The sign-in page group's height, from its title through its last note; nil while Advanced
    /// is shut.
    @State private var gatewayHeight: CGFloat?
    /// How far the callout's part of the group reaches, from its title through its error note;
    /// nil while no error note shows. The callout lands the title when this fits under the fade,
    /// so a tall group's hints may scroll on past the bottom while its title and failure show.
    @State private var gatewayErrorFoot: CGFloat?
    /// The scroll's height between the status bar and the home indicator: the key window's safe
    /// area, which the page fills. A callout reads it as it decides, once the keyboard has closed.
    private var visibleHeight: CGFloat? {
        let window = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first
        guard let height = window?.safeAreaLayoutGuide.layoutFrame.height, height > 0 else { return nil }
        return height
    }
    /// The address field's lock grows with the text size, as "https://" beside it does.
    @ScaledMetric(relativeTo: .subheadline) private var lockSize: CGFloat = 13
    /// A field took focus while the group waited to come into view: the callout then leaves the
    /// page where the person is.
    @State private var focusedWhilePending = false
    /// The same for the API key row.
    @State private var focusedWhileKeyPending = false
    /// Advanced was opened from its head, and its first group is still to be brought into view.
    @State private var revealsAdvanced = false
    /// Where Advanced's first row ends and where the scroll's visible bottom is, on screen.
    @State private var marks = RevealMarks()
    let done: (String?) -> Void

    enum Field: Hashable {
        case address, label, username, password, apiKey, home, joinName, joinPassword, joinAgain, gatewayFirst, gatewaySecond
        case headerName(SignInModel.Header.ID), headerValue(SignInModel.Header.ID)
    }

    #if DEBUG
    /// Set by a launch step: the next sign-in screen opens with the family QR scanner.
    static var scanOnOpen = false
    /// Set by a launch step: the next sign-in screen opens with this sample state, in memory only.
    static var sampleOnOpen: ((SignInModel) -> Void)?
    /// Set by a launch step: the next sign-in screen opens scrolled to its end.
    static var endOnOpen = false
    /// Set by a launch step: run on the model once the page has been scrolled to its end.
    static var thenOnOpen: ((SignInModel) -> Void)?
    /// The launch step's scroll to the end is still to come.
    @State private var endPending = false
    #endif

    init(form: SignInModel.Form, session: Session, done: @escaping (String?) -> Void = { _ in }) {
        _model = State(initialValue: SignInModel(form: form, session: session))
        self.done = done
    }

    /// The page in its scroll under its mask and backdrop, with the Back button and the camera and
    /// certificate layers over it.
    private var covered: some View {
        scrolled
            // What scrolls up under the clock and the back button melts away over the gap above
            // the octopus instead of showing through behind them.
            .mask {
                GeometryReader { geo in
                    let top: CGFloat = geo.safeAreaInsets.top
                    VStack(spacing: 0) {
                        LinearGradient(stops: [.init(color: .clear, location: 0),
                                               .init(color: .clear, location: top / (top + 56)),
                                               .init(color: .black, location: 1)],
                                       startPoint: .top, endPoint: .bottom)
                            .frame(height: top + 56)
                        Color.black
                        // Words leaving the bottom melt over its last 24 points instead of being cut
                        // by the screen's edge.
                        LinearGradient(stops: [.init(color: .black, location: 0),
                                               .init(color: .black.opacity(0.35), location: 0.6),
                                               .init(color: .clear, location: 1)],
                                       startPoint: .top, endPoint: .bottom)
                            .frame(height: 24)
                    }
                    .ignoresSafeArea()
                }
            }
            .background { PageBackdrop(page: glowPage, route: nil) }
            .overlay(alignment: .topLeading) {
                Button {
                    // A request that commits finishes first. With a finished sign-up held, Back leaves
                    // the link and keeps its username; the next Back leaves the page.
                    guard !model.committing else { return }
                    if model.holdsSignUp { model.stopJoin() } else { dismiss() }
                } label: {
                    Image(systemName: "chevron.left").font(.system(size: 17, weight: .semibold)).frame(width: 44, height: 44)
                }
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .disabled(model.committing)
                .padding(.leading, 16)
                .accessibilityLabel("Back")
            }
            .animation(.snappy, value: model.advancedOpen)
            // While the camera is open VoiceOver reads only the camera, never the page behind it.
            .accessibilityHidden(model.scanning)
            .overlay {
                if model.scanning {
                    QrScanner { link in
                        model.startJoin(link, session: session)
                    } cancel: {
                        model.scanning = false
                    }
                    .transition(.opacity)
                }
            }
            // A certificate question asked while the camera is open is drawn over the camera.
            .overlay {
                if let question = model.question {
                    TrustSheet(question: question) {
                        Task { await model.trust(session, sync: sync) }
                    } cancel: {
                        model.distrust()
                    }
                    .transition(.opacity)
                }
            }
            .animation(.easeOut(duration: 0.2), value: model.scanning)
            .animation(.easeOut(duration: 0.2), value: model.question)
    }

    /// `covered`, with what the page does as the model, the focus and the keyboard change.
    private var reacting: some View {
        covered
            .onAppear {
                model.serverSwitch = serverSwitch
                model.appeared()
                #if DEBUG
                if Self.scanOnOpen {
                    Self.scanOnOpen = false
                    model.scanFamilyCode()
                }
                if let sample = Self.sampleOnOpen {
                    Self.sampleOnOpen = nil
                    sample(model)
                }
                if Self.endOnOpen {
                    Self.endOnOpen = false
                    endPending = true
                }
                #endif
                if !model.didOpenJoin, case .join(let text, _) = model.form, let link = parseFamilyLink(text) { model.didOpenJoin = true; model.startJoin(link, session: session) }
            }
            .onDisappear {
                router.markSignIn(model, open: false)
                model.leave()
                #if DEBUG
                DriveToast.coverGone = true
                #endif
            }
            // A link from outside never replaces this cover while a sign-in is open in it.
            .onChange(of: model.signInOpen, initial: true) { router.markSignIn(model, open: model.signInOpen) }
            .onChange(of: model.signedIn) { _, signedIn in
                if signedIn {
                    done(model.notice)
                    dismiss()
                }
            }
            // A field's return key follows what the join part shows and never changes under a
            // focused field, so the keyboard closes whenever that changes, the camera opening too.
            .onChange(of: JoinLabels(joining: model.joining, invite: model.invite != nil, handOver: model.handOver != nil, scanning: model.scanning)) { focus = nil }
            .onChange(of: focus) { _, now in
                guard now != nil else { return }
                if model.calloutPending { focusedWhilePending = true }
                if model.keyPending { focusedWhileKeyPending = true }
                wait.send(.focused)
            }
            .onChange(of: model.calloutPending) { _, pending in
                guard pending else { return }
                focusedWhilePending = false
                if model.calloutShowing, let error = model.error {
                    AccessibilityNotification.Announcement(GatewayWords.inGroup(error)).post()
                }
            }
            .onChange(of: model.keyPending) { _, pending in
                if pending { focusedWhileKeyPending = false }
            }
            .onChange(of: model.advancedOpen) { _, open in
                if !open {
                    gatewayHeight = nil
                    gatewayErrorFoot = nil
                    marks.rowBottom = nil
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in keyboardUp = true }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidHideNotification)) { _ in
                keyboardUp = false
                wait.send(.hidden)
            }
    }

    var body: some View {
        reacting
            .fileImporter(isPresented: $choosingCertificate, allowedContentTypes: [.pkcs12]) { certificatePicked($0) }
            // Asked only once the file did not open with an empty password (`certificatePicked`), so it
            // also says what to do with a file saved with none.
            .alert(SignInWords.certPasswordTitle, isPresented: Binding(get: { pendingCertificate != nil }, set: { if !$0 { pendingCertificate = nil } })) {
                SecureField(SignInWords.certPasswordField, text: $certificatePassword)
                // Off until the password is typed in.
                Button(SignInWords.certPasswordUse) { takeCertificate() }.disabled(certificatePassword.isEmpty)
                Button("Cancel", role: .cancel) { pendingCertificate = nil }
            } message: {
                Text(SignInWords.certPasswordHint + " " + SignInWords.certNoPasswordOnPhone)
            }
    }

    /// The page in its scroll, with the callouts that move it. They run here, outside Advanced, so
    /// closing Advanced never ends one: it waits, and runs again once Advanced is open.
    private var scrolled: some View {
        ScrollViewReader { proxy in
            ScrollView {
                page
                    .padding(.horizontal, 24)
                    .padding(.bottom, 60)
                    .frame(maxWidth: 420)
                    .frame(maxWidth: .infinity)
                    .overlay(alignment: .bottom) {
                        Color.clear.frame(height: 1).allowsHitTesting(false).accessibilityHidden(true).id("pageEnd")
                    }
            }
            .scrollDismissesKeyboard(.interactively)
            .background {
                GeometryReader { geo in
                    Color.clear
                        // Where the scroll's visible part ends on screen: over the home indicator,
                        // or the keyboard while it is up.
                        .onChange(of: geo.frame(in: .global).maxY - geo.safeAreaInsets.bottom, initial: true) { _, bottom in
                            marks.visibleBottom = bottom
                        }
                }
            }
            .task(id: CalloutKey(pending: model.calloutPending, open: model.advancedOpen)) { await gatewayCallout(proxy) }
            .task(id: model.keyPending) { await keyCallout(proxy) }
            .task(id: model.advancedOpen) { await revealAdvanced(proxy) }
            #if DEBUG
            .task(id: EndKey(pending: endPending, callout: model.calloutPending)) { await scrollToEnd(proxy) }
            #endif
        }
    }

    /// The sign-in page group's callout: the keyboard closes, then the group comes into view (see
    /// `runCallout`). Only the three log lines are debug builds'.
    private func gatewayCallout(_ proxy: ScrollViewProxy) async {
        guard model.calloutPending, model.advancedOpen else { return }
        let wasUp = keyboardUp
        await runCallout(.gateway, wait: wait, focusedAtStart: { focusedWhilePending }, keyboardWasUp: { wasUp },
                         clearFocus: { closeKeyboard() }, groupHeight: { (gatewayErrorFoot ?? gatewayHeight).map { Double($0) } },
                         visibleHeight: {
                             let height = visibleHeight.map { Double($0) }
                             #if DEBUG
                             if let height { CalloutLog.height(height) }
                             #endif
                             return height
                         },
                         advancedOpen: { model.advancedOpen },
                         scroll: { target in
                             #if DEBUG
                             CalloutLog.scroll(target)
                             #endif
                             scrollCallout(proxy, to: target)
                         },
                         shown: {
                             #if DEBUG
                             CalloutLog.shown(wasUp: wasUp)
                             #endif
                             model.calloutShown()
                         })
    }

    /// The API key row's callout: the keyboard closes, then the row comes into view. The row is in
    /// the main card, so it waits for no measuring and no Advanced.
    private func keyCallout(_ proxy: ScrollViewProxy) async {
        guard model.keyPending else { return }
        let wasUp = keyboardUp
        await runCallout(.key, wait: wait, focusedAtStart: { focusedWhileKeyPending }, keyboardWasUp: { wasUp },
                         clearFocus: { closeKeyboard() }, groupHeight: { nil }, visibleHeight: { nil },
                         advancedOpen: { model.advancedOpen }, scroll: { scrollCallout(proxy, to: $0) },
                         shown: { model.keyShown() })
    }

    /// Brings a callout's target into view; at once under reduced motion. A group's title or the
    /// key row lands `calloutLanding` points under the scroll's top (the 56 of the top fade and 22
    /// more), clear of the fade and of the Back button, whose foot is 59 under the top. A group too
    /// tall for that shows the end of what the callout needs: its error note above the bottom.
    private func scrollCallout(_ proxy: ScrollViewProxy, to target: CalloutTarget) {
        let landing = UnitPoint(x: 0, y: CGFloat(calloutLanding) / max(visibleHeight ?? 730, 1))
        let end = gatewayErrorFoot == nil ? "gatewayEnd" : "gatewayErrorEnd"
        let move = {
            switch target {
            case .marker: proxy.scrollTo("gatewayMarker", anchor: landing)
            case .end: proxy.scrollTo(end, anchor: .bottom)
            case .key: proxy.scrollTo("keyMarker", anchor: landing)
            }
        }
        if still { move() } else { withAnimation(.snappy) { move() } }
    }

    /// Advanced opened from its head: its first group's first row comes into view when it opened
    /// under the scroll's visible bottom (see `advancedRevealScrolls`), the page gliding until the
    /// row's foot sits on that bottom, or moving at once under reduced motion. Whether it runs is
    /// decided as Advanced opens: with a callout or the API key row's scroll on its way it never
    /// does, so a callout that finishes first is never scrolled away. It waits for the row to be
    /// measured; a field taking focus on the way ends it, since the person is typing.
    private func revealAdvanced(_ proxy: ScrollViewProxy) async {
        guard model.advancedOpen, revealsAdvanced else { return }
        revealsAdvanced = false
        guard !model.calloutPending, !model.keyPending else { return }
        let run = wait.begin()
        defer { wait.end(run) }
        while marks.rowBottom == nil || marks.visibleBottom == nil {
            guard await wait.next(run, for: .measured) == .measured else { return }
        }
        await Task.yield()
        guard !Task.isCancelled, let row = marks.rowBottom, let bottom = marks.visibleBottom,
              advancedRevealScrolls(open: model.advancedOpen, calloutPending: model.calloutPending, keyPending: model.keyPending,
                                    rowBottom: Double(row), visibleBottom: Double(bottom)) else { return }
        let move = { proxy.scrollTo("advancedFirstRow", anchor: .bottom) }
        if still { move() } else { withAnimation(.snappy) { move() } }
    }

    /// Advanced's first row was laid out or moved: where its foot is now, and the first time since
    /// Advanced opened, a wake for the reveal waiting on it.
    private func firstRowMoved(_ bottom: CGFloat) {
        let first = marks.rowBottom == nil
        marks.rowBottom = bottom
        if first { wait.send(.measured) }
    }

    /// Closes the on-screen keyboard before a callout: no field keeps focus, and whatever UIKit
    /// still holds lets go too.
    private func closeKeyboard() {
        focus = nil
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }

    #if DEBUG
    /// A launch step's scroll to the page's end, once the page is laid out and any callout has run,
    /// then the step that follows it.
    private func scrollToEnd(_ proxy: ScrollViewProxy) async {
        guard endPending, !model.calloutPending else { return }
        await Task.yield()
        guard !Task.isCancelled else { return }
        endPending = false
        proxy.scrollTo("pageEnd", anchor: .bottom)
        if let then = Self.thenOnOpen {
            Self.thenOnOpen = nil
            then(model)
        }
    }
    #endif

    /// What kind of page the form is, as the glow sees it: each is a Settings page.
    private var glowPage: AmbientPage {
        switch model.form {
        case .signIn: .signIn
        case .editConnection: .editConnection
        case .add, .edit, .again: .serverForm
        case .join: .familyJoin
        }
    }

    private var header: some View {
        VStack(spacing: 6) {
            OctopusMark()
                .frame(width: 132)
                .shadow(color: palette.color(.accent).opacity(0.35), radius: 36)
                .padding(.top, 56)
                .padding(.bottom, 10)
            if let heading = model.heading {
                Text(heading).font(.octoTitle).multilineTextAlignment(.center)
            } else {
                Text("Octo").font(.octoDisplay)
            }
            Text(model.subheading)
                .font(.octoBodySmall)
                .foregroundStyle(OctoColor.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.top, 4)
        }
    }

    /// The page from the octopus down: the main card and its notes, the button and the line under
    /// it, the join buttons or the scan button on the button's trailing edge, then Advanced.
    /// Opened from its head, Advanced brings its first group into view.
    private var page: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                .frame(maxWidth: .infinity)
                .padding(.bottom, 28)
            card
            cardNotes
            if model.joining {
                joinButtons
            } else {
                AccentButton(model.buttonTitle, enabled: model.ready, loading: model.busy, fillWidth: true) {
                    Task { await model.submit(session, sync: sync) }
                }
                .padding(.top, 20)
                failureLine
                if model.offersScan {
                    // Off while a sign-in runs.
                    GlazeButton("Scan a family QR code", icon: .camera, size: .small, enabled: !model.busy) { model.scanFamilyCode() }
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        .padding(.top, 12)
                }
            }
            // Advanced shows while joining too: a family link's server may stand behind a sign-in
            // page, or want headers or a certificate of its own.
            FormSectionHead(title: SignInWords.advanced, line: SignInWords.advancedLine, open: model.advancedOpen) {
                model.advancedOpen.toggle()
                revealsAdvanced = model.advancedOpen
            }
            .padding(.top, 28)
            if model.advancedOpen {
                advanced
                    .padding(.top, 20)
                    .transition(.opacity)
            }
        }
    }

    /// The main card: the address, the Name on the server list's Add and Edit, then the username
    /// with the password or the API key, or while joining the invite's sign-up or the hand-over's
    /// step.
    private var card: some View {
        FormCard {
            FormFieldRow(title: SignInWords.serverAddress, value: model.rest, onChange: { model.typeAddress($0, session: session) },
                         placeholder: "music.example.com", contentType: .URL, keyboardType: .URL,
                         submitLabel: addressNext == nil ? .done : .next, leading: { schemeToggle }, onSubmit: addressNext,
                         focus: $focus, field: .address, readOnly: model.holdsSignUp)
                // A refused link pasted here leaves the field drawn again without it, with focus back in it.
                .id(model.addressResets)
                .task(id: model.addressResets) { if model.addressResets > 0 { focus = .address } }
            if let note = model.linkNote {
                FormNote(.info, note)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 12)
            }
            if model.showsName {
                FormDivider()
                FormFieldRow(title: SignInWords.serverName, optional: true, value: model.label, onChange: { model.label = $0 },
                             placeholder: SignInWords.namePlaceholder, submitLabel: nameNext == nil ? .done : .next,
                             capitalization: .words, autocorrect: true, onSubmit: nameNext, focus: $focus, field: .label)
            }
            if model.joining {
                joinRows
            } else {
                signInRows
            }
        }
    }

    /// The scheme inside the address field: a lock and "https://", or an open lock and "http://".
    private var schemeToggle: some View {
        Button(action: model.toggleScheme) {
            HStack(spacing: 4) {
                Image(systemName: model.scheme == .https ? "lock.fill" : "lock.open.fill")
                    .font(.system(size: lockSize, weight: .semibold))
                    .foregroundStyle(model.scheme == .https ? OctoColor.accent : OctoColor.textMuted)
                Text(model.scheme.prefix).font(.octoFormValue).foregroundStyle(OctoColor.textMuted)
            }
        }
        .buttonStyle(.plain)
        .disabled(model.holdsSignUp)
        .accessibilityLabel(model.scheme == .https ? "Encrypted, https" : "Not encrypted, http")
        .accessibilityHint(model.scheme == .https ? "Use http" : "Use https")
    }

    /// Next on the address: to the Name where it shows, to Your name while an invite shows, else to
    /// Username. While joining with neither (a hand-over, or no link yet) the key is Done.
    private var addressNext: (() -> Void)? {
        if model.joining && model.invite == nil && !model.showsName { return nil }
        return { if model.showsName { focus = .label } else if model.invite != nil { focus = .joinName } else { focus = .username } }
    }

    /// Next on the Name: to Your name while an invite shows, else to Username. While joining with no
    /// invite the key is Done.
    private var nameNext: (() -> Void)? {
        if model.joining && model.invite == nil { return nil }
        return { focus = model.invite != nil ? .joinName : .username }
    }

    /// Next on the home address: to the first header's name. With no header row the key is Done.
    private var homeNext: (() -> Void)? {
        if model.headers.isEmpty { return nil }
        return { if let first = model.headers.first { focus = .headerName(first.id) } }
    }

    /// Go on the sign-in page's second field: signs up for an invite, receives a hand-over, or signs
    /// in. While joining with no link yet there is nothing to send, and the key is Done.
    private var gatewaySubmit: (() -> Void)? {
        if model.invite != nil { return { Task { await model.signUp(session) } } }
        if model.handOver != nil { return { model.receive(session) } }
        if model.joining { return nil }
        return { Task { await model.submit(session, sync: sync) } }
    }

    /// Username, then the password, or the API key in its place when that is the Method.
    @ViewBuilder
    private var signInRows: some View {
        FormDivider()
        FormFieldRow(title: SignInWords.username, optional: model.useApiKey, value: model.username, onChange: { model.username = $0 },
                     placeholder: model.useApiKey ? "" : SignInWords.required, contentType: .username, submitLabel: .next,
                     onSubmit: { focus = model.useApiKey ? .apiKey : .password }, focus: $focus, field: .username)
        FormDivider()
        if model.useApiKey {
            FormFieldRow(title: SignInWords.apiKey, value: model.apiKey, onChange: { model.apiKey = $0 },
                         placeholder: model.keepsKey ? SignInWords.unchanged : SignInWords.required, secret: true, submitLabel: .done,
                         onSubmit: { Task { await model.submit(session, sync: sync) } }, focus: $focus, field: .apiKey)
                .calloutMarker("keyMarker")
        } else {
            FormFieldRow(title: SignInWords.password, value: model.password, onChange: { model.password = $0 },
                         placeholder: model.keepsPassword ? SignInWords.unchanged : SignInWords.required, secret: true,
                         contentType: .password, submitLabel: .done,
                         onSubmit: { Task { await model.submit(session, sync: sync) } }, focus: $focus, field: .password)
        }
    }

    /// While joining: the hand-over's one row, or the invite's sign-up fields.
    @ViewBuilder
    private var joinRows: some View {
        if let handOver = model.handOver {
            FormDivider()
            HStack(spacing: 12) {
                Text(handOverRowLine(step: model.handOverStep, failed: model.error != nil, server: handOver.server))
                    .font(.octoFormValue)
                    .foregroundStyle(OctoColor.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if model.busy { LoadingSpinner(size: 22) }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
        } else if model.invite != nil {
            FormDivider()
            FormFieldRow(title: SignInWords.yourName, value: model.joinName, onChange: { model.joinName = $0 },
                         placeholder: SignInWords.required, contentType: .name, submitLabel: .next, capitalization: .words,
                         autocorrect: true, onSubmit: { focus = .joinPassword }, focus: $focus, field: .joinName)
            FormDivider()
            FormFieldRow(title: SignInWords.password, description: model.strength, value: model.joinPassword,
                         onChange: { model.joinPassword = $0 }, placeholder: SignInWords.required, secret: true,
                         contentType: .newPassword, submitLabel: .next, onSubmit: { focus = .joinAgain }, focus: $focus,
                         field: .joinPassword)
            FormDivider()
            FormFieldRow(title: SignInWords.passwordAgain, value: model.joinAgain, onChange: { model.joinAgain = $0 },
                         placeholder: SignInWords.required, secret: true, contentType: .newPassword, submitLabel: .done,
                         onSubmit: { Task { await model.signUp(session) } }, focus: $focus, field: .joinAgain)
        }
    }

    /// Under the main card: what the hand-over or the invite asks, or how to join with no link yet,
    /// then how the connection travels.
    private var cardNotes: some View {
        VStack(alignment: .leading, spacing: 8) {
            // How the connection travels, first under the card on every state, beside the address
            // it describes.
            if let note = model.note { connectionNote(note) }
            if model.handOver != nil {
                FormNote(.info, "On your other device, tap Allow when it asks about this phone.")
            } else if let invite = model.invite {
                FormNote(.info, "You're invited to \(shownServer(invite.server)). Choose your name and a password. Your login then works in Octo and any other music app.")
            } else if model.joining {
                FormNote(.info, "Your invite shows a QR code. So does Sign in on another device, in Octo on your other phone or computer. Scan it, or open its link on this\u{00A0}phone.")
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    /// How safe the connection is: a note with a lock, or an error where the password or the API
    /// key itself would travel readable.
    private func connectionNote(_ note: SignInModel.Note) -> FormNote {
        switch note {
        case .encrypted: FormNote(.encrypted, "Encrypted connection")
        case .homeNetwork: FormNote(.unencrypted, "Not encrypted. Fine on your home network.")
        case .tokenInTheOpen: FormNote(.unencrypted, "Not encrypted. Your password goes as a one-time token, but the rest can be read on the way.")
        case .legacyInTheOpen: FormNote(.error, "Not encrypted, and a legacy password travels as it is. Anyone on the way can read it.")
        case .apiKeyInTheOpen: FormNote(.error, SignInWords.apiKeyInTheOpen)
        }
    }

    /// The line under the button: while the sign-in page group calls out the failure, a pointer that
    /// brings the group back into view; otherwise the error, if there is one.
    @ViewBuilder
    private var failureLine: some View {
        if model.calloutShowing {
            Button { model.pointTo() } label: { FormNote(.error, GatewayWords.pointer) }
                .buttonStyle(.plain)
                .padding(.horizontal, 16)
                .padding(.top, 12)
        } else if let error = model.error {
            FormNote(.error, error)
                .padding(.horizontal, 16)
                .padding(.top, 12)
        }
    }

    /// Under the card while joining: the step's own button, the line under it, then the scanner and
    /// the way back to the password form on the button's trailing edge.
    @ViewBuilder
    private var joinButtons: some View {
        if model.invite != nil {
            AccentButton("Sign up", enabled: model.joinMissing == nil, loading: model.busy, fillWidth: true) {
                Task { await model.signUp(session) }
            }
            .padding(.top, 20)
        } else if model.handOver != nil, !model.busy {
            AccentButton("Try again", fillWidth: true) { model.receive(session) }
                .padding(.top, 20)
        }
        failureLine
        // One block on the trailing edge, both ways as wide as the wider.
        TrailingColumn(gap: 12) {
            if model.handOver == nil {
                GlazeButton("Scan the QR code", icon: .camera, size: .small, fillWidth: true) { model.scanning = true }
            }
            GlazeButton("Sign in with a password", size: .small, enabled: !model.committing, fillWidth: true) { model.stopJoin() }
        }
        .padding(.top, 12)
    }

    /// Advanced's groups, in the order every app draws them.
    private var advanced: some View {
        let layout = advancedLayout(joining: model.joining, takesApiKeys: model.takesApiKeys, apiKeyOn: model.useApiKey,
                                    kind: model.gatewayKind, certificateRow: true)
        return VStack(alignment: .leading, spacing: 26) {
            ForEach(layout.groups, id: \.group) { group in
                switch group.group {
                case .gettingThere: gettingThere(group.rows)
                case .signingIn: signingIn(layout.methods)
                case .specialSetups: specialSetups
                }
            }
        }
    }

    /// Getting to the server: the sign-in page in front of it (Authentik, Authelia, a proxy's
    /// password or Cloudflare Access), its two fields once a kind is picked, and the home address.
    /// A callout marks the group's title and its end, and measures it from title to last note. Its
    /// first row, the kind, is what opening Advanced from its head brings into view.
    private func gettingThere(_ rows: [AdvancedRow]) -> some View {
        let labels = GatewayWords.fieldLabels(model.gatewayKind, detected: model.gatewayMet)
        // The values saved for this kind stay while its fields are left empty.
        let keptPage = model.gatewayKind == model.gatewaySaved
        let kinds = SettingsQuestion(GatewayWords.sectionLabel, GatewayKind.allCases, current: model.gatewayKind,
                                     answer: { SettingsAnswer(GatewayWords.kindLabel($0), GatewayWords.kindHelper($0)) },
                                     pick: { model.pickGateway($0) })
        return FormGroup(title: AdvancedGroup.gettingThere.title, marker: "gatewayMarker", outlined: model.calloutShowing) {
            ForEach(Array(rows.enumerated()), id: \.element) { index, row in
                if index > 0 { FormDivider() }
                switch row {
                case .pageKind:
                    FormChoiceRow(title: GatewayWords.sectionLabel,
                                  description: GatewayWords.kindHelper(model.gatewayKind) ?? GatewayWords.caption,
                                  value: GatewayWords.kindLabel(model.gatewayKind), question: kinds)
                        .revealMark("advancedFirstRow", bottom: firstRowMoved)
                case .pageFirst:
                    FormFieldRow(title: labels.first, value: model.gatewayFirst, onChange: { model.gatewayFirst = $0 },
                                 placeholder: keptPage ? SignInWords.unchanged : SignInWords.required,
                                 keyboardType: model.gatewayKind == .cloudflareToken ? .asciiCapable : .default, submitLabel: .next,
                                 onSubmit: { focus = .gatewaySecond }, focus: $focus, field: .gatewayFirst)
                case .pageSecond:
                    FormFieldRow(title: labels.second, value: model.gatewaySecond, onChange: { model.gatewaySecond = $0 },
                                 placeholder: keptPage ? SignInWords.unchanged : SignInWords.required, secret: true,
                                 submitLabel: gatewaySubmit == nil ? .done : .go, onSubmit: gatewaySubmit, focus: $focus,
                                 field: .gatewaySecond)
                case .home:
                    FormFieldRow(title: SignInWords.homeAddress, optional: true, value: model.homeAddress,
                                 onChange: { model.homeAddress = $0 }, placeholder: SignInWords.homePlaceholder, keyboardType: .URL,
                                 submitLabel: homeNext == nil ? .done : .next, onSubmit: homeNext, focus: $focus, field: .home)
                case .method, .headers, .certificate:
                    EmptyView()
                }
            }
        } notes: {
            if model.calloutShowing, let error = model.error {
                FormNote(.error, GatewayWords.inGroup(error))
                    .onGeometryChange(for: CGFloat.self) { $0.frame(in: .named("gatewayGroup")).maxY } action: { foot in
                        gatewayErrorFoot = foot
                        wait.send(.measured)
                    }
                    .onDisappear { gatewayErrorFoot = nil }
                    .calloutEnd("gatewayErrorEnd")
            }
            // A sign-in page's password never crosses the internet readable.
            if model.gatewayInsecure { FormNote(.error, GatewayWords.needsHttps) }
            if let met = model.gatewayMet {
                FormNote(.info, GatewayWords.hint(met))
            } else if let tip = GatewayWords.tip(model.gatewayKind) {
                FormNote(.info, tip)
            }
            if model.joining { FormNote(.info, GatewayWords.joinHint) }
            FormNote(.info, SignInWords.homeNote)
        }
        .coordinateSpace(.named("gatewayGroup"))
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height in
            gatewayHeight = height
            wait.send(.measured)
        }
        .calloutEnd("gatewayEnd")
    }

    /// How to sign in: the Method, from the ways the server may take.
    private func signingIn(_ methods: [AuthMode]) -> some View {
        let question = SettingsQuestion(SignInWords.method, methods, current: model.mode,
                                        answer: { SettingsAnswer(SignInWords.methodLabel($0), SignInWords.methodHelper($0, takesApiKeys: model.takesApiKeys)) },
                                        pick: { model.pickMethod($0) })
        return FormGroup(title: AdvancedGroup.signingIn.title) {
            FormChoiceRow(title: SignInWords.method, description: SignInWords.methodHelper(model.mode, takesApiKeys: model.takesApiKeys),
                          value: SignInWords.methodLabel(model.mode), question: question)
        }
    }

    /// Custom headers, each row keyed by its own id so focus and Show stay with their row, then
    /// the client certificate. A picked file its password did not open says so in the group's
    /// error note, since the password's question closes with either answer.
    private var specialSetups: some View {
        FormGroup(title: AdvancedGroup.specialSetups.title) {
            FormTrailingRow(title: SignInWords.customHeaders, description: SignInWords.headersDescription) { EmptyView() }
            ForEach(Array(model.headers.enumerated()), id: \.element.id) { i, header in
                FormDivider()
                HeaderRow(header: header, focus: $focus, id: header.id,
                          valueNext: i + 1 < model.headers.count ? .headerName(model.headers[i + 1].id) : nil,
                          focusNewHeader: $focusNewHeader,
                          rename: { model.renameHeader(header.id, to: $0) }, setValue: { model.setHeaderValue(header.id, to: $0) },
                          remove: { model.removeHeader(header.id); focus = nil })
            }
            GlazeButton(SignInWords.addHeader, size: .small) { focusNewHeader = model.addHeader() }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            FormDivider()
            certificateRow
        } notes: {
            if let certificateError { FormNote(.error, certificateError) }
        }
    }

    /// The client certificate: its name with its last valid day once known, or None chosen; Choose or
    /// Change, and Remove.
    private var certificateRow: some View {
        FormTrailingRow(title: SignInWords.certTitle,
                        description: certificateRowLine(name: model.certificateShown, expires: model.certificateExpiry,
                                                        format: { $0.formatted(date: .abbreviated, time: .omitted) })) {
            GlazeButton(model.certificateShown == nil ? SignInWords.certChoose : SignInWords.certChange, size: .small) {
                certificateError = nil
                choosingCertificate = true
            }
            if model.certificateShown != nil {
                RemoveButton(name: SignInWords.certRemove) {
                    model.certificate = nil
                    model.removesCertificate = model.hadCertificate
                }
            }
        }
    }

    /// A file picked for the certificate: one saved with no password is taken at once, and any other
    /// asks for its password. Only checked here: the server in use keeps its certificate until this
    /// one is saved.
    private func certificatePicked(_ result: Result<URL, any Error>) {
        guard case .success(let url) = result else { return }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { return }
        let name = url.lastPathComponent
        do {
            let expires = try CertificateCheck.validate(data, password: "")
            model.certificate = (name, data, "", expires)
            model.removesCertificate = false
        } catch {
            pendingCertificate = (name, data)
            certificatePassword = ""
        }
    }

    private func takeCertificate() {
        guard let pending = pendingCertificate else { return }
        do {
            // Only checked here: the server in use keeps its certificate until this one is saved.
            let expires = try CertificateCheck.validate(pending.data, password: certificatePassword)
            model.certificate = (pending.name, pending.data, certificatePassword, expires)
            model.removesCertificate = false
            pendingCertificate = nil
        } catch {
            certificateError = error.localizedDescription
        }
    }
}

/// What the address, Name and second sign-in page field return keys follow while joining, the camera
/// included: one change closes the keyboard, so no return key changes under a focused field.
struct JoinLabels: Hashable {
    let joining: Bool
    let invite: Bool
    let handOver: Bool
    let scanning: Bool
}

/// What starts the sign-in page group's callout: one waiting, with Advanced open.
struct CalloutKey: Hashable {
    let pending: Bool
    let open: Bool
}

/// Where Advanced's first row ends and where the scroll's visible bottom is, on screen. Kept out of
/// the page's observed state: both move with every scroll and keyboard, and redraw nothing.
@MainActor
final class RevealMarks {
    var rowBottom: CGFloat?
    var visibleBottom: CGFloat?
}

#if DEBUG
/// What starts a launch step's scroll to the page's end: one asked for, with no callout to come.
struct EndKey: Hashable {
    let pending: Bool
    let callout: Bool
}
#endif

/// Asks before trusting a certificate the phone does not. `changed` asks about a host the person
/// trusted another certificate for, met while in use.
struct TrustSheet: View {
    let question: ServerTrust.Untrusted
    var changed = false
    let trust: () -> Void
    let cancel: () -> Void

    private var title: String { changed ? SignInWords.trustChangedTitle : SignInWords.trustTitle }

    /// Sized to its content when it fits, and scrolling when large text or a small screen would
    /// push the buttons off.
    var body: some View {
        ViewThatFits(in: .vertical) {
            content
            ScrollView { content }
                .scrollBounceBehavior(.basedOnSize)
        }
        .frame(maxWidth: 380)
        .octoGlass(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Rectangle().fill(OctoShade(0.45)).ignoresSafeArea().onTapGesture(perform: cancel))
        .accessibilityAddTraits(.isModal)
        .accessibilityLabel(title)
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title).font(.octoHeadline)
            Text(changed ? SignInWords.trustChangedDetail : SignInWords.trustDetail("phone"))
                .font(.octoBodySmall)
                .foregroundStyle(OctoColor.textSecondary)
            VStack(alignment: .leading, spacing: 4) {
                Text(SignInWords.trustHost).font(.octoCaption).foregroundStyle(OctoColor.textMuted)
                Text(question.host).font(.octoBodySmall)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(SignInWords.trustFingerprint).font(.octoCaption).foregroundStyle(OctoColor.textMuted)
                Text(question.fingerprint).font(.system(size: 13, design: .monospaced)).textSelection(.enabled)
            }
            AnswerRow(gap: 10) {
                AccentButton(SignInWords.trustConfirm, size: .medium, fillWidth: true, action: trust)
                GlazeButton("Cancel", size: .medium, fillWidth: true, action: cancel)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

extension ServerTrust.Untrusted: Identifiable {
    var id: String { host + fingerprint }
}
