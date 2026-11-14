package app.winters.octo.connection

import app.winters.octo.subsonic.FamilyHandOverLink
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.FamilySignInPrefill
import app.winters.octo.ui.family.HandOverSignIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// A family link's way into a form, against fixtures/gateway/signin-vectors.json,
// the file OctoKit's LinkServerTests runs too.
class LinkServerTest {
    private val vectors: JsonObject = Json.parseToJsonElement(gatewayFixture("signin-vectors.json").readText()).jsonObject

    private fun section(name: String): List<JsonObject> = vectors[name]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.flag(key: String): Boolean = this[key]!!.jsonPrimitive.boolean

    private fun JsonObject.texts(key: String): List<String> = this[key]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun facts(o: JsonObject) = LinkFacts(o.text("server")!!, o.text("base"), o.text("home"), o.text("username"), o.flag("invite"))

    // An action as the vectors write it.
    private fun shown(action: LinkAction): Map<String, Any?> = when (action) {
        is LinkAction.Refuse -> mapOf("action" to "Refuse", "why" to action.why.name)
        is LinkAction.Take ->
            if (action.kind == LinkTake.Fitting) mapOf("action" to "Fitting")
            else mapOf("action" to action.kind.name, "address" to action.address, "home" to action.home)
    }

    private fun shown(action: ReceivedAction): Map<String, Any?> = when (action) {
        is ReceivedAction.Keep -> mapOf("action" to "Keep", "useForm" to action.useForm)
        is ReceivedAction.Refuse -> mapOf("action" to "Refuse", "why" to action.why.name)
        is ReceivedAction.ForgetAndAdopt -> mapOf("action" to "ForgetAndAdopt", "address" to action.address, "home" to action.home)
    }

    private fun expected(o: JsonObject): Map<String, Any?> =
        o.mapValues { (_, value) -> value.jsonPrimitive.let { if (it.isString) it.content else it.booleanOrNull } }

    private fun check(where: String, state: LinkFormState, expect: JsonObject) {
        assertEquals("$where: anchored", expect.flag("anchored"), state.anchor != null)
        assertEquals("$where: anchorEnded", expect.flag("anchorEnded"), state.anchorEnded)
        assertEquals("$where: hadAddress", expect.flag("hadAddress"), state.hadAddress)
        assertEquals("$where: taken", expect.texts("taken"), state.taken)
        assertEquals("$where: typed", expect.texts("typed"), state.typed)
        assertEquals("$where: note", expect.text("note"), state.note)
    }

    @Test
    fun everyLinkStepGivesItsActionAndState() {
        val cases = section("linkForm")
        assertTrue(cases.size >= 50)
        for (case in cases) {
            val name = case.text("name")!!
            var state = LinkFormState(LinkForm.valueOf(case.text("kind")!!))
            var taking: LinkAction? = null
            var got: ReceivedAction? = null
            case["steps"]!!.jsonArray.map { it.jsonObject }.forEachIndexed { index, step ->
                val op = step.text("op")!!
                val where = "$name, step ${index + 1} ($op)"
                when (op) {
                    "edited" -> state = state.edited(step.text("address"), step.text("home"), step.flag("nonBlank"))
                    "filled" -> state = state.filled(LinkAnchor(step.text("main")!!, step.text("home"), step.text("username")!!))
                    "take" -> {
                        val action = state.take(facts(step["link"]!!.jsonObject), step.flag("busy"), step.text("home")!!)
                        assertEquals(where, expected(step["expect"]!!.jsonObject), shown(action))
                        taking = action
                    }
                    "took" -> state = state.took(taking!!)
                    "received" -> {
                        val signIn = step["signIn"]!!.jsonObject
                        val action = state.received(ReceivedFacts(signIn.text("server")!!, signIn.text("home"), signIn.text("username")!!))
                        assertEquals(where, expected(step["expect"]!!.jsonObject), shown(action))
                        got = action
                    }
                    "adopted" -> state = state.adopted(got!!)
                    "left" -> state = state.left()
                    "noted" -> state = state.noted(step.text("words")!!)
                    else -> error("$where: no such op")
                }
                check(where, state, step["state"]!!.jsonObject)
            }
        }
    }

    @Test
    fun addressesAreComparedInOneForm() {
        val rows = section("norm")
        assertTrue(rows.size >= 25)
        for (row in rows) assertEquals(row.text("address"), row.text("expect"), norm(row.text("address")!!))
    }

    @Test
    fun aTakenLinkWritesTheHomeItNames() {
        for (row in section("linkHome")) {
            assertEquals("$row", row.text("expect"), linkHome(row.flag("forget"), row.text("home")!!, row.text("linkHome")))
        }
    }

    @Test
    fun aSignInIsOpenWhileAnythingOfItWorksOnlyOnce() {
        val rows = section("signInOpen")
        assertTrue(rows.size >= 6)
        for (row in rows) {
            assertEquals("$row", row.flag("expect"), signInOpen(row.flag("busy"), row.flag("question"), row.flag("holdsReceived"), row.flag("holdsSignUp")))
        }
    }

    @Test
    fun eachKindOfLinkNamesWhatItCarries() {
        assertEquals(LinkFacts("https://music.example.com", home = "http://music.lan", invite = true), linkFacts(FamilyInviteLink("https://music.example.com", "t", "http://music.lan")))
        assertEquals(LinkFacts("https://music.example.com"), linkFacts(FamilySignInPrefill("https://music.example.com", " ")))
        assertEquals(LinkFacts("https://music.example.com", username = "alex"), linkFacts(FamilySignInPrefill("https://music.example.com", "alex")))
        assertEquals(
            LinkFacts("https://music.example.com", base = "http://192.168.1.20:4533", home = "http://192.168.1.20:4533", username = "alex"),
            linkFacts(FamilyHandOverLink("http://192.168.1.20:4533", "t", "k", "https://music.example.com", "http://192.168.1.20:4533", "alex")),
        )
        assertNull(receivedFacts(HandOverSignIn("alex", "s", server = "https://music.example.com", home = " ")).home)
        assertEquals("http://192.168.1.20:4533", receivedFacts(HandOverSignIn("alex", "s", server = "https://music.example.com", home = "http://192.168.1.20:4533")).home)
    }

    @Test
    fun everyRefusalSaysItInTheSharedWords() {
        assertEquals(
            listOf(
                SignInWords.LINK_WHILE_BUSY,
                SignInWords.LINK_FOR_ANOTHER_ACCOUNT,
                SignInWords.LINK_FOR_ANOTHER_SERVER,
                SignInWords.CODE_ELSEWHERE,
                SignInWords.LINK_AFTER_ADDRESS_CHANGE,
            ),
            LinkRefusal.entries.map { it.words },
        )
    }
}
