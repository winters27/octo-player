package app.winters.octo.connection

import app.winters.octo.subsonic.HeaderScope
import app.winters.octo.subsonic.gateway.GatewayCookies
import app.winters.octo.subsonic.gatewayOrigins
import app.winters.octo.subsonic.origin
import app.winters.octo.subsonic.serverHeaders
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File

// The page Authentik's outpost answers with when it stops a request.
const val GATEWAY_PAGE = """<html><head><title>authentik</title></head><body><ak-flow-executor aria-label="authentik"></ak-flow-executor></body></html>"""

const val PAGE_TYPE = "text/html; charset=utf-8"

// A client set up the way the apps' security sets up theirs, for a server
// at `main` with these headers and a jar of its own.
fun securedHttp(main: HttpUrl, headers: Map<String, String> = emptyMap()): OkHttpClient {
    val scope = HeaderScope(setOf(origin(main)), headers, gatewayOrigins(main, null), GatewayCookies())
    return OkHttpClient.Builder().serverHeaders { scope }.build()
}

// A file of the shared gateway fixtures, found from the module's folder upward.
fun gatewayFixture(name: String): File =
    generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "fixtures/gateway/$name") }
        .firstOrNull(File::isFile)
        ?: error("fixtures/gateway/$name not found above ${File("").absolutePath}")
