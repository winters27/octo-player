package app.winters.octo.ui.nav

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Each opening of a family link is a page of its own, and a join page on
// top is replaced rather than stacked under the next.
class JoinOpeningsTest {
    private val link = "octo://join?server=https%3A%2F%2Fmusic.example.com&invite=t"

    // Red when a join on top is stacked under the next (going back would land
    // on a spent invite's form), or another top loses the new page.
    @Test
    fun aJoinOnTopIsReplacedAndAnyOtherTopGetsOneAdded() {
        val stack = mutableListOf<NavKey>(SettingsRoute, FamilyJoinRoute(link, 1))
        stack.openJoin(FamilyJoinRoute(link, 2))
        assertEquals(listOf(SettingsRoute, FamilyJoinRoute(link, 2)), stack)
        stack.add(FamilyRoute)
        stack.openJoin(FamilyJoinRoute(link, 3))
        assertEquals(listOf(SettingsRoute, FamilyJoinRoute(link, 2), FamilyRoute, FamilyJoinRoute(link, 3)), stack)
    }

    // Navigation3 keys a page's view model on its route's text. Red when the
    // opening drops out of the route (the same link opened again would share
    // the first page's view model), or an older saved stack cannot read it.
    @Test
    fun twoOpeningsOfOneLinkAreTwoRouteKeys() {
        assertNotEquals(FamilyJoinRoute(link, 1).toString(), FamilyJoinRoute(link, 2).toString())
        assertEquals(0L, FamilyJoinRoute(link).opened)
    }

    // Red when the shell's counter is lost on a rotation, the push stops
    // counting, or the page is not told its opening.
    @Test
    fun theShellCountsEachOpeningAndPassesItOn() {
        val shell = File(System.getProperty("octo.mainSources"), "app/winters/octo/ui/nav/MainShell.kt").readText()
        assertTrue(shell.contains("var joinOpens by rememberSaveable { mutableLongStateOf(0L) }"))
        assertTrue(shell.contains("nextOpening = { ++joinOpens }"))
        assertTrue(shell.contains("stack.openJoin(FamilyJoinRoute(familyAppLink(what.link), opened = nextOpening()))"))
        assertTrue(shell.contains("join = parseFamilyLink(route.link), joinOpened = route.opened"))
    }
}
