package app.winters.octo.subsonic.gateway

import app.winters.octo.subsonic.origin
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

// An answer a sign-in page gave instead of a song, cover or file.
class SignInPageAnswer(val gateway: Gateway?, val status: Int) : IOException("A sign-in page answered instead of the server")

// Refuses a web page where a song, cover or file was asked of the server:
// a 2xx text/html answer to a request for one of `scope`'s addresses is
// closed and thrown as SignInPageAnswer, so no player or cache ever takes
// it for media. Installed as an application interceptor, it judges the
// address the request was made for, wherever a redirect led. Every other
// address (outside radio, web pictures) is left alone.
class RefuseHtml(private val scope: () -> Set<String>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.isSuccessful || !isHtml(response.header("Content-Type")) || origin(request.url) !in scope()) return response
        val gateway = recognizeGateway(response.answerFacts())
        response.close()
        throw SignInPageAnswer(gateway, response.code)
    }
}

private val SPACES = setOf<Byte>(0x20, 0x09, 0x0D, 0x0A)

// "<" first, after any whitespace or a UTF-8 BOM: an HTML page kept as if
// it were media.
fun looksLikeHtmlFile(firstBytes: ByteArray): Boolean {
    val bom = firstBytes.size >= 3 && firstBytes[0] == 0xEF.toByte() && firstBytes[1] == 0xBB.toByte() && firstBytes[2] == 0xBF.toByte()
    var at = if (bom) 3 else 0
    while (at < firstBytes.size && firstBytes[at] in SPACES) at++
    return at < firstBytes.size && firstBytes[at] == '<'.code.toByte()
}
