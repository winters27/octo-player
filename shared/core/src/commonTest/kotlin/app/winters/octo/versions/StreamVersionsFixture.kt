package app.winters.octo.versions

import app.winters.octo.subsonic.StreamVersions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

// fixtures/stream-versions-words.json, which the iPhone app's tests read
// too, found from the module's folder upward.
val versionsFixture: JsonObject by lazy {
    val file = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "fixtures/stream-versions-words.json") }
        .firstOrNull(File::isFile)
        ?: error("fixtures/stream-versions-words.json not found above ${File("").absolutePath}")
    Json.parseToJsonElement(file.readText()).jsonObject
}

// Read as SubsonicClient reads the server's answers.
val versionsJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

// One of the fixture's `answers`, as the client would hand it on.
fun fixtureAnswer(name: String): StreamVersions =
    versionsJson.decodeFromJsonElement(
        StreamVersions.serializer(),
        requireNotNull(versionsFixture["answers"]?.jsonObject?.get(name)) { "no answer $name" },
    )

// A field as text; null when it is missing or null.
fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
