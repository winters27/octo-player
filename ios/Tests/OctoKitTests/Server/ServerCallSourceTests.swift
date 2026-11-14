import Foundation
import Testing

/// Every server call goes through ServerExchange, the one place that adds the server's headers
/// and its sign-in page's cookies, follows redirects by the server's rules and reads the answer.
/// These read the app's and OctoKit's sources (ios/Sources/Octo and ios/Sources/OctoKit).
@Suite struct ServerCallSourceTests {
    struct Source {
        let path: String
        let text: String
        let lines: [String]
    }

    struct SourcesMissing: Error {}

    /// The funnel itself.
    static let funnel = "OctoKit/Server/ServerExchange.swift"

    /// Files that talk only to outside services (radio stations, their logos, app updates,
    /// ListenBrainz, the radio catalog, the lyrics library), never to the server.
    static let outsideOnly: Set<String> = [
        "Octo/Playback/HlsStream.swift", "Octo/Playback/LiveStream.swift", "Octo/Discovery/StationLogo.swift",
        "Octo/Settings/AppUpdates.swift", "OctoKit/Settings/ListenBrainz.swift", "OctoKit/Radio/LiveRadio.swift",
        "OctoKit/LyricsEngine/OnlineLyrics.swift",
    ]

    /// Server files that keep a session of their own, and the calls they may make on it: the
    /// server's sessions and the byte delegates, whose requests come from `client.prepared` and
    /// whose redirects go through `hop`.
    static let ownSessions: [String: Set<String>] = [
        "Octo/App/ServerTrust.swift": ["URLSession(configuration:"],
        "Octo/Playback/ByteSource.swift": ["URLSession(configuration:", "dataTask(with:"],
        "Octo/Data/CachingStream.swift": ["URLSession(configuration:", "dataTask(with:"],
        "Octo/Player/TrustedStream.swift": ["URLSession(configuration:", "dataTask(with:"],
        "Octo/Data/OfflineDownloads.swift": ["URLSession(configuration:", "downloadTask(with:"],
    ]

    /// The byte delegates: each keeps the sign-in page's cookies on every hop and final answer.
    static let byteDelegates = ["Octo/Playback/ByteSource.swift", "Octo/Data/CachingStream.swift", "Octo/Player/TrustedStream.swift"]

    /// Files with an exact number of plain calls, each named: LoopFetcher's one is for Apple's
    /// own loop addresses, a server's going through ServerExchange; the admin screen's picture is
    /// for outside covers, the server's own going through ServerExchange.media.
    static let counted: [String: [String: Int]] = [
        "OctoKit/Animated/LoopFiles.swift": ["data(for:": 1],
        "Octo/Admin/OctoAdminScreen.swift": ["AsyncImage(": 1],
    ]

    static let network: [(token: String, pattern: String)] = [
        ("URLSession(configuration:", #"URLSession\(configuration:"#),
        ("dataTask(with:", #"\.dataTask\(with:"#),
        ("downloadTask(with:", #"\.downloadTask\(with:"#),
        ("uploadTask(with:", #"\.uploadTask\(with:"#),
        ("data(for:", #"(?<!ServerExchange)\.data\((for|from):"#),
        ("bytes(for:", #"(?<!ServerExchange)\.bytes\((for|from):"#),
        ("download(for:", #"(?<!ServerExchange)\.download\((for|from):"#),
        ("upload(for:", #"\.upload\((for|from):"#),
        ("AsyncImage(", #"\bAsyncImage\("#),
    ]

    static func sources() throws -> [Source] {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        var root: URL?
        while dir.path != "/" && !dir.path.isEmpty {
            if FileManager.default.fileExists(atPath: dir.appendingPathComponent("Sources/OctoKit").path) {
                root = dir.appendingPathComponent("Sources")
                break
            }
            dir = dir.deletingLastPathComponent()
        }
        guard let root else { throw SourcesMissing() }
        var found: [Source] = []
        for folder in ["Octo", "OctoKit"] {
            let base = root.appendingPathComponent(folder).standardizedFileURL
            guard let walk = FileManager.default.enumerator(at: base, includingPropertiesForKeys: nil) else { continue }
            for case let file as URL in walk where file.pathExtension == "swift" {
                let text = try String(contentsOf: file, encoding: .utf8).replacingOccurrences(of: "\r\n", with: "\n")
                let relative = folder + "/" + String(file.standardizedFileURL.path.dropFirst(base.path.count + 1))
                found.append(Source(path: relative, text: text, lines: text.components(separatedBy: "\n")))
            }
        }
        return found.sorted { $0.path < $1.path }
    }

    /// The source without its comment lines.
    static func code(_ source: Source) -> String {
        source.lines.filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }.joined(separator: "\n")
    }

    static func count(_ pattern: String, in text: String) -> Int {
        guard let expression = try? NSRegularExpression(pattern: pattern) else { return 0 }
        return expression.numberOfMatches(in: text, range: NSRange(text.startIndex..., in: text))
    }

    /// Each call of `name`, from its name to its closing parenthesis.
    static func calls(of name: String, in text: String) -> [String] {
        var out: [String] = []
        var from = text.startIndex
        while let found = text.range(of: name, range: from..<text.endIndex) {
            var depth = 1
            var index = found.upperBound
            while index < text.endIndex && depth > 0 {
                if text[index] == "(" { depth += 1 } else if text[index] == ")" { depth -= 1 }
                index = text.index(after: index)
            }
            out.append(String(text[found.lowerBound..<index]))
            from = found.upperBound
        }
        return out
    }

    @Test func everyServerCallGoesThroughTheExchange() throws {
        let sources = try Self.sources()
        #expect(sources.contains { $0.path == Self.funnel })
        for name in Self.outsideOnly.union(Self.ownSessions.keys).union(Self.counted.keys) {
            #expect(sources.contains { $0.path == name }, "\(name) is named here but is gone")
        }
        for source in sources where source.path != Self.funnel && !Self.outsideOnly.contains(source.path) {
            let code = Self.code(source)
            for (token, pattern) in Self.network {
                let found = Self.count(pattern, in: code)
                guard found > 0 else { continue }
                if Self.ownSessions[source.path]?.contains(token) == true { continue }
                if Self.counted[source.path]?[token] == found { continue }
                Issue.record("\(source.path): \(found) x \(token) outside ServerExchange")
            }
        }
    }

    @Test func noServerSessionKeepsCookiesOfItsOwn() throws {
        let sources = try Self.sources()
        for name in Self.ownSessions.keys.sorted() {
            let code = Self.code(try #require(sources.first { $0.path == name }))
            guard code.contains("URLSession(configuration:") else { continue }
            #expect(code.contains("httpShouldSetCookies = false"), "\(name)")
            #expect(code.contains("httpCookieStorage = nil"), "\(name)")
        }
    }

    @Test func everyByteDelegateKeepsTheSignInPagesCookies() throws {
        let sources = try Self.sources()
        for name in Self.byteDelegates {
            let code = Self.code(try #require(sources.first { $0.path == name }))
            #expect(code.contains("hop(response:"), "\(name): its redirects go through hop")
            #expect(code.contains("storeCookies(of:"), "\(name): its final answers keep their cookies")
            for call in Self.calls(of: "hop(response:", in: code) {
                #expect(call.contains("headers: signed"), "\(name): each hop gets the signing client's headers")
            }
        }
    }

    /// Red when a byte delegate goes back to a page check of its own.
    @Test func everyByteDelegateRefusesASignInPageByTheSharedRule() throws {
        let sources = try Self.sources()
        for name in Self.byteDelegates {
            let code = Self.code(try #require(sources.first { $0.path == name }))
            #expect(code.contains("signInPageInstead(of:"), "\(name): a sign-in page's refusal is judged by the shared rule")
            #expect(!code.contains("isHtml("), "\(name): a page check of its own beside the shared rule")
        }
    }

    /// The watch that requeues the running download reads the connection's own cookie store id,
    /// which every client made from one connection shares (ServerCookiesTests). Red when that
    /// watch reads anything else (a constant, the client itself), or `ObjectIdentifier` of the
    /// store again.
    @Test func theDownloadRequeueWatchesTheStoresOwnId() throws {
        let sources = try Self.sources()
        let code = Self.code(try #require(sources.first { $0.path == "Octo/Data/OfflineDownloads.swift" }))
        let requeue = try #require(code.components(separatedBy: "watches.append(").first { $0.contains("connectionReplaced()") })
        #expect(requeue.hasPrefix("Watch({ [weak self] in self?.session.client.map { $0.cookies.id } })"))
        #expect(!code.contains("ObjectIdentifier("))
    }

    /// The first launch's purge of the shared cookie store goes through `forgetKeptServerCookies`,
    /// which ServerCookiesTests runs on a store. Red when the app's purge does anything else.
    @Test func theFirstLaunchPurgeGoesThroughTheSharedOne() throws {
        let sources = try Self.sources()
        let code = Self.code(try #require(sources.first { $0.path == "Octo/App/Session.swift" }))
        let start = try #require(code.range(of: "private func forgetSharedCookies()"))
        let purge = try #require(code[start.upperBound...].components(separatedBy: "\n    }\n").first)
        #expect(purge.contains("forgetKeptServerCookies(in: HTTPCookieStorage.shared, hosts: hosts)"))
    }

    /// Red when a file shows a picture with `AsyncImage` and does not first send the server's own
    /// pictures through the exchange.
    @Test func everyOutsidePictureLeavesTheServersOwnToTheExchange() throws {
        for source in try Self.sources() where !Self.outsideOnly.contains(source.path) {
            let code = Self.code(source)
            guard Self.count(#"\bAsyncImage\("#, in: code) > 0 else { continue }
            #expect(code.contains("isServerPicture("), "\(source.path): a picture with no look at whether it is the server's")
            #expect(code.contains("ServerExchange.media("), "\(source.path): the server's own pictures do not come through the exchange")
        }
    }

    @Test func nothingReadsTheRemovedHeaders() throws {
        for source in try Self.sources() {
            let code = Self.code(source)
            // The words "Custom headers" are not the removed field.
            #expect(Self.count(#"(?<!SignInWords)\.customHeaders\b"#, in: code) == 0, "\(source.path) reads customHeaders")
            #expect(Self.count(#"\bclient\.headers\b(?!\s*\()"#, in: code) == 0, "\(source.path) reads client.headers")
        }
    }

    @Test func everyClientTheAppMakesNamesItsAddresses() throws {
        for source in try Self.sources() where source.path.hasPrefix("Octo/") {
            for call in Self.calls(of: "SubsonicClient(", in: Self.code(source)) {
                #expect(call.contains("scope:"), "\(source.path): \(call.prefix(80))")
            }
        }
    }

    @Test func serverSongsNeverComeAsLiveStreams() throws {
        let sources = try Self.sources()
        let streams = ["Octo/Playback/HlsStream.swift", "Octo/Playback/LiveStream.swift"]
        for source in sources where !streams.contains(source.path) {
            let code = Self.code(source)
            if code.contains("HlsBytes(") || code.contains("LiveBytes(") {
                #expect(source.path == "Octo/Playback/SongOpener.swift", "\(source.path) opens a live stream")
            }
        }
        for name in streams {
            let code = Self.code(try #require(sources.first { $0.path == name }))
            #expect(!code.contains("SubsonicClient") && !code.contains("streamURL("), "\(name)")
        }
        let opener = try #require(sources.first { $0.path == "Octo/Playback/SongOpener.swift" }).lines
        for (index, line) in opener.enumerated() where line.contains("HlsBytes(") || line.contains("LiveBytes(") {
            let owner = opener[..<index].last(where: { $0.contains("func ") })
            #expect(owner?.contains("func openStation(") == true, "SongOpener.swift line \(index + 1)")
        }
        for (index, line) in opener.enumerated() where line.contains("openStation(url") && !line.contains("func ") {
            #expect(opener[max(0, index - 6)...index].contains { $0.contains("isRadio(") }, "SongOpener.swift line \(index + 1)")
        }
    }
}
