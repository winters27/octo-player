package app.winters.octo.connection

import app.winters.octo.data.HeaderDraft
import app.winters.octo.data.resolveHeaders
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The sign-in form's rules against fixtures/gateway/signin-vectors.json, the
// file OctoKit's SignInFormRulesTests runs too.
class SignInFormRulesTest {
    private val vectors: JsonObject = Json.parseToJsonElement(gatewayFixture("signin-vectors.json").readText()).jsonObject

    private fun section(name: String): List<JsonObject> = vectors[name]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.flag(key: String): Boolean = this[key]!!.jsonPrimitive.boolean

    private fun JsonObject.mode(key: String): AuthMode? = text(key)?.let(AuthMode::valueOf)

    private fun formState(o: JsonObject) = GatewayFormState(
        advancedOpen = o.flag("advancedOpen"),
        kind = GatewayKind.valueOf(o.text("kind")!!),
        detected = o.text("detected")?.let(Gateway::valueOf),
        detectedFor = o.text("detectedFor"),
        callout = o.flag("callout"),
        calloutPending = o.flag("calloutPending"),
        keyPending = o.flag("keyPending"),
    )

    private fun row(o: JsonObject) = HeaderDraft(o.text("name")!!, o.text("value")!!, o.flag("saved"))

    @Test
    fun everyFormStepGivesItsState() {
        val cases = section("gatewayForm")
        assertTrue(cases.size >= 12)
        for (case in cases) {
            val name = case.text("name")!!
            var now = formState(case["start"]!!.jsonObject)
            case["steps"]!!.jsonArray.map { it.jsonObject }.forEachIndexed { index, step ->
                val op = step.text("op")!!
                now = when (op) {
                    "reactToFailure" -> reactToFailure(now, step.text("attempt")!!, step.text("current")!!, step.text("gateway")?.let(Gateway::valueOf))
                    "addressChanged" -> addressChanged(now, step.text("address")!!)
                    "gatewayEdited" -> gatewayEdited(now)
                    "kindPicked" -> kindPicked(now, GatewayKind.valueOf(step.text("kind")!!))
                    "calm" -> calm(now)
                    "sending" -> sending(now)
                    "calloutShown" -> calloutShown(now)
                    "pointTo" -> pointTo(now)
                    "methodPicked" -> methodPicked(now, step.mode("from")!!, step.mode("to")!!, step.flag("keepsKey"))
                    "keyShown" -> keyShown(now)
                    else -> error("$name: no op $op")
                }
                assertEquals("$name, step ${index + 1} ($op)", formState(step["expect"]!!.jsonObject), now)
            }
        }
    }

    @Test
    fun theMethodAndItsFlagsReadBothWays() {
        val modes = section("authModeOf")
        assertEquals(4, modes.size)
        for (row in modes) assertEquals("$row", row.mode("expect"), authModeOf(row.flag("legacy"), row.flag("apiKey")))
        val flags = section("flagsOf")
        assertEquals(3, flags.size)
        for (row in flags) assertEquals("$row", row.flag("legacy") to row.flag("apiKey"), flagsOf(row.mode("mode")!!))
    }

    @Test
    fun theMethodsOfferedFollowWhatTheServerSaid() {
        for (row in section("methodsShown")) {
            val expect = row["expect"]!!.jsonArray.map { AuthMode.valueOf(it.jsonPrimitive.content) }
            assertEquals("$row", expect, methodsShown(row["takesApiKeys"]!!.jsonPrimitive.booleanOrNull, row.flag("apiKeyOn")))
        }
    }

    @Test
    fun aSavedSecretStandsInOnlyForItsOwnKind() {
        val rows = section("keeps")
        assertTrue(rows.size >= 10)
        for (row in rows) {
            val keepsSecret = row.flag("keepsSecret")
            val saved = row.mode("savedMode")
            val mode = row.mode("mode")!!
            assertEquals("$row password", row.flag("password"), keepsPassword(keepsSecret, saved, mode))
            assertEquals("$row key", row.flag("key"), keepsKey(keepsSecret, saved, mode))
        }
    }

    @Test
    fun advancedIsLaidOutInOneOrderEverywhere() {
        for (row in section("advancedLayout")) {
            val expect = row["expect"]!!.jsonObject
            val groups = expect["groups"]!!.jsonArray.map { it.jsonObject }.map { group ->
                LayoutGroup(AdvancedGroup.valueOf(group.text("group")!!), group["rows"]!!.jsonArray.map { AdvancedRow.valueOf(it.jsonPrimitive.content) })
            }
            val methods = expect["methods"]!!.jsonArray.map { AuthMode.valueOf(it.jsonPrimitive.content) }
            val layout = advancedLayout(
                joining = row.flag("joining"),
                takesApiKeys = row["takesApiKeys"]!!.jsonPrimitive.booleanOrNull,
                apiKeyOn = row.flag("apiKeyOn"),
                kind = GatewayKind.valueOf(row.text("kind")!!),
                certificateRow = row.flag("certificateRow"),
            )
            assertEquals(row.text("name"), AdvancedLayout(groups, methods), layout)
        }
    }

    @Test
    fun theGroupsCarryTheirTitlesInOrder() {
        assertEquals(
            listOf(SignInWords.GROUP_GETTING_THERE, SignInWords.GROUP_SIGNING_IN, SignInWords.GROUP_SPECIAL_SETUPS),
            AdvancedGroup.entries.map { it.title },
        )
    }

    // Every header step by the rows' names, values and saved marks; after
    // every step the ids are as many as the rows and all different.
    @Test
    fun everyHeaderStepGivesItsRows() {
        val cases = section("headerList")
        assertTrue(cases.size >= 8)
        for (case in cases) {
            val name = case.text("name")!!
            var list = HeaderList.empty
            val removed = HashMap<Int, Long>()
            case["steps"]!!.jsonArray.map { it.jsonObject }.forEachIndexed { index, step ->
                val op = step.text("op")!!
                val id = step["row"]?.jsonPrimitive?.int?.let { list.ids[it] }
                    ?: step["removedIn"]?.jsonPrimitive?.int?.let { removed.getValue(it) }
                list = when (op) {
                    "replaced" -> list.replaced(step["rows"]!!.jsonArray.map { row(it.jsonObject) })
                    "added" -> list.added().first
                    "renamed" -> list.renamed(id!!, step.text("to")!!)
                    "valued" -> list.valued(id!!, step.text("to")!!)
                    "remove" -> list.remove(id!!).also { removed[index + 1] = id }
                    else -> error("$name: no op $op")
                }
                val where = "$name, step ${index + 1} ($op)"
                assertEquals(where, step["expect"]!!.jsonArray.map { row(it.jsonObject) }, list.rows)
                assertEquals(where, list.rows.size, list.ids.size)
                assertEquals(where, list.ids.size, list.ids.toSet().size)
            }
        }
    }

    // A saved row whose value is only spaces or a newline keeps its saved
    // value, read the way the request is built.
    @Test
    fun aBlankValueInASavedRowKeepsTheSavedOne() {
        val cases = section("savedHeaderValues")
        assertTrue(cases.size >= 5)
        for (case in cases) {
            val rows = case["rows"]!!.jsonArray.map { row(it.jsonObject) }
            val saved = case["saved"]!!.jsonObject.map { (name, value) -> ServerHeader(name, value.jsonPrimitive.content) }
            val expect = case["expect"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            assertEquals(case.text("name"), expect, cleanHeaders(resolveHeaders(rows, saved)).associate { it.name to it.value })
        }
    }
}
