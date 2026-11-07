package app.winters.octo.connection

import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// Every gateway word, against fixtures/gateway/words.json, the file the
// iPhone app's words are checked against too.
class GatewayWordsTest {
    private val fixture: Map<String, String> =
        Json.parseToJsonElement(gatewayFixture("words.json").readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }

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
        GatewayKind.entries.forEach { put("label.kind.${it.name}", GatewayWords.kindLabel(it)) }
        put("label.section", GatewayWords.LABEL_SECTION)
        put("label.caption", GatewayWords.LABEL_CAPTION)
        put("label.username", GatewayWords.LABEL_USERNAME)
        put("label.password", GatewayWords.LABEL_PASSWORD)
        put("label.clientId", GatewayWords.LABEL_CLIENT_ID)
        put("label.clientSecret", GatewayWords.LABEL_CLIENT_SECRET)
        put("label.saved", GatewayWords.LABEL_SAVED)
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
}
