package app.winters.octo.ui.paging

import android.database.Cursor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

// One value as SQLite holds it. Two values are equal when SQLite's own
// compare finds them equal in type and content, a BLOB included (two byte
// arrays with the same bytes are equal here, unlike ByteArray).
sealed interface SqlValue {
    data object Null : SqlValue

    data class Int64(val v: Long) : SqlValue

    data class Real(val v: Double) : SqlValue

    data class Text(val v: String) : SqlValue

    data class Blob(val v: ByteString) : SqlValue

    // The value to bind as a statement's argument.
    val bound: Any?
        get() = when (this) {
            Null -> null
            is Int64 -> v
            is Real -> v
            is Text -> v
            is Blob -> v.toByteArray()
        }

    companion object {
        // A Kotlin value as SQLite stores it: whole numbers and flags as
        // integers, fractions as reals, bytes as a BLOB.
        fun of(value: Any?): SqlValue = when (value) {
            null -> Null
            is SqlValue -> value
            is Long -> Int64(value)
            is Int -> Int64(value.toLong())
            is Short -> Int64(value.toLong())
            is Byte -> Int64(value.toLong())
            is Boolean -> Int64(if (value) 1 else 0)
            is Double -> Real(value)
            is Float -> Real(value.toDouble())
            is String -> Text(value)
            is ByteArray -> Blob(value.toByteString())
            is ByteString -> Blob(value)
            else -> throw IllegalArgumentException("not an SQLite value: ${value::class.simpleName}")
        }

        // A cursor's column as the row holds it, by its stored type.
        fun of(cursor: Cursor, column: Int): SqlValue = when (cursor.getType(column)) {
            Cursor.FIELD_TYPE_NULL -> Null
            Cursor.FIELD_TYPE_INTEGER -> Int64(cursor.getLong(column))
            Cursor.FIELD_TYPE_FLOAT -> Real(cursor.getDouble(column))
            Cursor.FIELD_TYPE_BLOB -> Blob(cursor.getBlob(column).toByteString())
            else -> Text(cursor.getString(column))
        }
    }
}

// A row's place in its list: the segment it is in, the sort values its
// index holds, then the row's own id. `token` writes it as JSON with every
// value's type, for a LazyColumn key, saved state, picks and a queue's
// cursor; RowKey.of(token) reads it back as the same key, and writing that
// again gives the same token.
@Serializable(with = RowKeySerializer::class)
data class RowKey(val segment: Int, val values: List<SqlValue>, val id: String) {
    val token: String by lazy(LazyThreadSafetyMode.PUBLICATION) { RowKeyJson.write(this).toString() }

    companion object {
        fun of(token: String): RowKey = RowKeyJson.read(Json.parseToJsonElement(token))
    }
}

// The JSON form: {"s":0,"v":[["i",5],["r","<bits>"],["t","x"],["b","<base64>"],["n"]],"id":"..."}.
// A real is its IEEE bits in hex, so it reads back exact; a BLOB is base64.
internal object RowKeyJson {
    fun write(key: RowKey): JsonObject = JsonObject(
        linkedMapOf(
            "s" to JsonPrimitive(key.segment),
            "v" to JsonArray(key.values.map(::value)),
            "id" to JsonPrimitive(key.id),
        ),
    )

    fun read(element: JsonElement): RowKey = try {
        val o = element.jsonObject
        RowKey(o.getValue("s").jsonPrimitive.int, o.getValue("v").jsonArray.map(::value), o.getValue("id").jsonPrimitive.content)
    } catch (e: IllegalArgumentException) {
        throw SerializationException("not a row key: $element", e)
    } catch (e: NoSuchElementException) {
        throw SerializationException("not a row key: $element", e)
    }

    private fun value(v: SqlValue): JsonArray = when (v) {
        SqlValue.Null -> JsonArray(listOf(JsonPrimitive("n")))
        is SqlValue.Int64 -> JsonArray(listOf(JsonPrimitive("i"), JsonPrimitive(v.v)))
        is SqlValue.Real -> JsonArray(listOf(JsonPrimitive("r"), JsonPrimitive(v.v.toRawBits().toULong().toString(16).padStart(16, '0'))))
        is SqlValue.Text -> JsonArray(listOf(JsonPrimitive("t"), JsonPrimitive(v.v)))
        is SqlValue.Blob -> JsonArray(listOf(JsonPrimitive("b"), JsonPrimitive(v.v.base64())))
    }

    private fun value(element: JsonElement): SqlValue {
        val parts = element.jsonArray
        val content = parts.getOrNull(1)?.jsonPrimitive
        return when (parts[0].jsonPrimitive.content) {
            "n" -> SqlValue.Null
            "i" -> SqlValue.Int64(content!!.long)
            "r" -> SqlValue.Real(Double.fromBits(content!!.content.toULong(16).toLong()))
            "t" -> SqlValue.Text(content!!.content)
            "b" -> SqlValue.Blob(content!!.content.decodeBase64() ?: throw SerializationException("not base64: $content"))
            else -> throw SerializationException("unknown value type: $element")
        }
    }
}

// A row key inside another JSON value takes the same form as its token.
object RowKeySerializer : KSerializer<RowKey> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("app.winters.octo.ui.paging.RowKey")

    override fun serialize(encoder: Encoder, value: RowKey) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("a row key is written as JSON only")
        json.encodeJsonElement(RowKeyJson.write(value))
    }

    override fun deserialize(decoder: Decoder): RowKey {
        val json = decoder as? JsonDecoder ?: throw SerializationException("a row key is read from JSON only")
        return RowKeyJson.read(json.decodeJsonElement())
    }
}
