package app.winters.octo.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Every case in song-identity-cases.json, the file the Octo server runs too.
// A failure names the case by its note, so a rule changed on one side shows
// up here before the two disagree. Each failing case is listed, not only the
// first.
class SongIdentityCasesTest {
    private val cases: JsonObject = javaClass.classLoader!!.getResource("song-identity-cases.json")!!
        .readText().let { Json.parseToJsonElement(it).jsonObject }

    private fun section(name: String): List<JsonObject> = cases.getValue(name).jsonArray.map { it.jsonObject }

    private fun text(element: JsonElement?): String? = when (element) {
        null, is JsonNull -> null
        else -> element.jsonPrimitive.content
    }

    private fun strings(element: JsonElement): List<String> = element.jsonArray.map { it.jsonPrimitive.content }

    private fun ref(side: JsonObject) = SongRef(
        text(side["title"]),
        text(side["artist"]),
        (side["seconds"] as? JsonPrimitive)?.doubleOrNull,
        // One ISRC as a string, or several as a list. Absent in every case
        // written before it.
        when (val isrc = side["isrc"]) {
            null -> emptyList()
            is JsonArray -> strings(isrc)
            else -> listOf(isrc.jsonPrimitive.content)
        },
    )

    private fun options(case: JsonObject): SongMatchOptions {
        val options = case["options"]?.jsonObject ?: return SongMatchOptions.Default
        var result = SongMatchOptions.Default
        options["lengthToleranceSeconds"]?.let { result = result.copy(lengthToleranceSeconds = if (it is JsonNull) null else it.jsonPrimitive.int) }
        options["extrasMustAgree"]?.let { result = result.copy(extrasMustAgree = it.jsonPrimitive.boolean) }
        options["alsoNeutral"]?.let { result = result.copy(alsoNeutral = strings(it).toSet()) }
        return result
    }

    private fun assertNoFailures(failures: List<String>) {
        assertTrue("${failures.size} failing:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun theFileHasEveryCase() {
        assertEquals(231, section("compare").size)
        assertEquals(58, section("parse").size)
        assertEquals(6, section("queries").size)
    }

    @Test
    fun everyComparisonReadsTheSameFromEitherSide() {
        val failures = ArrayList<String>()
        for (case in section("compare")) {
            val note = text(case["note"])
            val expect = when (val word = text(case["expect"])) {
                "same" -> SongVerdict.Same
                "version" -> SongVerdict.SameSongDifferentVersion
                "different" -> SongVerdict.Different
                else -> error("unknown expectation '$word'")
            }
            val a = ref(case.getValue("a").jsonObject)
            val b = ref(case.getValue("b").jsonObject)
            val options = options(case)
            for ((label, match) in listOf("forward" to SongIdentity.same(a, b, options), "reversed" to SongIdentity.same(b, a, options))) {
                if (match.verdict != expect) failures += "$note ($label) expected $expect, got ${match.verdict} (${match.reason})"
                if (match.confidence !in 0.0..1.0) failures += "$note ($label) confidence ${match.confidence}"
                if (match.reason.isBlank()) failures += "$note ($label) gave no reason"
            }
        }
        assertNoFailures(failures)
    }

    @Test
    fun everyTitleAndCreditParsesAsTheFileSays() {
        val failures = ArrayList<String>()
        for (case in section("parse")) {
            val note = text(case["note"])
            val input = case.getValue("input").jsonObject
            val title = text(input["title"])
            val artist = text(input["artist"])

            val parsed = SongIdentity.parseTitle(title, artist)
            val wantKey = text(case["expectTitleKey"])
            val wantLoose = text(case["expectLooseKey"])
            val wantVersions = strings(case.getValue("expectVersions"))
            if (parsed.key != wantKey) failures += "$note: title key '${parsed.key}', expected '$wantKey'"
            if (parsed.looseKey != wantLoose) failures += "$note: loose key '${parsed.looseKey}', expected '$wantLoose'"
            if (parsed.versions != wantVersions) failures += "$note: versions ${parsed.versions}, expected $wantVersions"

            val credit = SongIdentity.parseArtists(if (artist.isNullOrBlank()) parsed.artistFromTitle else artist)
            val artists = (credit.names + credit.featured + parsed.featured).map(SongIdentity::key).distinct()
            val wantArtists = strings(case.getValue("expectArtists"))
            if (artists != wantArtists) failures += "$note: artists $artists, expected $wantArtists"
        }
        assertNoFailures(failures)
    }

    @Test
    fun everySearchTriesTheQueriesInOrder() {
        val failures = ArrayList<String>()
        for (case in section("queries")) {
            val input = case.getValue("input").jsonObject
            val queries = SongIdentity.queryVariants(text(input["title"]), text(input["artist"])).map { it.text }
            val want = strings(case.getValue("expect"))
            if (queries != want) failures += "${text(case["note"])}: $queries, expected $want"
        }
        assertNoFailures(failures)
    }
}
