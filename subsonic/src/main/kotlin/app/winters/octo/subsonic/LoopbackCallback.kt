package app.winters.octo.subsonic

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// What a sign-in page sent the browser back with.
data class LoopbackAnswer(val code: String?, val state: String?, val error: String?)

// Catches a sign-in's answer on this device: a tiny web page on 127.0.0.1 at a
// free port, the way Spotify lets an app without a server of its own sign in.
// Spotify takes any port on a loopback redirect registered with none, so the
// app opens one here, asks the Octo server for a sign-in address with it, and
// hands what comes back to the server, which holds the PKCE secret.
//
// Plain sockets, so the same code runs on the desktop and on Android.
class LoopbackCallback private constructor(private val server: ServerSocket, private val host: String, val path: String) : Closeable {
    // The redirect to ask for: the registered one with this port added.
    val redirectUri: String get() = "http://$host:${server.localPort}$path"

    // Waits for the browser to come back, answering it with a page that says to
    // go back to Octo. Null when nothing came in time.
    suspend fun await(timeoutMs: Long = 10 * 60_000L): LoopbackAnswer? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.IO) {
            server.soTimeout = 500
            var answer: LoopbackAnswer? = null
            while (answer == null) {
                ensureActive()
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                answer = serve(socket)
            }
            answer
        }
    }

    // Answers one browser request, and gives back what it carried when it was
    // the sign-in's answer. A browser may open a connection it never uses; that
    // one times out and is let go.
    private fun serve(socket: Socket): LoopbackAnswer? = try {
        socket.use { client ->
            client.soTimeout = 5_000
            val line = client.getInputStream().bufferedReader().readLine().orEmpty()
            val found = parse(line.split(' ').getOrNull(1).orEmpty())
            val body = (if (found == null) NOT_HERE else if (found.error != null) REFUSED else DONE).toByteArray()
            val status = if (found == null) "404 Not Found" else "200 OK"
            client.getOutputStream().apply {
                val head = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                write(head.toByteArray())
                write(body)
                flush()
            }
            found
        }
    } catch (_: IOException) {
        null
    }

    // The answer in a request target like /callback?code=a&state=b, or null for any other address.
    internal fun parse(target: String): LoopbackAnswer? {
        val question = target.indexOf('?')
        val at = if (question < 0) target else target.substring(0, question)
        if (at != path) return null
        val query = if (question < 0) "" else target.substring(question + 1)
        val values = query.split('&').filter { it.isNotEmpty() }.associate { part ->
            val equals = part.indexOf('=')
            val name = if (equals < 0) part else part.substring(0, equals)
            val value = if (equals < 0) "" else URLDecoder.decode(part.substring(equals + 1), Charsets.UTF_8)
            name to value
        }
        if (values["code"] == null && values["error"] == null) return null
        return LoopbackAnswer(values["code"], values["state"], values["error"])
    }

    override fun close() = runCatching { server.close() }.let { }

    companion object {
        // Opens a catcher for this registered redirect, or null when it is not a
        // loopback address without a port, the only kind an app can use.
        fun open(registered: String): LoopbackCallback? {
            val uri = runCatching { URI(registered.trim()) }.getOrNull() ?: return null
            val host = uri.host ?: return null
            if (uri.scheme != "http" || uri.port != -1) return null
            val address = runCatching { InetAddress.getByName(host.trim('[', ']')) }.getOrNull() ?: return null
            if (!address.isLoopbackAddress || host.equals("localhost", ignoreCase = true)) return null
            val path = uri.rawPath?.ifEmpty { "/" } ?: "/"
            val socket = runCatching { ServerSocket(0, 4, address) }.getOrNull() ?: return null
            return LoopbackCallback(socket, host, path)
        }

        private fun page(title: String, text: String) =
            "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>$title</title><style>body{font:16px system-ui,sans-serif;background:#0d0f14;color:#e8e8ef;" +
                "display:grid;place-items:center;min-height:100vh;margin:0}main{max-width:26rem;padding:2rem}</style></head>" +
                "<body><main><h1>$title</h1><p>$text</p></main></body></html>"

        private val DONE = page("Back to Octo", "Octo has Spotify's answer and is finishing the sign-in. You can close this page and go back to Octo.")
        private val REFUSED = page("Spotify said no", "Spotify did not let Octo in. Go back to Octo to try again.")
        private val NOT_HERE = page("Nothing here", "This page only catches Spotify's answer for Octo.")
    }
}
