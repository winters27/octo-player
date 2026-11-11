package app.winters.octo.ui.paging

import android.os.Bundle
import android.os.Parcel
import app.winters.octo.playback.ListStart
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RowKeyJsonTest {
    private val every = listOf(
        SqlValue.Null,
        SqlValue.Int64(0),
        SqlValue.Int64(Long.MIN_VALUE),
        SqlValue.Int64(Long.MAX_VALUE),
        SqlValue.Int64(9_007_199_254_740_993),
        SqlValue.Real(1.5),
        SqlValue.Real(-0.0),
        SqlValue.Real(Double.MIN_VALUE),
        SqlValue.Real(1e300),
        SqlValue.Text(""),
        SqlValue.Text("Untitled"),
        SqlValue.Text("quote \" backslash \\ tab \t"),
        SqlValue.Text("\uFFFD \uD83D\uDE00 \uE000 é"),
        SqlValue.Blob(ByteArray(0).toByteString()),
        SqlValue.Blob(byteArrayOf(0, 1, -1, 127, -128).toByteString()),
    )

    // Every kind of value reads back as the same key, and writing it again
    // gives the same token, byte for byte.
    @Test
    fun everyValueRoundTrips() {
        val key = RowKey(3, every, "server:h:\uD83D\uDE00")
        val back = RowKey.of(key.token)
        assertEquals(key, back)
        assertEquals(key.token, back.token)
        every.forEach { value ->
            val one = RowKey(0, listOf(value), "x")
            assertEquals(one, RowKey.of(one.token))
            assertEquals(one.token, RowKey.of(one.token).token)
        }
        // Real keeps its exact bits, the sign of zero included.
        assertNotEquals(RowKey(0, listOf(SqlValue.Real(0.0)), "x").token, RowKey(0, listOf(SqlValue.Real(-0.0)), "x").token)
    }

    // The form is fixed, so a key saved by one run reads back in the next.
    @Test
    fun theFormIsTypeTagged() {
        val key = RowKey(
            1,
            listOf(SqlValue.Int64(5), SqlValue.Text("x"), SqlValue.Blob(byteArrayOf(0, 1, -1).toByteString()), SqlValue.Null, SqlValue.Real(1.5)),
            "device:7",
        )
        assertEquals("""{"s":1,"v":[["i",5],["t","x"],["b","AAH/"],["n"],["r","3ff8000000000000"]],"id":"device:7"}""", key.token)
    }

    // BLOBs with equal bytes from two arrays are equal values, so two keys
    // read from two rows of one Downloads title are equal.
    @Test
    fun blobsAreEqualByValue() {
        val a = RowKey(0, listOf(SqlValue.of(byteArrayOf(9, 8, 7))), "t")
        val b = RowKey(0, listOf(SqlValue.of(byteArrayOf(9, 8, 7))), "t")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(setOf(a), setOf(b))
    }

    // A key survives saved state (a Bundle written out to a Parcel and read
    // back) and a play's start written as JSON.
    @Test
    fun aKeySurvivesSavedStateAndAStart() {
        val key = RowKey(2, every, "server:h:9")
        val out = Parcel.obtain()
        Bundle().apply { putString("anchor", key.token) }.writeToParcel(out, 0)
        out.setDataPosition(0)
        val restored = Bundle.CREATOR.createFromParcel(out)
        out.recycle()
        assertEquals(key, RowKey.of(restored.getString("anchor")!!))

        val start = ListStart.Row("server:h:9", key)
        assertEquals(start, ListStart.fromJson(start.toJson()))
        assertEquals(ListStart.First("t", null), ListStart.fromJson(ListStart.First("t", null).toJson()))
    }
}
