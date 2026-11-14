import Foundation

/// One custom header row on a sign-in form, with an id of its own that never changes, so the rows
/// draw keyed. A saved row keeps its value in the keychain; its value may be left empty.
public struct HeaderDraftRow: Identifiable, Equatable, Sendable {
    public let id: UUID
    public var name: String
    public var value: String
    public var saved: Bool

    public init(name: String, value: String, saved: Bool) {
        id = UUID()
        self.name = name
        self.value = value
        self.saved = saved
    }
}

/// The custom header rows a sign-in form holds. Every write goes through one of these ops, which
/// only set fields, so a row's id never changes and an edit always lands on its own row.
public struct HeaderList: Equatable, Sendable {
    public private(set) var rows: [HeaderDraftRow]

    public static let empty = HeaderList(rows: [])

    private init(rows: [HeaderDraftRow]) {
        self.rows = rows
    }

    /// New rows, each with a fresh id: a form filled from a saved server, or emptied.
    public mutating func replaced(_ rows: [HeaderRules.Row]) {
        self.rows = rows.map { HeaderDraftRow(name: $0.name, value: $0.value, saved: $0.saved) }
    }

    /// A blank row at the end, and its id.
    @discardableResult
    public mutating func added() -> UUID {
        let row = HeaderDraftRow(name: "", value: "", saved: false)
        rows.append(row)
        return row.id
    }

    /// A new name. A saved row stays saved only under its own name, compared trimmed and ignoring
    /// case; under another its saved value no longer applies.
    public mutating func renamed(_ id: UUID, _ name: String) {
        guard let at = rows.firstIndex(where: { $0.id == id }) else { return }
        let old = rows[at].name.trimmingCharacters(in: .whitespacesAndNewlines)
        let same = name.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(old) == .orderedSame
        rows[at].saved = rows[at].saved && same
        rows[at].name = name
    }

    /// A new value; a saved row stays saved.
    public mutating func valued(_ id: UUID, _ value: String) {
        guard let at = rows.firstIndex(where: { $0.id == id }) else { return }
        rows[at].value = value
    }

    public mutating func remove(_ id: UUID) {
        rows.removeAll { $0.id == id }
    }
}
