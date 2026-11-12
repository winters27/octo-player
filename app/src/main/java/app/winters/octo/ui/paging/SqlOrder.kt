package app.winters.octo.ui.paging

import okio.ByteString

// SQLite's own order of values, for every Kotlin merge or compare of keys
// read in SQL order: NULL first, then numbers by value (an integer and a
// real compared exactly), then text, then BLOBs. Text compares as SQLite's
// BINARY does on UTF-8, by code point, which differs from
// String.compareTo past U+FFFF ("�" sorts before an emoji here, after
// it there). BLOBs compare byte by byte, unsigned, a shorter prefix first.
object SqlOrder : Comparator<SqlValue> {
    override fun compare(a: SqlValue, b: SqlValue): Int {
        val ranks = rank(a).compareTo(rank(b))
        if (ranks != 0) return ranks
        return when (a) {
            SqlValue.Null -> 0
            is SqlValue.Int64 -> when (b) {
                is SqlValue.Int64 -> a.v.compareTo(b.v)
                is SqlValue.Real -> integerAndReal(a.v, b.v)
                else -> 0
            }
            is SqlValue.Real -> when (b) {
                is SqlValue.Int64 -> -integerAndReal(b.v, a.v)
                is SqlValue.Real -> reals(a.v, b.v)
                else -> 0
            }
            is SqlValue.Text -> text(a.v, (b as SqlValue.Text).v)
            is SqlValue.Blob -> bytes(a.v, (b as SqlValue.Blob).v)
        }
    }

    // Two texts as SQLite's BINARY collation orders them.
    fun text(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        return (a.length - i).compareTo(b.length - j)
    }

    fun bytes(a: ByteString, b: ByteString): Int {
        val n = minOf(a.size, b.size)
        for (k in 0 until n) {
            val d = (a[k].toInt() and 0xff) - (b[k].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size.compareTo(b.size)
    }

    // A list's order of its row keys: the segment, then the first `lead`
    // values in the chosen direction, the rest ascending, then the id, as
    // every list's order is a prefix in the chosen direction followed by
    // ascending ties. A loader whose segments or ties run otherwise orders
    // its keys itself.
    fun rows(lead: Int = 0, descending: Boolean = false): Comparator<RowKey> = Comparator { a, b ->
        val segments = a.segment.compareTo(b.segment)
        if (segments != 0) return@Comparator segments
        val n = minOf(a.values.size, b.values.size)
        for (k in 0 until n) {
            val c = compare(a.values[k], b.values[k])
            if (c != 0) return@Comparator if (descending && k < lead) -c else c
        }
        val sizes = a.values.size.compareTo(b.values.size)
        if (sizes != 0) sizes else text(a.id, b.id)
    }

    private fun rank(v: SqlValue): Int = when (v) {
        SqlValue.Null -> 0
        is SqlValue.Int64, is SqlValue.Real -> 1
        is SqlValue.Text -> 2
        is SqlValue.Blob -> 3
    }

    // SQLite holds no NaN, so reals compare as plain numbers, with 0.0 and
    // -0.0 equal as SQLite finds them.
    private fun reals(a: Double, b: Double): Int = if (a < b) -1 else if (a > b) 1 else 0

    // An integer against a real, exact past 2^53 where a Double cannot hold
    // every integer.
    private fun integerAndReal(i: Long, r: Double): Int {
        if (r < -9.223372036854775808E18) return 1
        if (r >= 9.223372036854775808E18) return -1
        val whole = r.toLong()
        if (i != whole) return i.compareTo(whole)
        val fraction = r - whole.toDouble()
        return if (fraction > 0) -1 else if (fraction < 0) 1 else 0
    }
}
