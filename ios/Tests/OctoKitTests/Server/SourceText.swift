import Foundation

/// The app's and OctoKit's own sources, for the site checks: the app target has no test target,
/// so what its sign-in page and model do is pinned by their text. A file under `ios/Sources` is
/// found from this file upward, with CRLF read as LF; every piece a check looks for throws when it
/// is missing, so no check passes on text that is not there.
enum SourceText {
    struct Missing: Error, CustomStringConvertible {
        let what: String
        var description: String { "not found: \(what)" }
    }

    static func read(_ path: String) throws -> String {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while dir.path != "/" && !dir.path.isEmpty {
            let file = dir.appendingPathComponent("Sources").appendingPathComponent(path)
            if FileManager.default.fileExists(atPath: file.path) {
                return try String(contentsOf: file, encoding: .utf8).replacingOccurrences(of: "\r\n", with: "\n")
            }
            dir = dir.deletingLastPathComponent()
        }
        throw Missing(what: path)
    }

    /// The text from `start` to the first `end` after it, both included.
    static func slice(_ text: String, from start: String, to end: String) throws -> String {
        guard let head = text.range(of: start) else { throw Missing(what: start) }
        guard let tail = text.range(of: end, range: head.upperBound..<text.endIndex) else { throw Missing(what: end) }
        return String(text[head.lowerBound..<tail.upperBound])
    }

    /// Where `part` first starts in `text`.
    static func at(_ part: String, in text: String) throws -> String.Index {
        guard let found = text.range(of: part) else { throw Missing(what: part) }
        return found.lowerBound
    }

    /// Where `part` last starts in `text`.
    static func lastAt(_ part: String, in text: String) throws -> String.Index {
        guard let found = text.range(of: part, options: .backwards) else { throw Missing(what: part) }
        return found.lowerBound
    }

    static func count(_ part: String, in text: String) -> Int {
        text.components(separatedBy: part).count - 1
    }

    /// The text with every run of spaces, tabs and line breaks made one space, so a check can name
    /// several lines of code in one string whatever their indents.
    static func squashed(_ text: String) -> String {
        var out = ""
        var spaced = false
        for character in text {
            if character.isWhitespace {
                if !spaced { out.append(" ") }
                spaced = true
            } else {
                out.append(character)
                spaced = false
            }
        }
        return out
    }

    /// The string literals in `text`, as written between their quotes (one line each).
    static func literals(_ text: String) -> [String] {
        var found: [String] = []
        var open: String?
        var escaped = false
        for character in text {
            guard var current = open else {
                if character == "\"" { open = "" }
                continue
            }
            if escaped {
                current.append(character)
                escaped = false
            } else if character == "\\" {
                current.append(character)
                escaped = true
            } else if character == "\"" {
                found.append(current)
                open = nil
                continue
            } else if character == "\n" {
                open = nil
                continue
            } else {
                current.append(character)
            }
            open = current
        }
        return found
    }
}
