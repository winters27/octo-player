package app.winters.octo.connection

import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.gateway.Gateway
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Every sign-in word against fixtures/gateway/signin-words.json, the file
// the iPhone app's words are checked against too. Templates are filled with
// `{name}` (and `{date}`), so the fixture shows where a name goes.
class SignInWordsTest {
    private val fixture: Map<String, String> =
        Json.parseToJsonElement(gatewayFixture("signin-words.json").readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun kotlinWords(): Map<String, String> = buildMap {
        put("advanced", SignInWords.ADVANCED)
        put("advanced.line", SignInWords.ADVANCED_LINE)
        put("group.gettingThere", SignInWords.GROUP_GETTING_THERE)
        put("group.signingIn", SignInWords.GROUP_SIGNING_IN)
        put("group.specialSetups", SignInWords.GROUP_SPECIAL_SETUPS)
        put("row.serverAddress", SignInWords.SERVER_ADDRESS)
        put("row.serverName", SignInWords.SERVER_NAME)
        put("row.username", SignInWords.USERNAME)
        put("row.password", SignInWords.PASSWORD)
        put("row.apiKey", SignInWords.API_KEY)
        put("row.yourName", SignInWords.YOUR_NAME)
        put("row.passwordAgain", SignInWords.PASSWORD_AGAIN)
        put("row.homeAddress", SignInWords.HOME_ADDRESS)
        put("row.method", SignInWords.METHOD)
        put("row.customHeaders", SignInWords.CUSTOM_HEADERS)
        put("row.headerName", SignInWords.HEADER_NAME)
        put("row.headerValue", SignInWords.HEADER_VALUE)
        put("addHeader", SignInWords.ADD_HEADER)
        put("removeHeader", SignInWords.removeHeader("{name}"))
        put("removeHeader.blank", SignInWords.removeHeader(""))
        put("placeholder.required", SignInWords.REQUIRED)
        put("placeholder.unchanged", SignInWords.UNCHANGED)
        put("placeholder.home", SignInWords.HOME_PLACEHOLDER)
        put("placeholder.name", SignInWords.NAME_PLACEHOLDER)
        put("tag.optional", SignInWords.OPTIONAL_TAG)
        put("tag.optional.name", SignInWords.optionalName("{name}"))
        put("note.home", SignInWords.HOME_NOTE)
        put("description.headers", SignInWords.HEADERS_DESCRIPTION)
        put("note.apiKeyInTheOpen", SignInWords.API_KEY_IN_THE_OPEN)
        AuthMode.entries.forEach { mode ->
            put("method.${mode.name}", SignInWords.methodLabel(mode))
            put("method.helper.${mode.name}", SignInWords.methodHelper(mode, null))
        }
        put("method.helper.ApiKey.known", SignInWords.methodHelper(AuthMode.ApiKey, true))
        put("error.unreachable", SignInWords.UNREACHABLE)
        put("error.restarting", SignInWords.RESTARTING)
        put("error.notMusic", SignInWords.NOT_MUSIC)
        put("error.wrongPassword", SignInWords.WRONG_PASSWORD)
        put("error.wayRefused", SignInWords.WAY_REFUSED)
        put("error.somethingWrong", SignInWords.SOMETHING_WRONG)
        put("error.badAddress", SignInWords.BAD_ADDRESS)
        put("error.badHomeAddress", SignInWords.BAD_HOME_ADDRESS)
        put("error.missingSecret", SignInWords.MISSING_SECRET)
        put("error.gone", SignInWords.GONE)
        put("error.alreadyKept", SignInWords.alreadyKept("{name}"))
        put("legacy.hint", SignInWords.LEGACY_HINT)
        put("legacy.unsafe", SignInWords.LEGACY_UNSAFE)
        put("cert.title", SignInWords.CERT_TITLE)
        put("cert.none", SignInWords.CERT_NONE)
        put("cert.choose", SignInWords.CERT_CHOOSE)
        put("cert.change", SignInWords.CERT_CHANGE)
        put("cert.remove", SignInWords.CERT_REMOVE)
        put("cert.line", SignInWords.certificateLine("{name}", "{date}"))
        put("cert.password.title", SignInWords.CERT_PASSWORD_TITLE)
        put("cert.password.hint", SignInWords.CERT_PASSWORD_HINT)
        put("cert.password.field", SignInWords.CERT_PASSWORD_FIELD)
        put("cert.password.use", SignInWords.CERT_PASSWORD_USE)
        put("cert.asked", SignInWords.CERT_ASKED)
        put("cert.refused", SignInWords.CERT_REFUSED)
        put("cert.keyKind", SignInWords.CERT_KEY_KIND)
        put("cert.wrongPassword", SignInWords.CERT_WRONG_PASSWORD)
        put("cert.notAFile", SignInWords.CERT_NOT_A_FILE)
        put("cert.noKey", SignInWords.CERT_NO_KEY)
        put("cert.notForClients", SignInWords.CERT_NOT_FOR_CLIENTS)
        put("cert.expired", SignInWords.CERT_EXPIRED)
        put("cert.notYetValid", SignInWords.CERT_NOT_YET_VALID)
        put("cert.tooBig", SignInWords.CERT_TOO_BIG)
        put("link.whileBusy", SignInWords.LINK_WHILE_BUSY)
        put("link.forAnotherAccount", SignInWords.LINK_FOR_ANOTHER_ACCOUNT)
        put("link.forAnotherServer", SignInWords.LINK_FOR_ANOTHER_SERVER)
        put("link.codeElsewhere", SignInWords.CODE_ELSEWHERE)
        put("link.afterAddressChange", SignInWords.LINK_AFTER_ADDRESS_CHANGE)
        put("link.whileSignInOpen", SignInWords.LINK_WHILE_SIGN_IN_OPEN)
        put("link.askWhileSignInOpen", SignInWords.askWhileSignInOpen("{name}"))
        put("trust.title", SignInWords.TRUST_TITLE)
        put("trust.changed.title", SignInWords.TRUST_CHANGED_TITLE)
        put("trust.host", SignInWords.TRUST_HOST)
        put("trust.fingerprint", SignInWords.TRUST_FINGERPRINT)
        put("trust.confirm", SignInWords.TRUST_CONFIRM)
        put("trust.refused", SignInWords.TRUST_REFUSED)
        put("trust.detail", SignInWords.trustDetail("{device}"))
        put("trust.changed.detail", SignInWords.TRUST_CHANGED_DETAIL)
        Gateway.entries.forEach { gateway ->
            for (sent in listOf(false, true)) {
                put("inGroup.${gateway.name}.$sent", GatewayWords.inGroup(GatewayWords.failure(gateway, sent)))
            }
        }
    }

    @Test
    fun everyWordMatchesTheSharedFile() {
        assertEquals(fixture, kotlinWords())
    }

    // Shared words are plain ASCII, so no dash or curly quote slips in. The
    // one exception is in the words `glued` lists: a no-break space, which
    // keeps a wrapped line's last two words together, or a word joiner, which
    // keeps "Wi-Fi" whole.
    @Test
    fun everyWordIsPlainAscii() {
        val glued = setOf("advanced.line", "note.home")
        kotlinWords().forEach { (key, words) ->
            val mayGlue = key in glued || key.startsWith("inGroup.")
            assertTrue(key, words.all { it.code in 0x20..0x7E || (mayGlue && (it.code == 0xA0 || it.code == 0x2060)) })
        }
    }

    // Red when a line that wraps on the phones loses the no-break space that
    // keeps its last two words together (it would end on one word there), or
    // carries one anywhere but there.
    @Test
    fun theWrappingLinesKeepTheirLastTwoWordsTogether() {
        val glue = Char(0xA0)
        for (line in listOf(SignInWords.ADVANCED_LINE, GatewayWords.POINTER)) {
            assertEquals(line, 1, line.count { it == glue })
            assertTrue(line, line.lastIndexOf(' ') < line.indexOf(glue))
        }
    }

    // A row from Add header starts blank; its Remove button still has a name.
    @Test
    fun aBlankHeaderNameHasItsOwnRemoveName() {
        assertEquals("Remove header", SignInWords.removeHeader("   "))
        assertEquals("Remove header X-Token", SignInWords.removeHeader(" X-Token "))
    }

    // The API key's line says what the server said about keys, once known.
    @Test
    fun theApiKeyLineFollowsWhatTheServerSaid() {
        assertEquals(SignInWords.methodHelper(AuthMode.ApiKey, null), SignInWords.methodHelper(AuthMode.ApiKey, false))
        assertNotEquals(SignInWords.methodHelper(AuthMode.ApiKey, null), SignInWords.methodHelper(AuthMode.ApiKey, true))
    }

    // Red when the group's error note sends the person "under Advanced",
    // where they already are, or when inGroup rewrites a refusal's words or
    // the words every other screen shows.
    @Test
    fun theGroupsErrorNoteNeverSendsThePersonUnderAdvanced() {
        for (gateway in Gateway.entries) {
            assertFalse(gateway.name, GatewayWords.inGroup(GatewayWords.failure(gateway, false)).contains("under Advanced"))
            assertEquals(gateway.name, GatewayWords.failure(gateway, true), GatewayWords.inGroup(GatewayWords.failure(gateway, true)))
            assertTrue(gateway.name, GatewayWords.failure(gateway, false).endsWith("fields under Advanced."))
        }
    }
}
