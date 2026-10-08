package app.winters.octo.ui.charts

import app.winters.octo.discovery.ChartList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// What the Charts page says above a chart: who ranked it, and for where.
class ChartsCaptionTest {
    private fun list(chart: String, source: String, country: String?) = ChartList(chart, null, source, country, emptyList())

    @Test
    fun aChartSaysWhoRankedItAndForWhichCountry() {
        assertEquals("Most played on Apple Music in United States", caption(list("34", "apple", "us")))
        assertEquals("Picked by Apple Music in United Kingdom", caption(list("new", "apple", "gb")))
        assertEquals("Trending on Apple Music in Japan", caption(list("trending", "apple", "jp")))
    }

    @Test
    fun withoutACountryOnlyWhoRankedIt() {
        assertEquals("Most popular on Deezer", caption(list("34", "deezer", null)))
        assertEquals("Most played on Apple Music", caption(list("18", "apple", "usa")))
    }

    @Test
    fun aListNobodyRankedSaysNothing() {
        assertNull(caption(list("34", "", "us")))
    }
}
