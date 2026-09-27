package app.winters.octo.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParametricEqFileTest {
    private val sample = """
        Preamp: -6.1 dB
        Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70
        Filter 2: ON PK Fc 180 Hz Gain -3.2 dB Q 0.55
        Filter 3: ON PK Fc 1450.5 Hz Gain 2.0 dB Q 1.93
        Filter 4: OFF PK Fc 3000 Hz Gain 4.0 dB Q 2.00
        Filter 5: ON HSC Fc 10000 Hz Gain -2.5 dB Q 0.70
    """.trimIndent()

    @Test
    fun readsThePreampAndEveryFilterThatIsOn() {
        val read = ParametricEqFile.parse(sample)
        assertEquals(-6.1f, read.preampDb, 0.0001f)
        assertEquals(4, read.filters.size)
        assertEquals(EqFilter(FilterType.LowShelf, 105f, 6.4f, 0.7f), read.filters[0])
        assertEquals(EqFilter(FilterType.Peak, 180f, -3.2f, 0.55f), read.filters[1])
        assertEquals(1450.5f, read.filters[2].frequency, 0.0001f)
        assertEquals(FilterType.HighShelf, read.filters[3].type)
    }

    @Test
    fun skipsLinesItDoesNotUnderstand() {
        val read = ParametricEqFile.parse("# a comment\nFilter 1: ON LP Fc 20000 Hz\nFilter 2: ON PK Fc 60 Hz Gain 1.5 dB Q 1.00\n")
        assertEquals(0f, read.preampDb)
        assertEquals(listOf(EqFilter(FilterType.Peak, 60f, 1.5f, 1f)), read.filters)
    }

    @Test
    fun aCorrectionIsNamedAfterItsFile() {
        val fix = ParametricEqFile.correction("Sennheiser HD 650 ParametricEQ.txt", sample)!!
        assertEquals("Sennheiser HD 650", fix.name)
        assertEquals(ParametricEqFile.IMPORTED, fix.source)
        assertEquals(-6.1f, fix.preampDb, 0.0001f)
        assertEquals(4, fix.filters.size)
        assertEquals("My curve", ParametricEqFile.nameFrom("My curve.txt"))
        assertEquals("ParametricEQ", ParametricEqFile.nameFrom("ParametricEQ.txt"))
    }

    @Test
    fun aFileWithNoFiltersIsNoCorrection() {
        assertNull(ParametricEqFile.correction("empty.txt", "Preamp: -3 dB\n"))
    }

    @Test
    fun whatIsWrittenReadsBackTheSame() {
        val filters = listOf(
            EqFilter(FilterType.LowShelf, 105f, 6.4f, 0.7f),
            EqFilter(FilterType.Peak, 1_000f, -3.5f, 1.41f),
            EqFilter(FilterType.Peak, 62.5f, 2f, 0.9f),
            EqFilter(FilterType.HighShelf, 12_000f, -1f, 0.71f),
        )
        val text = ParametricEqFile.write(-4.25f, filters)
        assertTrue(text.startsWith("Preamp: -4.3 dB\n"))
        assertTrue(text.contains("Filter 2: ON PK Fc 1000 Hz Gain -3.5 dB Q 1.41"))
        val read = ParametricEqFile.parse(text)
        assertEquals(-4.3f, read.preampDb, 0.0001f)
        assertEquals(filters.size, read.filters.size)
        filters.zip(read.filters).forEach { (wrote, back) ->
            assertEquals(wrote.type, back.type)
            assertEquals(wrote.frequency, back.frequency, 0.05f)
            assertEquals(wrote.gainDb, back.gainDb, 0.05f)
            assertEquals(wrote.q, back.q, 0.005f)
        }
    }

    @Test
    fun zeroIsWrittenWithoutASign() {
        assertTrue(ParametricEqFile.write(-0.01f, emptyList()).startsWith("Preamp: 0.0 dB"))
    }
}
