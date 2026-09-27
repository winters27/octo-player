package app.winters.octo.output

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// Something the phone hands a device: what kind of file it is, and a way
// to read it from a given byte.
interface ServedContent {
    val mimeType: String

    // Opens the content at `offset`. `total` is the whole length, when known.
    fun open(offset: Long): Opened
}

class Opened(val input: InputStream, val total: Long?) : Closeable {
    override fun close() = input.close()
}

// Bytes already in memory, such as a cover made smaller for a device.
class ServedBytes(override val mimeType: String, private val bytes: ByteArray) : ServedContent {
    override fun open(offset: Long): Opened =
        Opened(bytes.inputStream(offset.toInt().coerceIn(0, bytes.size), bytes.size), bytes.size.toLong())
}

// A new random part for an address, too long to guess.
fun interface Tokens {
    fun next(): String
}

private val random = SecureRandom()

// 128 random bits, written with letters, digits, '-' and '_'.
val SecureTokens = Tokens {
    val bytes = ByteArray(16).also(random::nextBytes)
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

// Where served files live on the server, and the longest request it reads.
private const val PATH = "/m/"
private const val MAX_HEAD_BYTES = 16 * 1024
private const val READ_TIMEOUT_MS = 20_000
private const val MAX_CONNECTIONS = 16
private const val COPY_BUFFER = 64 * 1024

// A small web server on the phone for the device music is cast to, since a
// TV or speaker cannot open the phone's files itself. It listens only on
// `address` (the phone's Wi-Fi address), and serves only what it has been
// handed, each thing at an address with its own random part: a device can
// fetch the songs in the cast queue and their covers, and nothing else.
// Whatever leaves the queue stops being served. It answers GET and HEAD,
// with byte ranges, which devices use to seek.
class MediaServer(
    private val address: InetAddress,
    private val tokens: Tokens = SecureTokens,
) {
    // What is served now: random part to content, and each thing's key
    // (a queue entry, or its cover) to its random part.
    private val served = ConcurrentHashMap<String, Entry>()
    private val byKey = ConcurrentHashMap<String, String>()

    private class Entry(val key: String, val content: ServedContent, val extension: String)

    private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null
    private val open = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val connections = AtomicInteger()

    val running: Boolean get() = socket != null

    // The server's address and port, once started.
    val base: String?
        get() = socket?.let { s -> "http://${hostOf(address)}:${s.localPort}" }

    // Starts listening on a free port. Safe to call when already started.
    @Synchronized
    fun start() {
        if (socket != null) return
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(address, 0), 16)
        val workers = Executors.newCachedThreadPool { r -> Thread(r, "octo-media-server").apply { isDaemon = true } }
        socket = server
        pool = workers
        workers.execute { accept(server, workers) }
    }

    // Stops listening, ends every transfer, and forgets everything served,
    // so no address handed out before works again.
    @Synchronized
    fun stop() {
        val server = socket ?: return
        socket = null
        runCatching { server.close() }
        open.forEach { runCatching { it.close() } }
        open.clear()
        pool?.shutdownNow()
        pool = null
        served.clear()
        byKey.clear()
    }

    // The address a device can fetch `content` at. The same key keeps its
    // address while it stays served. `extension` ends the address, for
    // devices that go by a file's name.
    fun offer(key: String, content: ServedContent, extension: String = ""): String? {
        val base = base ?: return null
        val token = byKey.getOrPut(key) { tokens.next() }
        served[token] = Entry(key, content, extension)
        return base + PATH + token + if (extension.isEmpty()) "" else ".$extension"
    }

    // Stops serving everything whose key is not in `keys`: songs that left
    // the queue, and their covers.
    fun keepOnly(keys: Set<String>) {
        byKey.keys.filter { it !in keys }.forEach { key -> byKey.remove(key)?.let(served::remove) }
    }

    // Whether anything is served under `key`.
    fun isServing(key: String): Boolean = byKey.containsKey(key)

    private fun accept(server: ServerSocket, workers: ExecutorService) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            if (connections.incrementAndGet() > MAX_CONNECTIONS) {
                connections.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            open += client
            try {
                workers.execute {
                    try {
                        client.use(::handle)
                    } finally {
                        open -= client
                        connections.decrementAndGet()
                    }
                }
            } catch (e: Exception) {
                open -= client
                connections.decrementAndGet()
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val out = BufferedOutputStream(client.getOutputStream(), COPY_BUFFER)
        try {
            val request = readRequest(client.getInputStream().buffered()) ?: return reply(out, 400, "Bad Request")
            if (request.method != "GET" && request.method != "HEAD") {
                return reply(out, 405, "Method Not Allowed", listOf("Allow" to "GET, HEAD"))
            }
            val entry = entryFor(request.path) ?: return reply(out, 404, "Not Found")
            serve(out, request, entry)
        } catch (e: IOException) {
            // The device hung up, often on purpose after reading enough, or
            // the file could not be read: either way this transfer is over.
        } finally {
            runCatching { out.flush() }
        }
    }

    private fun entryFor(path: String): Entry? {
        if (!path.startsWith(PATH)) return null
        val token = path.substring(PATH.length).substringBefore('.')
        if (token.isEmpty() || '/' in token) return null
        return served[token]
    }

    private fun serve(out: OutputStream, request: Request, entry: Entry) {
        val range = parseByteRange(request.headers["range"])
        val content = entry.content
        // A suffix ask needs the length first, so it opens at the start.
        var opened = content.open(if (range?.suffix != null) 0 else range?.start ?: 0)
        try {
            val total = opened.total
            if (total == null) {
                // Of unknown length, parts cannot be answered: send it whole.
                if (range?.start?.let { it > 0 } == true) {
                    opened.close()
                    opened = content.open(0)
                }
                head(out, 200, "OK", content, length = null, extra = emptyList())
                if (request.method == "GET") opened.input.copyTo(out, COPY_BUFFER)
                return
            }
            val bytes = if (range == null) 0L..<total else range.within(total)
            if (bytes == null) {
                return reply(out, 416, "Range Not Satisfiable", listOf("Content-Range" to "bytes */$total"))
            }
            // Opened at the start for a suffix; move to where it begins.
            if (range?.suffix != null) opened.input.skipFully(bytes.first)
            val length = bytes.last - bytes.first + 1
            if (range == null) {
                head(out, 200, "OK", content, length, emptyList())
            } else {
                head(out, 206, "Partial Content", content, length, listOf("Content-Range" to "bytes ${bytes.first}-${bytes.last}/$total"))
            }
            if (request.method == "GET") opened.input.copyExactly(out, length)
        } finally {
            opened.close()
        }
    }

    private fun head(out: OutputStream, code: Int, reason: String, content: ServedContent, length: Long?, extra: List<Pair<String, String>>) {
        val image = content.mimeType.startsWith("image/")
        val headers = buildList {
            add("Content-Type" to content.mimeType)
            length?.let { add("Content-Length" to it.toString()) }
            if (length != null) add("Accept-Ranges" to "bytes")
            add("Cache-Control" to "no-store")
            // Media renderers look for these before they play or seek.
            add("transferMode.dlna.org" to if (image) "Interactive" else "Streaming")
            if (!image) add("contentFeatures.dlna.org" to dlnaFeatures(seekable = length != null))
            addAll(extra)
        }
        writeHead(out, code, reason, headers)
    }

    private fun reply(out: OutputStream, code: Int, reason: String, extra: List<Pair<String, String>> = emptyList()) {
        writeHead(out, code, reason, listOf("Content-Length" to "0") + extra)
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: List<Pair<String, String>>) {
        val text = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        out.write(text.toByteArray(Charsets.ISO_8859_1))
    }
}

// What a device asked for: the method, the path without any query, and
// the headers by lower-case name.
internal class Request(val method: String, val path: String, val headers: Map<String, String>)

// Reads the request line and headers, up to a blank line. Null when the
// request is malformed or too long.
internal fun readRequest(input: InputStream): Request? {
    val head = StringBuilder()
    var matched = 0
    while (head.length < MAX_HEAD_BYTES) {
        val b = input.read()
        if (b < 0) return null
        head.append(b.toChar())
        // Counts through "\r\n\r\n".
        matched = when {
            b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
            b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
            b == '\r'.code -> 1
            else -> 0
        }
        if (matched == 4) break
    }
    if (matched != 4) return null
    val lines = head.split("\r\n")
    val parts = lines.first().split(' ')
    if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return null
    val target = parts[1]
    if (!target.startsWith("/")) return null
    val headers = lines.drop(1).filter { ':' in it }.associate { line ->
        line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim()
    }
    return Request(parts[0].uppercase(), target.substringBefore('?'), headers)
}

// What media renderers are told about a song: whether it can be sought in
// by bytes (DLNA.ORG_OP=01), that it is not converted, and that it streams.
fun dlnaFeatures(seekable: Boolean): String =
    "DLNA.ORG_OP=${if (seekable) "01" else "00"};DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

private fun hostOf(address: InetAddress): String {
    val host = address.hostAddress.orEmpty().substringBefore('%')
    return if (':' in host) "[$host]" else host
}

private fun InputStream.skipFully(count: Long) {
    var left = count
    while (left > 0) {
        val skipped = skip(left)
        if (skipped > 0) {
            left -= skipped
        } else {
            if (read() < 0) throw IOException("Ended early")
            left--
        }
    }
}

private fun InputStream.copyExactly(out: OutputStream, count: Long) {
    val buffer = ByteArray(COPY_BUFFER)
    var left = count
    while (left > 0) {
        val read = read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
        if (read < 0) break
        out.write(buffer, 0, read)
        left -= read
    }
}
