package app.winters.octo.subsonic.gateway

import app.winters.octo.subsonic.HeaderScope
import app.winters.octo.subsonic.gatewayOrigins
import app.winters.octo.subsonic.serverHeaders
import app.winters.octo.subsonic.serverOrigins
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File

// The page Authentik's outpost answers with when it stops a request.
const val AUTHENTIK_PAGE = """<html><head><title>authentik</title></head><body><ak-flow-executor aria-label="authentik"></ak-flow-executor></body></html>"""

const val HTML = "text/html; charset=utf-8"

const val PING = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""

// A client set up the way the apps' security sets up theirs: the server's
// headers on every hop, a sign-in page's only where gatewayOrigins allows,
// and a jar of its own (or the one given).
fun securedClient(
    main: HttpUrl,
    headers: Map<String, String> = emptyMap(),
    home: HttpUrl? = null,
    cookies: GatewayCookies = GatewayCookies(),
): OkHttpClient {
    val scope = HeaderScope(serverOrigins(main, home), headers, gatewayOrigins(main, home), cookies)
    return OkHttpClient.Builder().serverHeaders { scope }.build()
}

// A file of the shared gateway fixtures, found from the module's folder upward.
fun gatewayFixture(name: String): File =
    generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "fixtures/gateway/$name") }
        .firstOrNull(File::isFile)
        ?: error("fixtures/gateway/$name not found above ${File("").absolutePath}")

// One case of fixtures/gateway/vectors.json.
class Vector(val name: String, val facts: AnswerFacts, val expect: String?)

fun loadVectors(): List<Vector> {
    val root = Json.parseToJsonElement(gatewayFixture("vectors.json").readText()).jsonObject
    return root.getValue("vectors").jsonArray.map { element ->
        val vector = element.jsonObject
        val hops = vector.getValue("hops").jsonArray.map { hop ->
            val fields = hop.jsonObject
            AnswerHop(
                fields.getValue("url").jsonPrimitive.content,
                fields.getValue("status").jsonPrimitive.int,
                fields.getValue("headers").jsonArray.map { pair -> pair.jsonArray[0].jsonPrimitive.content to pair.jsonArray[1].jsonPrimitive.content },
            )
        }
        Vector(
            vector.getValue("name").jsonPrimitive.content,
            AnswerFacts(
                requested = vector.getValue("requested").jsonPrimitive.content,
                hops = hops,
                htmlBody = vector["htmlBody"]?.jsonPrimitive?.contentOrNull,
                loginSent = vector.getValue("loginSent").jsonPrimitive.boolean,
            ),
            vector["expect"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
