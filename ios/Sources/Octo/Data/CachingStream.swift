import AVFoundation
import Foundation
import OctoKit
import UniformTypeIdentifiers

/// Streams a server song through the saved songs: every range the player asks for is answered
/// from the phone when it is saved there, and otherwise fetched from the server (with the app's
/// trusted session, the signed request's headers and the sign-in page's cookies of the client
/// that signed it) and saved as it plays, under the song's stable key. A sign-in page answering in
/// the song's place (a web page, or a 401 or 403 it is known by) is refused, never saved. Keep the
/// stream alive as long as its asset.
final class CachingStream: NSObject, AVAssetResourceLoaderDelegate, URLSessionDataDelegate, @unchecked Sendable {
    static let scheme = "octo-saved"

    private let key: String
    private let signed: SignedStream
    private let cache: StreamCache
    private let queue = DispatchQueue(label: "app.winters.octo.saved-stream")
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.default.namingOcto(Session.appVersion)
        // Cookies are kept by the signing client's own store, never by the system.
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        let delegateQueue = OperationQueue()
        delegateQueue.underlyingQueue = self.queue
        return URLSession(configuration: configuration, delegate: self, delegateQueue: delegateQueue)
    }()
    private var requests: [Int: (request: AVAssetResourceLoadingRequest, offset: Int64)] = [:]
    /// The generation of the song's saved copies this stream saves under (`StreamCache.generation`).
    private let generation: Int

    init(key: String, signed: SignedStream, generation: Int, cache: StreamCache = .shared) {
        self.key = key
        self.signed = signed
        self.generation = generation
        self.cache = cache
    }

    /// An asset that loads through this stream, from the song's signed address.
    func asset(for url: URL) -> AVURLAsset {
        var parts = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        parts.scheme = Self.scheme + "-" + (parts.scheme ?? "https")
        let asset = AVURLAsset(url: parts.url!)
        asset.resourceLoader.setDelegate(self, queue: queue)
        return asset
    }

    func resourceLoader(_ loader: AVAssetResourceLoader,
                        shouldWaitForLoadingOfRequestedResource request: AVAssetResourceLoadingRequest) -> Bool {
        guard let url = request.request.url, var parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let scheme = parts.scheme, scheme.hasPrefix(Self.scheme + "-")
        else { return false }
        parts.scheme = String(scheme.dropFirst(Self.scheme.count + 1))
        guard let source = parts.url else { return false }
        let held = Unchecked(request)
        Task { await self.answer(held, from: source) }
        return true
    }

    private func answer(_ held: Unchecked<AVAssetResourceLoadingRequest>, from source: URL) async {
        let request = held.value
        let start = request.dataRequest?.requestedOffset ?? 0
        let wantsAll = request.dataRequest?.requestsAllDataToEndOfResource ?? true
        let info = await cache.info(key)
        if let length = info?.length, let data = request.dataRequest {
            let end = wantsAll ? length : min(length, start + Int64(data.requestedLength))
            if end > start, await cache.savedRun(key, from: start) >= end - start,
               let bytes = await cache.read(key, offset: start, length: Int(end - start)) {
                let mimeType = info?.mimeType
                queue.async {
                    let request = held.value
                    if let content = request.contentInformationRequest { Self.describe(content, length: length, mimeType: mimeType) }
                    request.dataRequest?.respond(with: bytes)
                    request.finishLoading()
                }
                return
            }
        }
        var fetch = signed.asking(source)
        if let data = request.dataRequest {
            if wantsAll || request.contentInformationRequest != nil {
                fetch.setValue("bytes=\(start)-", forHTTPHeaderField: "Range")
            } else {
                fetch.setValue("bytes=\(start)-\(start + Int64(data.requestedLength) - 1)", forHTTPHeaderField: "Range")
            }
        }
        let ask = fetch
        queue.async {
            let task = self.session.dataTask(with: ask)
            self.requests[task.taskIdentifier] = (held.value, start)
            task.resume()
        }
    }

    private static func describe(_ content: AVAssetResourceLoadingContentInformationRequest, length: Int64, mimeType: String?) {
        if let mimeType, let type = UTType(mimeType: mimeType) { content.contentType = type.identifier }
        content.contentLength = length
        content.isByteRangeAccessSupported = true
    }

    func resourceLoader(_ loader: AVAssetResourceLoader, didCancel request: AVAssetResourceLoadingRequest) {
        for (id, waiting) in requests where waiting.request === request {
            requests[id] = nil
            session.getAllTasks { tasks in tasks.first { $0.taskIdentifier == id }?.cancel() }
        }
    }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge)
        async -> (URLSession.AuthChallengeDisposition, URLCredential?) {
        ServerTrust.shared.answer(challenge)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge)
        async -> (URLSession.AuthChallengeDisposition, URLCredential?) {
        ServerTrust.shared.answer(challenge)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse)
        async -> URLSession.ResponseDisposition {
        guard let waiting = requests[dataTask.taskIdentifier], let http = response as? HTTPURLResponse else { return .cancel }
        storeCookies(of: http, in: signed.cookies, scope: signed.scope)
        // A sign-in page answering in the song's place (a web page, or a 401 or 403 it is known
        // by) is never played, never saved as the song, and not asked for again.
        if signInPageInstead(of: http, sent: dataTask.originalRequest, scope: signed.scope) != nil {
            requests[dataTask.taskIdentifier] = nil
            waiting.request.finishLoading(with: PlayError(.signInPage, httpStatus: http.statusCode, detail: "a sign-in page answered"))
            return .cancel
        }
        guard (200..<300).contains(http.statusCode) else { return .cancel }
        let length = TrustedStream.totalLength(http)
        // A whole answer to a ranged ask starts at the top of the file.
        if http.statusCode == 200 { requests[dataTask.taskIdentifier]?.offset = 0 }
        await cache.describe(key, contentLength: length > 0 ? length : nil, mimeType: http.mimeType,
                             video: http.value(forHTTPHeaderField: VIDEO_HEADER), generation: generation)
        if let content = waiting.request.contentInformationRequest {
            if let mime = http.mimeType, let type = UTType(mimeType: mime) { content.contentType = type.identifier }
            content.contentLength = length
            content.isByteRangeAccessSupported = http.statusCode == 206
        }
        return .allow
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard let waiting = requests[dataTask.taskIdentifier] else { return }
        waiting.request.dataRequest?.respond(with: data)
        let offset = waiting.offset
        requests[dataTask.taskIdentifier]?.offset = offset + Int64(data.count)
        let key = key
        let cache = cache
        let made = generation
        Task { await cache.write(key, offset: offset, data: data, generation: made) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest) async -> URLRequest? {
        hop(response: response, newRequest: request, first: task.originalRequest?.url, cookies: signed.cookies, scope: signed.scope,
            headers: signed.headers)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        ServerTrust.shared.noteEnd(of: task.originalRequest, error)
        guard let waiting = requests.removeValue(forKey: task.taskIdentifier) else { return }
        if let error, (error as? URLError)?.code != .cancelled {
            waiting.request.finishLoading(with: error)
        } else {
            waiting.request.finishLoading()
        }
        let cache = cache
        Task { await cache.save() }
    }
}
