import Foundation

// What the router keeps about the sign-in covers: which forms have a sign-in open, the cover one of
// Octo's own sign-in asks opened, and which kept server an ask is about.

/// The sign-in forms the covers show, each marked while a sign-in is open in it, held without keeping
/// them alive.
public struct OpenSignIns<Form: AnyObject> {
    private final class Mark {
        weak var form: Form?

        init(_ form: Form) {
            self.form = form
        }
    }

    private var marks: [ObjectIdentifier: Mark] = [:]

    public init() {}

    /// Marks `form` open or not; one form's mark never touches another's.
    public mutating func mark(_ form: Form, open: Bool) {
        marks[ObjectIdentifier(form)] = open ? Mark(form) : nil
    }

    /// Whether a sign-in is open in a form still alive. A form gone without its last mark is dropped
    /// here, so it never keeps links out.
    public mutating func anyOpen() -> Bool {
        marks = marks.filter { $0.value.form != nil }
        return !marks.isEmpty
    }

    /// The forms still alive with a sign-in open.
    public var forms: [Form] { marks.values.compactMap(\.form) }
}

/// The cover an ask opened, once `showing` is the cover on screen: kept while it is still that cover,
/// ended once anything else shows, another cover or none.
public func askedCoverKept(_ asked: String?, showing: String?) -> String? {
    showing == asked ? asked : nil
}

/// The phone's servers as one of Octo's own asks reads them: the kept ones, the one in use, and
/// whether the phone is signed in to it.
public struct AskServers: Sendable {
    public var kept: [StoredServer]
    public var activeId: String?
    public var signedIn: Bool

    public init(kept: [StoredServer], activeId: String?, signedIn: Bool) {
        self.kept = kept
        self.activeId = activeId
        self.signedIn = signedIn
    }

    /// The kept server an ask for `id` is about, looked up anew each time the ask runs, or nil when
    /// the ask is dropped: the server is no longer kept, or it is the one in use and signed in.
    public func target(_ id: String) -> StoredServer? {
        if signedIn && activeId == id { return nil }
        return kept.first { $0.id == id }
    }
}

/// The kept server whose ask's message a cover leaves when a link's join replaces it, or nil. Only a
/// cover an ask opened (`asked`) that is still the one showing leaves one, for its `server` while
/// the ask would not be dropped; a cover the person opened leaves nothing.
public func askLeftOnReplace(asked: String?, showing: String?, server: String, servers: AskServers) -> StoredServer? {
    guard let asked, showing == asked else { return nil }
    return servers.target(server)
}
