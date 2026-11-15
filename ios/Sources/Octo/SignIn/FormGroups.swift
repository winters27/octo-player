import OctoKit
import SwiftUI
import UIKit

// The sign-in form's parts, laid out the way every Octo app's sign-in page is: groups under their
// titles on raised cards, titled field rows with the entry in an inset well, notes under each card,
// and the quiet Optional tag.

/// A group of the form: its title over a raised card of rows, and its notes under the card. A
/// `marker` puts a callout's scroll mark on the title; `outlined` rings the card in the error red
/// while the group calls out a failure.
struct FormGroup<Rows: View, Notes: View>: View {
    let title: String
    var marker: String?
    var outlined: Bool
    let rows: Rows
    let notes: Notes

    init(title: String, marker: String? = nil, outlined: Bool = false, @ViewBuilder rows: () -> Rows,
         @ViewBuilder notes: () -> Notes) {
        self.title = title
        self.marker = marker
        self.outlined = outlined
        self.rows = rows()
        self.notes = notes()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            heading
                .padding(.horizontal, 16)
                .padding(.bottom, 9)
            FormCard(outlined: outlined) { rows }
            NotesStack { notes }
        }
    }

    @ViewBuilder
    private var heading: some View {
        let words = Text(title)
            .font(.octoFormGroup)
            .foregroundStyle(OctoColor.textPrimary)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityAddTraits(.isHeader)
        if let marker { words.calloutMarker(marker) } else { words }
    }
}

extension FormGroup where Notes == EmptyView {
    init(title: String, marker: String? = nil, outlined: Bool = false, @ViewBuilder rows: () -> Rows) {
        self.init(title: title, marker: marker, outlined: outlined, rows: rows, notes: { EmptyView() })
    }
}

/// The raised card a group's rows sit on, a surface a step above the page, as Settings' groups are.
/// `outlined` rings it in the error red, the error note's own red.
struct FormCard<Content: View>: View {
    var outlined = false
    let content: Content

    init(outlined: Bool = false, @ViewBuilder content: () -> Content) {
        self.outlined = outlined
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .raisedPanel()
            .overlay {
                if outlined {
                    RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(OctoColor.errorWords, lineWidth: 2)
                }
            }
    }
}

/// The notes under a card, 8 under it on the rows' content edge; nothing at all when there is none.
private struct NotesStack<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        Group(subviews: content) { notes in
            if !notes.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(notes) { note in note }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
        }
    }
}

/// The hairline between two rows of a card, starting under the rows' words.
struct FormDivider: View {
    @Environment(\.displayScale) private var scale

    init() {}

    var body: some View {
        Rectangle()
            .fill(OctoInkColor.quaternary)
            .frame(height: 1 / scale)
            .padding(.leading, 16)
    }
}

/// What a note under a card is: an aside, an error, or how the connection travels.
enum FormNoteKind {
    case info, error, encrypted, unencrypted
}

/// A note under a card: its glyph and its words. An error's glyph and words are both in the error
/// words color; an aside's glyph is the accent, the connection's lock the secondary words.
struct FormNote: View {
    let kind: FormNoteKind
    let text: String
    @ScaledMetric(relativeTo: .subheadline) private var glyph: CGFloat = 16

    init(_ kind: FormNoteKind, _ text: String) {
        self.kind = kind
        self.text = text
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            // Every glyph in one slot, so every note's words start on one column whichever mark
            // leads, and switching http and https moves only the lock.
            Image(systemName: symbol)
                .font(.system(size: glyph, weight: .regular))
                .foregroundStyle(glyphStyle)
                .frame(width: glyph)
                .accessibilityHidden(true)
            Text(text)
                .font(.octoFormNote)
                .foregroundStyle(kind == .error ? AnyShapeStyle(OctoColor.errorWords) : AnyShapeStyle(OctoColor.textSecondary))
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private var symbol: String {
        switch kind {
        case .info: "info.circle"
        case .error: "exclamationmark.circle.fill"
        case .encrypted: "lock.fill"
        case .unencrypted: "lock.open.fill"
        }
    }

    private var glyphStyle: AnyShapeStyle {
        switch kind {
        case .info: AnyShapeStyle(OctoColor.accent)
        case .error: AnyShapeStyle(OctoColor.errorWords)
        case .encrypted, .unencrypted: AnyShapeStyle(OctoColor.textSecondary)
        }
    }
}

/// The quiet tag after the name of a field that may stay empty. VoiceOver hears it in the field's
/// own name, so the tag itself is hidden from it.
struct OptionalTag: View {
    var body: some View {
        Text(SignInWords.optionalTag)
            .font(.octoFormTag)
            .foregroundStyle(OctoColor.tagWords)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(OctoColor.tagFill, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
            .accessibilityHidden(true)
    }
}

/// A setting's name, with the Optional tag after it when the field may stay empty.
private struct RowTitle: View {
    let title: String
    let optional: Bool

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(title)
                .font(.octoFormName)
                .foregroundStyle(OctoColor.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            if optional { OptionalTag() }
        }
    }
}

/// The line under a setting's name: at most two lines up to the large text size, all of it above.
private struct RowDescription: View {
    let text: String
    @Environment(\.dynamicTypeSize) private var size

    var body: some View {
        Text(text)
            .font(.octoFormDescription)
            .foregroundStyle(OctoColor.textSecondary)
            .lineLimit(size <= .large ? 2 : nil)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// The inset well a field's words are typed in: the page's color, edged with the theme's edge hairline.
private struct EntryWell<Content: View>: View {
    let content: Content
    @Environment(\.displayScale) private var scale

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 12, style: .continuous)
        HStack(spacing: 8) { content }
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .background(OctoColor.background, in: shape)
            .overlay(shape.strokeBorder(OctoColor.edge, lineWidth: 1 / scale))
    }
}

/// How every entry types: the field's words, its keyboard, its return key and what that key does.
/// No `onSubmit` keeps the system's own return key. A read-only entry shows its words and takes no
/// typing.
private struct EntryLook: ViewModifier {
    let contentType: UITextContentType?
    let keyboardType: UIKeyboardType
    let submitLabel: SubmitLabel
    let capitalization: TextInputAutocapitalization
    let autocorrect: Bool
    let onSubmit: (() -> Void)?
    let readOnly: Bool

    func body(content: Content) -> some View {
        let shaped = content
            .font(.octoFormValue)
            .foregroundStyle(OctoColor.textPrimary)
            .textContentType(contentType)
            .keyboardType(keyboardType)
            .textInputAutocapitalization(capitalization)
            .autocorrectionDisabled(!autocorrect)
            .submitLabel(submitLabel)
            .disabled(readOnly)
        if let onSubmit {
            shaped.onSubmit { onSubmit() }
        } else {
            shaped
        }
    }
}

/// A plain entry. `label` is the name VoiceOver reads; `placeholder` shows in the muted words.
struct PlainEntry: View {
    let label: String
    let value: String
    let onChange: (String) -> Void
    let placeholder: String
    var contentType: UITextContentType?
    var keyboardType: UIKeyboardType = .default
    var submitLabel: SubmitLabel = .done
    var capitalization: TextInputAutocapitalization = .never
    var autocorrect = false
    var onSubmit: (() -> Void)?
    let focus: FocusState<SignInView.Field?>.Binding
    let field: SignInView.Field
    var readOnly = false

    var body: some View {
        TextField(text: Binding(get: { value }, set: { onChange($0) }), prompt: Text(placeholder).foregroundStyle(OctoColor.textMuted)) {
            Text(label)
        }
        .focused(focus, equals: field)
        .modifier(EntryLook(contentType: contentType, keyboardType: keyboardType, submitLabel: submitLabel,
                            capitalization: capitalization, autocorrect: autocorrect, onSubmit: onSubmit, readOnly: readOnly))
    }
}

/// A secret entry with Show and Hide. Its two forms share one focus binding, on the group round them.
struct SecretEntry: View {
    let label: String
    let value: String
    let onChange: (String) -> Void
    let placeholder: String
    var contentType: UITextContentType?
    var submitLabel: SubmitLabel = .done
    var onSubmit: (() -> Void)?
    let focus: FocusState<SignInView.Field?>.Binding
    let field: SignInView.Field
    @State private var revealed = false

    init(label: String, value: String, onChange: @escaping (String) -> Void, placeholder: String,
         contentType: UITextContentType? = nil, submitLabel: SubmitLabel = .done, onSubmit: (() -> Void)? = nil,
         focus: FocusState<SignInView.Field?>.Binding, field: SignInView.Field) {
        self.label = label
        self.value = value
        self.onChange = onChange
        self.placeholder = placeholder
        self.contentType = contentType
        self.submitLabel = submitLabel
        self.onSubmit = onSubmit
        self.focus = focus
        self.field = field
    }

    var body: some View {
        Group {
            if revealed {
                TextField(text: text, prompt: prompt) { Text(label) }
            } else {
                SecureField(text: text, prompt: prompt) { Text(label) }
            }
        }
        .focused(focus, equals: field)
        .modifier(EntryLook(contentType: contentType, keyboardType: .default, submitLabel: submitLabel, capitalization: .never,
                            autocorrect: false, onSubmit: onSubmit, readOnly: false))
        Button(revealed ? "Hide" : "Show") { revealed.toggle() }
            .font(.octoLabel)
            .foregroundStyle(OctoColor.accent)
            .buttonStyle(.plain)
    }

    private var text: Binding<String> { Binding(get: { value }, set: { onChange($0) }) }

    private var prompt: Text { Text(placeholder).foregroundStyle(OctoColor.textMuted) }
}

/// A titled field row: the setting's name (with the Optional tag), the line under it, and the
/// entry in its well. `leading` sits inside the well before the words (the address's scheme).
/// VoiceOver reads the field by its name, the tag merged in.
struct FormFieldRow<Leading: View>: View {
    let title: String
    var optional: Bool
    var description: String?
    let value: String
    let onChange: (String) -> Void
    var placeholder: String
    var secret: Bool
    var contentType: UITextContentType?
    var keyboardType: UIKeyboardType
    var submitLabel: SubmitLabel
    var capitalization: TextInputAutocapitalization
    var autocorrect: Bool
    let leading: () -> Leading
    var onSubmit: (() -> Void)?
    let focus: FocusState<SignInView.Field?>.Binding
    let field: SignInView.Field
    var readOnly: Bool

    init(title: String, optional: Bool = false, description: String? = nil, value: String, onChange: @escaping (String) -> Void,
         placeholder: String = "", secret: Bool = false, contentType: UITextContentType? = nil,
         keyboardType: UIKeyboardType = .default, submitLabel: SubmitLabel = .done,
         capitalization: TextInputAutocapitalization = .never, autocorrect: Bool = false,
         @ViewBuilder leading: @escaping () -> Leading, onSubmit: (() -> Void)? = nil,
         focus: FocusState<SignInView.Field?>.Binding, field: SignInView.Field, readOnly: Bool = false) {
        self.title = title
        self.optional = optional
        self.description = description
        self.value = value
        self.onChange = onChange
        self.placeholder = placeholder
        self.secret = secret
        self.contentType = contentType
        self.keyboardType = keyboardType
        self.submitLabel = submitLabel
        self.capitalization = capitalization
        self.autocorrect = autocorrect
        self.leading = leading
        self.onSubmit = onSubmit
        self.focus = focus
        self.field = field
        self.readOnly = readOnly
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            RowTitle(title: title, optional: optional)
                .accessibilityHidden(true)
            if let description { RowDescription(text: description) }
            EntryWell {
                leading()
                if secret {
                    SecretEntry(label: name, value: value, onChange: onChange, placeholder: placeholder, contentType: contentType,
                                submitLabel: submitLabel, onSubmit: onSubmit, focus: focus, field: field)
                } else {
                    PlainEntry(label: name, value: value, onChange: onChange, placeholder: placeholder, contentType: contentType,
                               keyboardType: keyboardType, submitLabel: submitLabel, capitalization: capitalization,
                               autocorrect: autocorrect, onSubmit: onSubmit, focus: focus, field: field, readOnly: readOnly)
                }
            }
            .padding(.top, 2)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The field's name as VoiceOver reads it, the tag merged in.
    private var name: String { optional ? SignInWords.optionalName(title) : title }
}

extension FormFieldRow where Leading == EmptyView {
    init(title: String, optional: Bool = false, description: String? = nil, value: String, onChange: @escaping (String) -> Void,
         placeholder: String = "", secret: Bool = false, contentType: UITextContentType? = nil,
         keyboardType: UIKeyboardType = .default, submitLabel: SubmitLabel = .done,
         capitalization: TextInputAutocapitalization = .never, autocorrect: Bool = false, onSubmit: (() -> Void)? = nil,
         focus: FocusState<SignInView.Field?>.Binding, field: SignInView.Field, readOnly: Bool = false) {
        self.init(title: title, optional: optional, description: description, value: value, onChange: onChange,
                  placeholder: placeholder, secret: secret, contentType: contentType, keyboardType: keyboardType,
                  submitLabel: submitLabel, capitalization: capitalization, autocorrect: autocorrect, leading: { EmptyView() },
                  onSubmit: onSubmit, focus: focus, field: field, readOnly: readOnly)
    }
}

/// One custom header: its name over its value, and its Remove at the right edge. Next on the name
/// goes to the value; the value's key goes to `valueNext`, the next row's name, or is Done on the
/// last row. A row just added takes focus once it is drawn, never in the action that adds it.
struct HeaderRow: View {
    let header: SignInModel.Header
    let focus: FocusState<SignInView.Field?>.Binding
    let id: SignInModel.Header.ID
    let valueNext: SignInView.Field?
    let focusNewHeader: Binding<SignInModel.Header.ID?>
    let rename: (String) -> Void
    let setValue: (String) -> Void
    let remove: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            VStack(spacing: 8) {
                EntryWell {
                    PlainEntry(label: SignInWords.headerName, value: header.name, onChange: rename, placeholder: SignInWords.headerName,
                               keyboardType: .asciiCapable, submitLabel: .next,
                               onSubmit: { focus.wrappedValue = .headerValue(id) }, focus: focus, field: .headerName(id))
                }
                .task(id: focusNewHeader.wrappedValue) {
                    if focusNewHeader.wrappedValue == id {
                        focusNewHeader.wrappedValue = nil
                        focus.wrappedValue = .headerName(id)
                    }
                }
                EntryWell {
                    SecretEntry(label: SignInWords.headerValue, value: header.value, onChange: setValue,
                                placeholder: header.saved ? SignInWords.unchanged : SignInWords.headerValue,
                                submitLabel: valueNext == nil ? .done : .next,
                                onSubmit: { if let next = valueNext { focus.wrappedValue = next } }, focus: focus, field: .headerValue(id))
                }
            }
            RemoveButton(name: SignInWords.removeHeader(header.name), action: remove)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

/// A row's Remove: a minus in the destructive color, named for what it removes.
struct RemoveButton: View {
    let name: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "minus.circle.fill")
                .font(.system(size: 22, weight: .regular))
                .foregroundStyle(OctoColor.destructive)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(name)
    }
}

/// A row with a name, the line under it and controls at its right edge; at the accessibility sizes
/// the controls go under the words.
struct FormTrailingRow<Trailing: View>: View {
    let title: String
    var description: String?
    let trailing: Trailing
    @Environment(\.dynamicTypeSize) private var size

    init(title: String, description: String? = nil, @ViewBuilder trailing: () -> Trailing) {
        self.title = title
        self.description = description
        self.trailing = trailing()
    }

    var body: some View {
        Group {
            if size.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 8) {
                    words
                    HStack(spacing: 8) { trailing }
                }
            } else {
                HStack(alignment: .center, spacing: 12) {
                    words.frame(maxWidth: .infinity, alignment: .leading)
                    trailing
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var words: some View {
        VStack(alignment: .leading, spacing: 2) {
            RowTitle(title: title, optional: false)
            if let description { RowDescription(text: description) }
        }
        .accessibilityElement(children: .combine)
    }
}

/// A choice on the form (the sign-in page's kind, the Method): its name and the line under it, the
/// current answer at the right edge, and a chevron, the answer and the chevron on the name's first
/// line however many lines follow it. An answer that does not fit beside the name goes under it,
/// as it always does at the accessibility sizes. Tapping it opens the answers as the Settings
/// questions do, each with its own line. The chevron grows with the text size, as the name does.
struct FormChoiceRow: View {
    let title: String
    var description: String?
    let value: String
    let question: SettingsQuestion
    @Environment(\.dynamicTypeSize) private var size
    @ScaledMetric(relativeTo: .subheadline) private var chevronSize: CGFloat = 13

    init(title: String, description: String? = nil, value: String, question: SettingsQuestion) {
        self.title = title
        self.description = description
        self.value = value
        self.question = question
    }

    var body: some View {
        Menu {
            QuestionMenuItems(question: question)
        } label: {
            Group {
                if size.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 4) {
                        RowTitle(title: title, optional: false)
                        if let description { RowDescription(text: description) }
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            answer(.leading)
                            chevron
                        }
                    }
                } else {
                    VStack(alignment: .leading, spacing: 2) {
                        // The answer and the chevron on the name's line while they fit on one; else
                        // the answer under the name with the chevron after it, as at the
                        // accessibility sizes, so it reads as the row's value, never its line.
                        ViewThatFits(in: .horizontal) {
                            HStack(alignment: .firstTextBaseline, spacing: 0) {
                                RowTitle(title: title, optional: false)
                                Spacer(minLength: 8)
                                answer(.trailing)
                                chevron.padding(.leading, 12)
                            }
                            VStack(alignment: .leading, spacing: 2) {
                                RowTitle(title: title, optional: false)
                                HStack(alignment: .firstTextBaseline, spacing: 6) {
                                    answer(.leading)
                                    chevron
                                }
                            }
                        }
                        // The line wraps where it did beside the chevron's column.
                        if let description { RowDescription(text: description).padding(.trailing, chevronSize + 12) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .menuStyle(.button)
        .buttonStyle(SettingsRowPress())
        .menuOrder(.fixed)
        .accessibilityLabel(title)
        .accessibilityValue(value)
        .accessibilityHint(description ?? "")
    }

    private func answer(_ alignment: TextAlignment) -> some View {
        Text(value)
            .font(.octoFormValue)
            .foregroundStyle(OctoColor.textSecondary)
            .multilineTextAlignment(alignment)
            .lineLimit(size <= .large ? 2 : nil)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var chevron: some View {
        Image(systemName: "chevron.up.chevron.down")
            .font(.system(size: chevronSize, weight: .semibold))
            .foregroundStyle(OctoColor.textMuted)
            .accessibilityHidden(true)
    }
}

/// A section's head (Advanced): its title, set as a group's title and one size open or shut, the
/// line naming what is inside, and a chevron on the title's first line that turns as it opens and
/// grows with the text size, as the title does. VoiceOver reads it as a heading that opens and
/// closes.
struct FormSectionHead: View {
    let title: String
    let line: String
    let open: Bool
    let toggle: () -> Void
    @ScaledMetric(relativeTo: .headline) private var chevronSize: CGFloat = 15

    init(title: String, line: String, open: Bool, toggle: @escaping () -> Void) {
        self.title = title
        self.line = line
        self.open = open
        self.toggle = toggle
    }

    var body: some View {
        Button(action: toggle) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.octoFormGroup)
                        .foregroundStyle(OctoColor.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    RowDescription(text: line)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right")
                    .font(.system(size: chevronSize, weight: .semibold))
                    .foregroundStyle(OctoColor.textSecondary)
                    .rotationEffect(.degrees(open ? 90 : 0))
                    // Turned, the glyph's ink sits lower on the line: lifted back onto the title's
                    // cap middle.
                    .offset(y: open ? -chevronSize * 0.1 : 0)
            }
            .padding(.horizontal, 16)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityHint(line)
        .accessibilityValue(open ? "Expanded" : "Collapsed")
        .accessibilityAddTraits([.isHeader, .isButton])
    }
}

extension View {
    /// The mark a callout scrolls to: a zero-height view on this view's top edge, in the layout
    /// itself, so the scroll reads its real place. `scrollCallout` lands it under the top fade.
    func calloutMarker(_ id: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Color.clear.frame(height: 0).allowsHitTesting(false).accessibilityHidden(true).id(id)
            self
        }
    }

    /// The mark a callout scrolls to the view's bottom: 8 points tall under this view, so its last
    /// line sits 8 above the view's bottom.
    func calloutEnd(_ id: String) -> some View {
        overlay(alignment: .bottom) {
            Color.clear.frame(height: 8).alignmentGuide(.bottom) { $0[.top] }
                .allowsHitTesting(false).accessibilityHidden(true).id(id)
        }
    }

    /// The mark a reveal scrolls to the view's bottom: this view's own frame, so all of it comes
    /// into view. `bottom` hears where the view's foot is on screen each time it moves.
    func revealMark(_ id: String, bottom: @escaping @MainActor (CGFloat) -> Void) -> some View {
        overlay {
            Color.clear
                .allowsHitTesting(false).accessibilityHidden(true).id(id)
                .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).maxY } action: { bottom($0) }
        }
    }
}
