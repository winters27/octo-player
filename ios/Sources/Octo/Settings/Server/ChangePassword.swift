import OctoKit
import SwiftUI

/// The current password, then the new one twice. Its button is off until all three are typed in,
/// and nothing is sent until the form is right; what the server says comes back in plain words.
/// Made fresh each time its sheet opens. While a change is on its way, from here or Family's
/// sheet, Done sends nothing and the sheet stays. The change runs on its own (`PasswordChanges`):
/// once changed this form closes and the app's notice says so; a refusal shows here, or in the
/// notice once the form is gone.
struct ChangePasswordForm: View {
    let close: () -> Void
    @Environment(Session.self) private var session
    @Environment(Accounts.self) private var accounts
    @Environment(Router.self) private var router
    @State private var draft = PasswordDraft()
    @State private var problem: String?
    @State private var onScreen = OnScreen()
    @State private var onCurrent = false
    @State private var onNew = false
    @State private var onAgain = false

    /// A change of password is on its way, from this form or another.
    private var busy: Bool { session.passwordChanges.running }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // The sheet's heading, as the sign-in page's own sheets set theirs, and its line close
            // under it.
            VStack(alignment: .leading, spacing: 4) {
                Text("Change password")
                    .font(.octoHeadline)
                    .foregroundStyle(OctoColor.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                Text("Your other apps will ask for the new password the next time they sign in.")
                    .font(.octoBodySmall)
                    .foregroundStyle(OctoColor.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.bottom, 4)
            field("Current password", text: $draft.current, at: 0)
            field("New password", text: $draft.new, at: 1)
            field("New password again", text: $draft.confirm, at: 2)
            if let problem {
                Text(problem).font(.octoCaption).foregroundStyle(OctoColor.error).fixedSize(horizontal: false, vertical: true)
            }
            AnswerRow(gap: 12) {
                AccentButton("Change password", size: .medium, enabled: draft.filled, loading: busy, fillWidth: true, action: change)
                GlazeButton("Cancel", size: .medium, enabled: !busy, fillWidth: true, action: leave)
            }
        }
        .padding(.horizontal, 20)
        // Room under the sheet's grabber, as Family's sheets keep over their titles.
        .padding(.top, 30)
        .padding(.bottom, 20)
        // A swipe down does nothing while a change is on its way.
        .interactiveDismissDisabled(busy)
        .onAppear { onScreen.shown = true }
        .onDisappear { onScreen.shown = false }
    }

    /// A field with its name over it, so a typed field keeps its name. Next moves on to the
    /// following field; Done on the last one changes the password.
    private func field(_ name: String, text: Binding<String>, at index: Int) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(name).font(.octoFormName).foregroundStyle(OctoColor.textPrimary).accessibilityHidden(true)
            GlassInput(SignInWords.required, text: Binding(get: { text.wrappedValue }, set: { text.wrappedValue = $0; problem = nil }),
                       secure: true, submitLabel: index == 2 ? .done : .next, focus: focus(at: index)) {
                switch index {
                case 0: onNew = true
                case 1: onAgain = true
                default: change()
                }
            }
            .textContentType(index == 0 ? .password : .newPassword)
            .accessibilityLabel(name)
        }
    }

    private func focus(at index: Int) -> Binding<Bool> {
        switch index {
        case 0: $onCurrent
        case 1: $onNew
        default: $onAgain
        }
    }

    /// Closes the sheet, unless a change is on its way: it stays until that answers.
    private func leave() {
        guard !busy else { return }
        close()
    }

    /// Sends the change once: while one is on its way, from here or another form, Done sends
    /// nothing more.
    private func change() {
        guard !busy else { return }
        if let problem = passwordDraftProblem(draft) {
            self.problem = problem
            return
        }
        problem = nil
        let draft = draft
        let session = session
        let router = router
        let onScreen = onScreen
        let family = accounts.family.model.me?.managed == true
        session.passwordChanges.change {
            await session.changePassword(current: draft.current, new: draft.new, family: family)
        } words: { result in
            passwordChangeWords(result)
        } answered: { result, words in
            guard onScreen.shown else { return false }
            if result == .changed { close() } else { problem = words }
            return true
        } notice: { words, done in
            router.show(words, done: done)
        }
    }
}

/// Whether a view is on screen: true from its appearance until it goes. Work that answers after its
/// view may have gone (a change of password whose sheet was closed under it) asks before it writes
/// there.
@MainActor
final class OnScreen {
    var shown = true
}
