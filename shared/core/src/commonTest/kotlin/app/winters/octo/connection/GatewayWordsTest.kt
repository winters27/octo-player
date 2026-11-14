package app.winters.octo.connection

import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

// Every gateway word, against fixtures/gateway/words.json, the file the
// iPhone app's words are checked against too.
class GatewayWordsTest {
    private val fixture: Map<String, String> =
        Json.parseToJsonElement(gatewayFixture("words.json").readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }

    // Every sign-in page met, and none.
    private val detected: List<Gateway?> = listOf<Gateway?>(null) + Gateway.entries

    private fun kotlinWords(): Map<String, String> = buildMap {
        Gateway.entries.forEach { gateway ->
            put("name.${gateway.name}", GatewayWords.name(gateway))
            put("failure.${gateway.name}.false", GatewayWords.failure(gateway, false))
            put("failure.${gateway.name}.true", GatewayWords.failure(gateway, true))
            put("hint.${gateway.name}", GatewayWords.hint(gateway))
            put("row.${gateway.name}.false", GatewayWords.row(gateway, false))
            put("row.${gateway.name}.true", GatewayWords.row(gateway, true))
        }
        put("row.needsHttps", GatewayWords.ROW_NEEDS_HTTPS)
        GatewayKind.entries.forEach { kind ->
            put("label.kind.${kind.name}", GatewayWords.kindLabel(kind))
            GatewayWords.kindHelper(kind)?.let { put("label.helper.${kind.name}", it) }
            GatewayWords.tip(kind)?.let { put("tip.${kind.name}", it) }
        }
        for (kind in listOf(GatewayKind.Password, GatewayKind.CloudflareToken)) {
            for (met in detected) {
                val (first, second) = GatewayWords.fieldLabels(kind, met)
                put("field.${kind.name}.${met?.name ?: "none"}.first", first)
                put("field.${kind.name}.${met?.name ?: "none"}.second", second)
            }
        }
        put("label.section", GatewayWords.LABEL_SECTION)
        put("label.caption", GatewayWords.LABEL_CAPTION)
        put("label.clientId", GatewayWords.LABEL_CLIENT_ID)
        put("label.clientSecret", GatewayWords.LABEL_CLIENT_SECRET)
        put("pointer", GatewayWords.POINTER)
        put("join.hint", GatewayWords.JOIN_HINT)
        put("share.line", GatewayWords.SHARE_LINE)
        put("play.failure", GatewayWords.PLAY_FAILURE)
        put("needsHttps", GatewayWords.NEEDS_HTTPS)
        put("problem.bothPassword", GatewayWords.PROBLEM_BOTH_PASSWORD)
        put("problem.bothToken", GatewayWords.PROBLEM_BOTH_TOKEN)
        put("problem.colon", GatewayWords.PROBLEM_COLON)
        put("problem.control", GatewayWords.PROBLEM_CONTROL)
        put("problem.reservedRow", GatewayWords.PROBLEM_RESERVED_ROW)
    }

    @Test
    fun everyWordMatchesTheSharedFile() {
        assertEquals(fixture, kotlinWords())
    }

    @Test
    fun noWordHasADashOrACurlyQuote() {
        kotlinWords().values.forEach { words -> assertFalse(words, words.any { it in "—–‘’“”" }) }
    }

    // Red when a word calls Octo "the app" where every other line names it.
    @Test
    fun noWordCallsOctoTheApp() {
        val theApp = Regex("\\bthe app\\b")
        kotlinWords().values.forEach { words -> assertFalse(words, theApp.containsMatchIn(words)) }
    }

    // Red when a sign-in page field row reads like the server's own username
    // or password again.
    @Test
    fun noSignInPageFieldIsNamedLikeTheServersOwnLogin() {
        for (kind in GatewayKind.entries) {
            for (met in detected) {
                val (first, second) = GatewayWords.fieldLabels(kind, met)
                for (label in listOf(first, second)) {
                    assertNotEquals("$kind $met", SignInWords.USERNAME, label)
                    assertNotEquals("$kind $met", SignInWords.PASSWORD, label)
                }
            }
        }
    }
}
