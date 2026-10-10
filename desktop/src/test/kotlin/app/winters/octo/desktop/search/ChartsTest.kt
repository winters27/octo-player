package app.winters.octo.desktop.search

import app.winters.octo.desktop.FakeServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The Charts page's server side: the charts the server's country has and
// each chart's songs, only from a server that lists octoTopSongs version 2.
class ChartsTest {
    private val server = FakeServer()

    @After fun stop() = server.close()

    private val choices = """"charts":{"country":"us","chart":[
        {"id":"34","name":"Popular right now","label":"Top songs","kind":"overall","on":true},
        {"id":"new","name":"Best New Songs","label":"Best New Songs","kind":"new","on":true},
        {"id":"18","name":"Top Hip-Hop/Rap","label":"Hip-Hop/Rap","kind":"genre","on":false}
    ]}"""

    private fun chart(id: String, name: String) = """"topSongs":{"artist":null,"source":"apple","chart":"$id","name":"$name","country":"us","entry":[
        {"rank":1,"inLibrary":false,"song":{"id":"ext1","title":"Solar Eclipse","artist":"Drake","isExternal":true}}
    ]}"""

    private fun charts(extensions: List<String>, scope: CoroutineScope) = Charts(server.connection(extensions), scope)

    private suspend fun until(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(20) }

    @Test
    fun aServerWithoutTheSecondVersionIsAskedNothing() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val charts = charts(listOf("octoTopSongs:1", "octoAcquisitions:1"), scope)
        server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[{"name":"octoTopSongs","versions":[1]}]""", type = "octo")

        charts.check()
        until { charts.offered != null }
        charts.load("34")
        delay(200)

        assertFalse(charts.offered!!)
        assertFalse(server.endpoints().contains("getTopChart"))
        assertFalse(server.endpoints().contains("getCharts"))
        scope.cancel()
    }

    @Test
    fun theChoicesAndAChartAreAskedOnceAndKept() = runBlocking {
        server.answer("getCharts", choices, type = "octo")
        server.answer("getTopChart", chart("new", "Best New Songs"), type = "octo")
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val charts = charts(listOf("octoTopSongs:1", "octoTopSongs:2"), scope)

        charts.loadChoices()
        until { charts.choices != null }
        charts.loadChoices()
        charts.load("new")
        until { charts.lists["new"] != null }
        charts.load("new")
        delay(200)

        assertEquals(listOf("34", "new", "18"), charts.choices!!.chart.map { it.id })
        assertEquals(1, server.calls.count { it.url.pathSegments.last() == "getCharts" })
        val asked = server.calls.filter { it.url.pathSegments.last() == "getTopChart" }
        assertEquals(1, asked.size)
        assertEquals("new", asked.single().url.queryParameter("chart"))
        assertEquals("100", asked.single().url.queryParameter("count"))
        assertEquals("Best New Songs", charts.lists["new"]!!.name)
        scope.cancel()
    }

    @Test
    fun aGenreIsAskedForFiftySongs() = runBlocking {
        server.answer("getTopChart", chart("18", "Top Hip-Hop/Rap"), type = "octo")
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val charts = charts(listOf("octoTopSongs:2"), scope)

        charts.load("18")
        until { charts.lists["18"] != null }

        val asked = server.calls.single { it.url.pathSegments.last() == "getTopChart" }.url
        assertEquals("18", asked.queryParameter("chart"))
        assertEquals("50", asked.queryParameter("count"))
        assertTrue(charts.offered!!)
        scope.cancel()
    }
}
