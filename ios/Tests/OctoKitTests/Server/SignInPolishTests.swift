import Foundation
import OctoKit
import Testing

/// The sign-in looks' rules that live in OctoKit: where a question's two answers go, when opening
/// Advanced scrolls, what the hand-over's row says, and when Change password is ready.
@Suite struct SignInPolishRulesTests {
    /// Red when answers that fit are stacked, a short main answer is left narrower than Cancel where
    /// the row has room for it, or answers too wide for the row are squeezed side by side.
    @Test func answersSitSideBySideWhileTheyFitAndStackOtherwise() {
        // A short main answer beside Cancel, with room: widened to Cancel's width.
        #expect(answerPlacement(main: 60, other: 84, gap: 10, room: 300) == AnswerPlacement(stacked: false, main: 84, other: 84))
        // A long main answer keeps its own width.
        #expect(answerPlacement(main: 170, other: 84, gap: 10, room: 300) == AnswerPlacement(stacked: false, main: 170, other: 84))
        // Room for both at their own widths but not for the widening: widened as far as the row allows.
        #expect(answerPlacement(main: 60, other: 84, gap: 10, room: 160) == AnswerPlacement(stacked: false, main: 66, other: 84))
        // Exactly the row's width still sits side by side.
        #expect(answerPlacement(main: 206, other: 84, gap: 10, room: 300) == AnswerPlacement(stacked: false, main: 206, other: 84))
        // A point wider: stacked, each the row's full width, so neither is cut.
        #expect(answerPlacement(main: 207, other: 84, gap: 10, room: 300) == AnswerPlacement(stacked: true, main: 300, other: 300))
        // Large text in the trust question on a 375 point phone (its words 295 wide).
        #expect(answerPlacement(main: 230, other: 110, gap: 10, room: 295) == AnswerPlacement(stacked: true, main: 295, other: 295))
        // No width limit: side by side, the main one at least Cancel's width.
        #expect(answerPlacement(main: 60, other: 84, gap: 10, room: nil) == AnswerPlacement(stacked: false, main: 84, other: 84))
    }

    /// Red when opening Advanced from its head leaves a first row that opened under the view's bottom
    /// out of sight, scrolls a page that already shows it (which would scroll it back), or scrolls
    /// while a callout or the API key row's scroll is on its way, or once Advanced closed again.
    @Test func openingAdvancedScrollsOnlyToShowAFirstRowOutOfSight() {
        // An iPhone 16: the visible bottom 34 over the screen's 852.
        #expect(advancedRevealScrolls(open: true, calloutPending: false, keyPending: false, rowBottom: 900, visibleBottom: 818))
        #expect(!advancedRevealScrolls(open: true, calloutPending: false, keyPending: false, rowBottom: 700, visibleBottom: 818))
        #expect(!advancedRevealScrolls(open: true, calloutPending: false, keyPending: false, rowBottom: 818, visibleBottom: 818))
        #expect(!advancedRevealScrolls(open: true, calloutPending: true, keyPending: false, rowBottom: 900, visibleBottom: 818))
        #expect(!advancedRevealScrolls(open: true, calloutPending: false, keyPending: true, rowBottom: 900, visibleBottom: 818))
        #expect(!advancedRevealScrolls(open: false, calloutPending: false, keyPending: false, rowBottom: 900, visibleBottom: 818))
    }

    /// A guard: red when some form's Advanced stops starting with Getting there and its kind row,
    /// the row the page's reveal marks.
    @Test func theRevealedRowIsAlwaysTheFirstGroupsFirstRow() {
        for joining in [false, true] {
            for takesApiKeys in [nil, true, false] as [Bool?] {
                for apiKeyOn in [false, true] {
                    for kind in GatewayKind.allCases {
                        for certificateRow in [false, true] {
                            let layout = advancedLayout(joining: joining, takesApiKeys: takesApiKeys, apiKeyOn: apiKeyOn, kind: kind,
                                                        certificateRow: certificateRow)
                            #expect(layout.groups.first?.group == .gettingThere)
                            #expect(layout.groups.first?.rows.first == .pageKind)
                        }
                    }
                }
            }
        }
    }

    /// Red when a refused hand-over still says it is ready to sign in, a step under way is hidden by
    /// the failure's words, or the server is named with its scheme or a slash at its end.
    @Test func aHandOversRowSaysWhereItStands() {
        #expect(handOverRowLine(step: nil, failed: false, server: "https://music.example.com/") == "Ready to sign in to music.example.com")
        #expect(handOverRowLine(step: nil, failed: true, server: "https://music.example.com/") == "Not signed in to music.example.com yet")
        #expect(handOverRowLine(step: handOverWaiting, failed: true, server: "https://music.example.com/") == handOverWaiting)
        #expect(handOverRowLine(step: handOverAllowed, failed: false, server: "http://192.168.1.20:4533") == handOverAllowed)
    }

    /// Red when Change password's button is ready with a field still empty, or stays off once all
    /// three hold words.
    @Test func aPasswordChangeIsReadyOnlyWithEveryFieldTypedIn() {
        #expect(!PasswordDraft().filled)
        #expect(!PasswordDraft(current: "old", new: "new", confirm: "").filled)
        #expect(!PasswordDraft(current: "old", new: "", confirm: "new").filled)
        #expect(!PasswordDraft(current: "", new: "new", confirm: "new").filled)
        #expect(PasswordDraft(current: "old", new: "new", confirm: "new").filled)
        // New passwords that do not match are typed in all the same: the button is ready, and
        // pressing it says why nothing was sent.
        #expect(PasswordDraft(current: "old", new: "new", confirm: "nwe").filled)
    }
}

/// The iPhone sign-in page's looks and its password questions, checked in their own sources: the
/// app target has no test target, so what its form parts, page and password sheets draw is pinned
/// here by their text.
@Suite struct SignInPolishSitesTests {
    private static let view = "Octo/SignIn/SignInView.swift"
    private static let groups = "Octo/SignIn/FormGroups.swift"
    private static let style = "Octo/Design/OctoStyle.swift"
    private static let buttons = "Octo/Design/Buttons.swift"
    private static let changePassword = "Octo/Settings/Server/ChangePassword.swift"
    private static let familySheets = "Octo/Family/FamilySheets.swift"
    private static let familyParts = "Octo/Family/FamilySheetParts.swift"
    private static let words = "OctoKit/Server/SignInWords.swift"

    /// Red when the ring round a called-out group is the accent again instead of the error red.
    @Test func theCalloutRingIsTheErrorRed() throws {
        let groups = try SourceText.read(Self.groups)
        let card = try SourceText.slice(groups, from: "struct FormCard<Content: View>: View {", to: "\n}\n")
        #expect(card.contains("RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(OctoColor.errorWords, lineWidth: 2)"))
        #expect(!card.contains("OctoColor.accent"))
    }

    /// Red when Advanced's head is set larger than a group's title again, its chevron sags to the
    /// middle of the title and its line, or the chevron keeps one size while the title grows.
    @Test func advancedsHeadIsAGroupTitleWithItsChevronOnTheTitle() throws {
        let groups = try SourceText.read(Self.groups)
        let head = try SourceText.slice(groups, from: "struct FormSectionHead: View {", to: "\n}\n")
        for anchor in [
            "@ScaledMetric(relativeTo: .headline) private var chevronSize: CGFloat = 15",
            "HStack(alignment: .firstTextBaseline, spacing: 12) {",
            ".font(.octoFormGroup)",
            ".font(.system(size: chevronSize, weight: .semibold))",
        ] {
            #expect(head.contains(anchor), "\(anchor)")
        }
        for old in [".octoFormSection", "alignment: .center", "size: 15,"] {
            #expect(!head.contains(old), "\(old)")
        }
        // The chevron grows with the title's own text style.
        let style = try SourceText.read(Self.style)
        #expect(style.contains("static var octoFormGroup: Font { octo(17, .semibold, .headline) }"))
        #expect(!style.contains("octoFormSection"))
    }

    /// Red when a choice's answer and chevron sag to the middle of its name and the lines under it,
    /// or its chevron keeps one size while the name grows.
    @Test func aChoicesAnswerAndChevronSitOnItsName() throws {
        let groups = try SourceText.read(Self.groups)
        let choice = try SourceText.slice(groups, from: "struct FormChoiceRow: View {", to: "\n}\n")
        #expect(choice.contains("@ScaledMetric(relativeTo: .subheadline) private var chevronSize: CGFloat = 13"))
        #expect(choice.contains(".font(.system(size: chevronSize, weight: .semibold))"))
        // Beside the name on its first baseline, and on the answer's own line (where it does not fit
        // beside the name, and at the accessibility sizes) with the chevron after it.
        #expect(SourceText.count("HStack(alignment: .firstTextBaseline, spacing: 0) {", in: choice) == 1)
        #expect(SourceText.count("HStack(alignment: .firstTextBaseline, spacing: 6) {", in: choice) == 2)
        #expect(!choice.contains("alignment: .center"))
        #expect(!choice.contains("size: 13,"))
        let style = try SourceText.read(Self.style)
        #expect(style.contains("static var octoFormName: Font { octo(15, .medium, .subheadline) }"))
    }

    /// Red when the scanner or the way back to a password sign-in is centered under the full-width
    /// button again instead of ending on its trailing edge.
    @Test func theLinkWaysEndOnTheButtonsTrailingEdge() throws {
        let view = SourceText.squashed(try SourceText.read(Self.view))
        for anchor in [
            "AccentButton(model.buttonTitle, enabled: model.ready, loading: model.busy, fillWidth: true)",
            "GlazeButton(\"Scan a family QR code\", icon: .camera, size: .small, enabled: !model.busy) { model.scanFamilyCode() } .frame(maxWidth: .infinity, alignment: .trailing) .padding(.top, 12)",
            "TrailingColumn(gap: 12) { if model.handOver == nil { GlazeButton(\"Scan the QR code\", icon: .camera, size: .small, fillWidth: true) { model.scanning = true } } GlazeButton(\"Sign in with a password\", size: .small, enabled: !model.committing, fillWidth: true) { model.stopJoin() } } .padding(.top, 12)",
        ] {
            #expect(view.contains(anchor), "\(anchor)")
        }
    }

    /// Red when the hand-over's row says it is ready to sign in over a failure, or types its words
    /// in the view again instead of reading the shared rule.
    @Test func aFailedHandOverSaysItIsNotSignedInYet() throws {
        let view = try SourceText.read(Self.view)
        #expect(view.contains("Text(handOverRowLine(step: model.handOverStep, failed: model.error != nil, server: handOver.server))"))
        #expect(!view.contains("\"Ready to sign in to"))
    }

    /// Red when opening Advanced from its head no longer brings its first group into view, a reveal
    /// is asked for by anything but the head, the reveal skips its rule or its wait for the row, or
    /// the row's mark moves off the first group's first row.
    @Test func openingAdvancedFromItsHeadBringsItsFirstGroupIntoView() throws {
        let raw = try SourceText.read(Self.view)
        let view = SourceText.squashed(raw)
        #expect(view.contains(
            "FormSectionHead(title: SignInWords.advanced, line: SignInWords.advancedLine, open: model.advancedOpen) { model.advancedOpen.toggle() revealsAdvanced = model.advancedOpen }"))
        // The declaration, the head, and the reveal's own clear: nothing else asks for one.
        #expect(SourceText.count("revealsAdvanced = ", in: raw) == 3)
        let host = try SourceText.slice(raw, from: "    private var scrolled: some View {", to: "\n    }\n")
        #expect(host.contains(".task(id: model.advancedOpen) { await revealAdvanced(proxy) }"))
        #expect(SourceText.squashed(host).contains(
            ".onChange(of: geo.frame(in: .global).maxY - geo.safeAreaInsets.bottom, initial: true) { _, bottom in marks.visibleBottom = bottom }"))
        let reveal = SourceText.squashed(try SourceText.slice(raw, from: "    private func revealAdvanced(", to: "\n    }\n"))
        for anchor in [
            "guard model.advancedOpen, revealsAdvanced else { return } revealsAdvanced = false",
            "while marks.rowBottom == nil || marks.visibleBottom == nil { guard await wait.next(run, for: .measured) == .measured else { return } }",
            "advancedRevealScrolls(open: model.advancedOpen, calloutPending: model.calloutPending, keyPending: model.keyPending, rowBottom: Double(row), visibleBottom: Double(bottom))",
            "let move = { proxy.scrollTo(\"advancedFirstRow\", anchor: .bottom) } if still { move() } else { withAnimation(.snappy) { move() } }",
        ] {
            #expect(reveal.contains(anchor), "\(anchor)")
        }
        #expect(SourceText.count(".revealMark(", in: raw) == 1)
        #expect(view.contains(
            "value: GatewayWords.kindLabel(model.gatewayKind), question: kinds) .revealMark(\"advancedFirstRow\", bottom: firstRowMoved)"))
        #expect(view.contains("if !open { gatewayHeight = nil gatewayErrorFoot = nil marks.rowBottom = nil }"))
        let groups = SourceText.squashed(try SourceText.read(Self.groups))
        #expect(groups.contains(
            "Color.clear .allowsHitTesting(false).accessibilityHidden(true).id(id) .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).maxY } action: { bottom($0) }"))
    }

    /// Red when whether the reveal runs is decided only after its wait for the row, so a callout or the
    /// API key row's scroll that finishes first is scrolled away by it: with either on its way as
    /// Advanced opens, the reveal never runs.
    @Test func aRevealIsDecidedAsAdvancedOpens() throws {
        let raw = try SourceText.read(Self.view)
        let reveal = SourceText.squashed(try SourceText.slice(raw, from: "    private func revealAdvanced(", to: "\n    }\n"))
        #expect(reveal.contains(
            "async { guard model.advancedOpen, revealsAdvanced else { return } revealsAdvanced = false guard !model.calloutPending, !model.keyPending else { return } let run = wait.begin()"))
    }

    /// Red when Done on Change password's last field can send a second change while one runs, from
    /// this form or another: the flag it checks is the app's one (`PasswordChangeSitesTests`).
    @Test func changePasswordSendsOneChangeAtATime() throws {
        let change = try SourceText.read(Self.changePassword)
        let body = try SourceText.slice(change, from: "    private func change() {", to: "\n    }\n")
        #expect(body.hasPrefix("    private func change() {\n        guard !busy else { return }\n"))
        #expect(change.contains("    private var busy: Bool { session.passwordChanges.running }\n"))
        #expect(SourceText.count("passwordChanges.change {", in: body) == 1)
        #expect(change.contains("default: change()"))
    }

    /// Red when a certificate file saved with no password has to go through the password question,
    /// whose Use it stays off on an empty field so the file could never be chosen, or when the first
    /// try reads "opened, with no last day" as a failure (`try?` makes both nil).
    @Test func aCertificateSavedWithNoPasswordIsTakenWithoutAsking() throws {
        let raw = try SourceText.read(Self.view)
        #expect(raw.contains(".fileImporter(isPresented: $choosingCertificate, allowedContentTypes: [.pkcs12]) { certificatePicked($0) }"))
        let picked = try SourceText.slice(raw, from: "    private func certificatePicked(", to: "\n    }\n")
        let empty = try SourceText.at("let expires = try CertificateCheck.validate(data, password: \"\")", in: picked)
        let ask = try SourceText.at("pendingCertificate = ", in: picked)
        #expect(empty < ask)
        #expect(SourceText.squashed(picked).contains(
            "do { let expires = try CertificateCheck.validate(data, password: \"\") model.certificate = (name, data, \"\", expires) model.removesCertificate = false } catch { pendingCertificate = (name, data) certificatePassword = \"\" }"))
        #expect(!raw.contains("try? CertificateCheck.validate("))
        // The password question is asked from here alone.
        #expect(SourceText.count("pendingCertificate = (", in: raw) == 1)
    }

    /// Red when the password question, which opens only once the file did not open with an empty
    /// password, leaves a file saved with none at a Use it that stays off: its message must say to
    /// export the file again with a password, since iPhone's own import may refuse an empty one.
    @Test func thePasswordQuestionSaysWhatToDoWithAFileSavedWithNone() throws {
        let raw = try SourceText.read(Self.view)
        let view = SourceText.squashed(raw)
        #expect(view.contains(
            "} message: { Text(SignInWords.certPasswordHint + \" \" + SignInWords.certNoPasswordOnPhone) }"))
        let words = SourceText.squashed(try SourceText.read(Self.words))
        #expect(words.contains(
            "public static let certNoPasswordOnPhone = \"If this file has no password, export it again with one: iPhone can't open certificates without a password.\""))
        // Its words follow the alert's hint, whose own words stay the shared ones.
        #expect(words.contains("public static let certPasswordHint = \"The password the certificate file was saved with.\""))
        #expect(SourceText.count("certNoPasswordOnPhone", in: raw) == 1)
    }

    /// Red when a sign-in question's answers sit in a plain row again, where a short main answer can
    /// be narrower than Cancel and large text squeezes both, or the row stops following the shared
    /// placement.
    @Test func everySignInQuestionsAnswersShareOneRow() throws {
        let raw = try SourceText.read(Self.view)
        #expect(SourceText.squashed(raw).contains(
            "AnswerRow(gap: 10) { AccentButton(SignInWords.trustConfirm, size: .medium, fillWidth: true, action: trust) GlazeButton(\"Cancel\", size: .medium, fillWidth: true, action: cancel) }"))
        let trust = try SourceText.slice(raw, from: "struct TrustSheet: View {", to: "\n}\n")
        #expect(!trust.contains("HStack("))
        let change = try SourceText.read(Self.changePassword)
        #expect(SourceText.squashed(change).contains(
            "AnswerRow(gap: 12) { AccentButton(\"Change password\", size: .medium, enabled: draft.filled, loading: busy, fillWidth: true, action: change) GlazeButton(\"Cancel\", size: .medium, enabled: !busy, fillWidth: true, action: leave) }"))
        #expect(!change.contains("HStack("))
        let buttons = SourceText.squashed(try SourceText.read(Self.buttons))
        for anchor in [
            "struct AnswerRow: Layout {",
            "let placed = answerPlacement(main: Double(subviews[0].sizeThatFits(.unspecified).width), other: Double(subviews[1].sizeThatFits(.unspecified).width), gap: Double(gap), room: room)",
            "if placed.stacked { subviews[0].place(at: CGPoint(x: bounds.minX, y: bounds.minY), proposal: mainOffer) subviews[1].place(at: CGPoint(x: bounds.minX, y: bounds.minY + main.height + gap), proposal: otherOffer) }",
        ] {
            #expect(buttons.contains(anchor), "\(anchor)")
        }
    }

    /// Red when Change password's button or the certificate password's Use it is lit before its
    /// fields are typed in. The page's own main buttons already wait for their forms.
    @Test func noMainButtonIsLitBeforeItsFormCanBeUsed() throws {
        let view = SourceText.squashed(try SourceText.read(Self.view))
        #expect(view.contains("AccentButton(model.buttonTitle, enabled: model.ready, loading: model.busy, fillWidth: true)"))
        #expect(view.contains("AccentButton(\"Sign up\", enabled: model.joinMissing == nil, loading: model.busy, fillWidth: true)"))
        #expect(view.contains("Button(SignInWords.certPasswordUse) { takeCertificate() }.disabled(certificatePassword.isEmpty)"))
        let change = SourceText.squashed(try SourceText.read(Self.changePassword))
        #expect(change.contains("AccentButton(\"Change password\", size: .medium, enabled: draft.filled, loading: busy,"))
    }

    /// Red when a certificate file its password did not open says why only inside its question,
    /// which has already closed by then, instead of in the certificate group's error note.
    @Test func aCertificateItsPasswordDidNotOpenSaysSoUnderItsGroup() throws {
        let raw = try SourceText.read(Self.view)
        let setups = SourceText.squashed(try SourceText.slice(raw, from: "    private var specialSetups: some View {", to: "\n    }\n"))
        #expect(setups.contains("certificateRow } notes: { if let certificateError { FormNote(.error, certificateError) } }"))
        #expect(SourceText.squashed(raw).contains(
            "} message: { Text(SignInWords.certPasswordHint + \" \" + SignInWords.certNoPasswordOnPhone) }"))
        #expect(!raw.contains("certificateError ?? SignInWords.certPasswordHint"))
        // A new pick starts with no note; a failed one sets it and keeps the file's question closed.
        #expect(SourceText.squashed(raw).contains("certificateError = nil choosingCertificate = true"))
        #expect(SourceText.squashed(raw).contains("} catch { certificateError = error.localizedDescription }"))
    }

    /// Red when a choice row's words column takes the whole row again, so the answer gets no width
    /// (no "Normal" beside Method, and above Large an empty row one letter wide per line), when an
    /// answer that fits is pushed under the name by spacing on both sides of the spacer, or when an
    /// answer under the name has no chevron after it and reads as the row's line.
    @Test func aChoiceRowShowsItsAnswerBesideOrUnderItsName() throws {
        let groups = SourceText.squashed(try SourceText.read(Self.groups))
        #expect(groups.contains(
            "ViewThatFits(in: .horizontal) { HStack(alignment: .firstTextBaseline, spacing: 0) { RowTitle(title: title, optional: false) Spacer(minLength: 8) answer(.trailing) chevron.padding(.leading, 12) } VStack(alignment: .leading, spacing: 2) { RowTitle(title: title, optional: false) HStack(alignment: .firstTextBaseline, spacing: 6) { answer(.leading) chevron } } }"))
        #expect(groups.contains("if let description { RowDescription(text: description).padding(.trailing, chevronSize + 12) }"))
        #expect(!groups.contains(".layoutPriority(1) answer(.trailing)"))
        #expect(!groups.contains("Spacer(minLength: 0) answer(.trailing)"))
    }

    /// Red when Change password's title is body text again, or a field's name lives only in its
    /// placeholder (three typed fields would be three rows of dots), in Settings or Family.
    @Test func changePasswordLeadsWithAHeadingAndNamesItsFields() throws {
        let change = SourceText.squashed(try SourceText.read(Self.changePassword))
        #expect(change.contains(
            "Text(\"Change password\") .font(.octoHeadline) .foregroundStyle(OctoColor.textPrimary) .accessibilityAddTraits(.isHeader)"))
        #expect(change.contains(".font(.octoBodySmall) .foregroundStyle(OctoColor.textSecondary)"))
        #expect(change.contains(".padding(.horizontal, 20) // Room under the sheet's grabber, as Family's sheets keep over their titles. .padding(.top, 30)"))
        #expect(!change.contains("Text(\"Change password\").font(.octoBody)"))
        let family = SourceText.squashed(try SourceText.read(Self.familySheets))
        for code in [change, family] {
            #expect(code.contains("VStack(alignment: .leading, spacing: 6) { Text(name).font(.octoFormName).foregroundStyle(OctoColor.textPrimary).accessibilityHidden(true)"))
            #expect(code.contains(".accessibilityLabel(name)"))
        }
        #expect(change.contains("GlassInput(SignInWords.required,"))
        #expect(family.contains("GlassInput(index == 1 ? \"At least \\(minPassword) characters\" : SignInWords.required,"))
        // Family's footer puts the main answer first, as every question does.
        #expect(family.contains(
            "HStack(spacing: 10) { AccentButton(FamilyText.changePassword, size: .medium, bright: true, loading: busy, fillWidth: true, action: go) GlazeButton(\"Cancel\","))
    }

    /// Red when the page's words are cut by the screen's bottom edge again instead of melting.
    @Test func wordsMeltAtTheBottomEdge() throws {
        let view = SourceText.squashed(try SourceText.read(Self.view))
        #expect(view.contains(
            "LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black.opacity(0.35), location: 0.6), .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom) .frame(height: 24) } .ignoresSafeArea()"))
    }

    /// Red when a family sheet's avatar or Close centers on the title and its line together
    /// instead of on the title's first line.
    @Test func aFamilySheetsHeaderCentersOnItsTitlesFirstLine() throws {
        let parts = SourceText.squashed(try SourceText.read(Self.familyParts))
        #expect(parts.contains("private let capMiddle: CGFloat = 7.75"))
        #expect(parts.contains("HStack(alignment: .firstTextBaseline, spacing: 12) { if let name {"))
        #expect(SourceText.count(".alignmentGuide(.firstTextBaseline) { $0[VerticalAlignment.center] + capMiddle }", in: parts) == 2)
    }

    /// Red when a note's words start where its glyph happens to end, so notes do not share a column
    /// and switching http and https moves the words.
    @Test func everyNotesWordsStartOnOneColumn() throws {
        let groups = SourceText.squashed(try SourceText.read(Self.groups))
        #expect(groups.contains(".font(.system(size: glyph, weight: .regular)) .foregroundStyle(glyphStyle) .frame(width: glyph) .accessibilityHidden(true)"))
    }

    /// Red when the join page's connection line drifts under the join note again, or its two ways
    /// stack with ragged left edges.
    @Test func theJoinPagesLinesAndWaysHangTogether() throws {
        let raw = try SourceText.read(Self.view)
        let notes = try SourceText.slice(raw, from: "    private var cardNotes: some View {", to: "\n    }\n")
        let connection = try SourceText.at("if let note = model.note { connectionNote(note) }", in: notes)
        let first = try SourceText.at("if model.handOver != nil {", in: notes)
        #expect(connection < first)
        #expect(SourceText.count("connectionNote(note)", in: notes) == 1)
        #expect(notes.contains(
            "\"Your invite shows a QR code. So does Sign in on another device, in Octo on your other phone or computer. Scan it, or open its link on this\\u{00A0}phone.\""))
        let buttons = SourceText.squashed(try SourceText.read(Self.buttons))
        #expect(buttons.contains("struct TrailingColumn: Layout {"))
        #expect(buttons.contains(
            "let widest = subviews.map { $0.sizeThatFits(.unspecified).width }.max() ?? 0 guard let room = proposal.width else { return widest } return min(widest, room)"))
        #expect(buttons.contains("subview.place(at: CGPoint(x: bounds.maxX - width, y: y), proposal: offer)"))
    }

    /// Red when the address field's lock or a button's icon stays one size while the words beside
    /// it grow or shrink with the text size.
    @Test func iconsGrowWithTheWordsBesideThem() throws {
        let view = SourceText.squashed(try SourceText.read(Self.view))
        #expect(view.contains("@ScaledMetric(relativeTo: .subheadline) private var lockSize: CGFloat = 13"))
        #expect(view.contains(".font(.system(size: lockSize, weight: .semibold))"))
        let buttons = SourceText.squashed(try SourceText.read(Self.buttons))
        #expect(buttons.contains("@ScaledMetric(relativeTo: .footnote) private var iconScale: CGFloat = 1"))
        #expect(buttons.contains("IconView(icon, size: CGFloat(size.iconSize) * iconScale, color: ink)"))
    }

    /// Red when Advanced's turned chevron is left low on its title's line.
    @Test func advancedsOpenChevronSitsOnItsTitle() throws {
        let head = try SourceText.slice(SourceText.read(Self.groups), from: "struct FormSectionHead: View {", to: "\n}\n")
        let turn = try SourceText.at(".rotationEffect(.degrees(open ? 90 : 0))", in: head)
        let lift = try SourceText.at(".offset(y: open ? -chevronSize * 0.1 : 0)", in: head)
        #expect(turn < lift)
    }
}
