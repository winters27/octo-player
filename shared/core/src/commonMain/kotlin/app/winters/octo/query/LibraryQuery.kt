package app.winters.octo.query

import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// Which songs a list shows: rules like "Added in the last month" or "Genre
// is Electronic", met all together or any one of them, plus the words typed
// in the list's filter field, then an order and how many at most. The song
// lists' filters use it now; live lists save it and the library health
// checks build on it later.
@Serializable
data class LibraryQuery(
    val rules: List<QueryRule> = emptyList(),
    val match: QueryMatch = QueryMatch.All,
    // Words that must each appear in the title, artist, album, album artist
    // or a genre, ignoring case and accents.
    val text: String = "",
    val sort: QuerySort? = null,
    val limit: Int? = null,
) {
    // Whether it leaves any song out: a rule, or typed words.
    val filters: Boolean get() = rules.isNotEmpty() || text.isNotBlank()

    // The rule in place of any other on the same field, as a filter bar
    // does: picking "Added this month" replaces "Added this week".
    fun setting(rule: QueryRule): LibraryQuery = copy(rules = rules.filter { it.field != rule.field } + rule)

    fun without(rule: QueryRule): LibraryQuery = copy(rules = rules - rule)

    // No rules and no words; the order and limit stay.
    fun cleared(): LibraryQuery = copy(rules = emptyList(), text = "")

    fun toJson(): String = QueryJson.encodeToString(serializer(), this)

    companion object {
        // A saved query, or null when the text is not one. Rules this
        // version does not know (a field or test added later) are left out
        // rather than failing the whole query, and so are unknown keys.
        fun fromJson(text: String): LibraryQuery? {
            val root = runCatching { QueryJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val rules = (root["rules"] as? JsonArray).orEmpty().mapNotNull { element ->
                runCatching { QueryJson.decodeFromJsonElement(QueryRule.serializer(), element) }.getOrNull()
            }
            val rest = runCatching { QueryJson.decodeFromJsonElement(serializer(), JsonObject(root - "rules")) }.getOrNull() ?: return null
            return rest.copy(rules = rules)
        }
    }
}

// All the rules must hold, or any one of them.
@Serializable
enum class QueryMatch {
    @SerialName("all") All,
    @SerialName("any") Any,
}

// An order for the songs, by the shared song orders' saved ids.
@Serializable
data class QuerySort(val by: String, val descending: Boolean = false) {
    // Null when the id is not a song order this version knows.
    fun order(): SortOrder? = SongSort.entries.firstOrNull { it.id == by }?.let { SortOrder(it, descending) }

    companion object {
        fun of(order: SortOrder) = QuerySort(order.by.id, order.descending)
    }
}

// What kind of value a field holds, which decides the tests it offers.
enum class FieldKind { Text, Number, Date, Flag }

// What a rule looks at. The names in quotes are what gets saved, so they
// never change once shipped.
@Serializable
enum class QueryField(val kind: FieldKind) {
    @SerialName("title") Title(FieldKind.Text),
    @SerialName("artist") Artist(FieldKind.Text),
    @SerialName("album") Album(FieldKind.Text),
    @SerialName("albumArtist") AlbumArtist(FieldKind.Text),
    @SerialName("genre") Genre(FieldKind.Text),
    @SerialName("composer") Composer(FieldKind.Text),
    @SerialName("format") Format(FieldKind.Text),
    @SerialName("year") Year(FieldKind.Number),
    @SerialName("plays") Plays(FieldKind.Number),
    @SerialName("rating") Rating(FieldKind.Number),

    // In seconds.
    @SerialName("duration") Duration(FieldKind.Number),

    // In kilobits per second.
    @SerialName("bitRate") BitRate(FieldKind.Number),
    @SerialName("bpm") Bpm(FieldKind.Number),
    @SerialName("added") Added(FieldKind.Date),
    @SerialName("lastPlayed") LastPlayed(FieldKind.Date),
    @SerialName("favourite") Favourite(FieldKind.Flag),
    @SerialName("lossless") Lossless(FieldKind.Flag),
}

// How a rule tests its field. Each kind of field offers its own few.
@Serializable
enum class QueryOp {
    // Words and numbers alike; a yes-or-no field only takes Is.
    @SerialName("is") Is,
    @SerialName("isNot") IsNot,

    // Words.
    @SerialName("contains") Contains,
    @SerialName("doesNotContain") DoesNotContain,
    @SerialName("startsWith") StartsWith,

    // Numbers.
    @SerialName("atLeast") AtLeast,
    @SerialName("atMost") AtMost,
    @SerialName("between") Between,

    // Dates.
    @SerialName("inTheLast") InTheLast,
    @SerialName("notInTheLast") NotInTheLast,
    @SerialName("before") Before,
    @SerialName("after") After,
    @SerialName("never") Never,
    ;

    companion object {
        // The tests each kind of field offers, in the order a menu lists them.
        fun forKind(kind: FieldKind): List<QueryOp> = when (kind) {
            FieldKind.Text -> listOf(Contains, Is, IsNot, StartsWith, DoesNotContain)
            FieldKind.Number -> listOf(Is, IsNot, AtLeast, AtMost, Between)
            FieldKind.Date -> listOf(InTheLast, NotInTheLast, Before, After, Never)
            FieldKind.Flag -> listOf(Is)
        }
    }
}

// One rule: a field, a test, and the value the test needs. Words go in
// `text`; a number in `number` (and the top of a range in `to`); a span in
// `days`; a moment in `at` (milliseconds since 1970); yes or no in `flag`.
// A rule whose test does not suit its field, or lacks its value, is left
// out when songs are picked.
@Serializable
data class QueryRule(
    val field: QueryField,
    val op: QueryOp,
    val text: String? = null,
    val number: Long? = null,
    val to: Long? = null,
    val days: Int? = null,
    val at: Long? = null,
    val flag: Boolean? = null,
) {
    // Whether the test suits the field and has what it needs.
    // (`this.field`, since a getter's own `field` is its backing field.)
    val complete: Boolean
        get() = op in QueryOp.forKind(this.field.kind) && when (op) {
            QueryOp.Contains, QueryOp.DoesNotContain, QueryOp.StartsWith -> !text.isNullOrEmpty()
            QueryOp.Is, QueryOp.IsNot -> when (this.field.kind) {
                FieldKind.Text -> text != null
                FieldKind.Number -> number != null
                FieldKind.Flag -> flag != null
                FieldKind.Date -> false
            }
            QueryOp.AtLeast, QueryOp.AtMost -> number != null
            QueryOp.Between -> number != null && to != null
            QueryOp.InTheLast, QueryOp.NotInTheLast -> days != null && days >= 0
            QueryOp.Before, QueryOp.After -> at != null
            QueryOp.Never -> true
        }
}

// Saved compactly: values left at their defaults are not written.
internal val QueryJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    // An unknown word where a default exists (a new way of matching) reads
    // as the default.
    coerceInputValues = true
}
