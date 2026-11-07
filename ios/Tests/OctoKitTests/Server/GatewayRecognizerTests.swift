import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import OctoKit

/// The recognizer against the vectors every app shares, and the readings it rests on.
@Suite struct GatewayRecognizerTests {
    struct File: Decodable { let vectors: [Item] }

    struct Item: Decodable {
        let name: String
        let requested: String
        let hops: [Hop]
        let htmlBody: String?
        let loginSent: Bool
        let expect: String?
    }

    struct Hop: Decodable {
        let url: String
        let status: Int
        let headers: [[String]]
    }

    static func vectors() throws -> [(name: String, facts: AnswerFacts, expect: Gateway?)] {
        let file = try JSONDecoder().decode(File.self, from: try gatewayFixture("vectors.json"))
        return file.vectors.map { item in
            let hops = item.hops.map { hop in
                AnswerHop(url: URL(string: hop.url), status: hop.status, headers: hop.headers.map { ($0[0], $0[1]) })
            }
            let facts = AnswerFacts(requested: URL(string: item.requested), hops: hops, htmlBody: item.htmlBody, loginSent: item.loginSent)
            return (item.name, facts, item.expect.flatMap(Gateway.init(rawValue:)))
        }
    }

    @Test func everySharedVectorGivesItsGateway() throws {
        let vectors = try Self.vectors()
        #expect(vectors.count >= 26)
        for vector in vectors {
            #expect(recognizeGateway(vector.facts) == vector.expect, "\(vector.name)")
        }
    }

    @Test func cookiesJoinedIntoOneHeaderAreReadOneByOne() {
        #expect(splitSetCookie("octo_admin=a1; Path=/; Expires=Sat, 07 Nov 2026 18:00:00 GMT, authentik_proxy_x=1; Path=/")
            == ["octo_admin=a1; Path=/; Expires=Sat, 07 Nov 2026 18:00:00 GMT", "authentik_proxy_x=1; Path=/"])
        let requested = URL(string: "https://music.example.com/rest/ping")
        let folded = AnswerHop(url: requested, status: 302, headers: [("Location", "https://auth.example.com/"),
                                                                       ("Set-Cookie", "octo_admin=a1; Path=/, authentik_proxy_x=1; Path=/")])
        let final = AnswerHop(url: URL(string: "https://auth.example.com/"), status: 200, headers: [("Content-Type", "text/plain")])
        #expect(recognizeGateway(AnswerFacts(requested: requested, hops: [folded, final], htmlBody: nil, loginSent: false)) == .authentik)
    }

    @Test func typesBodiesAndNamesReadAsTheOtherAppsReadThem() {
        #expect(mediaType("Text/HTML; charset=utf-8") == "text/html")
        #expect(mediaType(nil) == nil)
        #expect(mediaType(" ") == nil)
        #expect(isHtml("text/html;charset=iso-8859-1"))
        #expect(!isHtml("application/json"))
        #expect(looksLikeJson(Data("\u{FEFF}  {\"a\":1}".utf8)))
        #expect(looksLikeJson(Data("\n[1]".utf8)))
        #expect(!looksLikeJson(Data("<html>".utf8)))
        #expect(!looksLikeJson(Data()))
        #expect(isBlankBody(Data(" \r\n\t".utf8)))
        #expect(!isBlankBody(Data("{}".utf8)))
        #expect(looksLikeHtmlFile(Data([0xEF, 0xBB, 0xBF, 0x20, 0x3C, 0x68])))
        #expect(looksLikeHtmlFile(Data("\r\n<!DOCTYPE html>".utf8)))
        #expect(!looksLikeHtmlFile(Data([0xFF, 0xD8, 0xFF])))
        #expect(!looksLikeHtmlFile(Data()))
        #expect(GatewayNames.isGatewayName("authorization"))
        #expect(GatewayNames.isGatewayName("cf-access-client-SECRET"))
        #expect(!GatewayNames.isGatewayName("X-Proxy-Token"))
    }
}
