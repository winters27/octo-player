package app.winters.octo.ui.paging

// A text as a key the database orders the way Kotlin orders the text: its
// UTF-16 code units, two bytes each, high byte first. SQLite compares such
// keys byte by byte, which is String.compareTo's order, so a list ordered
// in Kotlin can be stored and walked in SQL.
object Utf16Key {
    operator fun invoke(text: String): ByteArray {
        val bytes = ByteArray(text.length * 2)
        for (i in text.indices) {
            val unit = text[i].code
            bytes[2 * i] = (unit shr 8).toByte()
            bytes[2 * i + 1] = unit.toByte()
        }
        return bytes
    }
}
