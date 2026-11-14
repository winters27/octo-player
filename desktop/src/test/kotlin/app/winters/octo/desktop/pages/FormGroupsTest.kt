package app.winters.octo.desktop.pages

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.winters.octo.connection.SignInWords
import app.winters.octo.design.FocusVisibility
import app.winters.octo.design.ProvideWindowLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The form's parts: where a callout scrolls to, that it waits for nothing on
// the desktop and gives way to a field taking the keyboard, and that a
// field row is named for its field and Show is never a Tab stop.
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class FormGroupsTest {
    @Test
    fun aShortGroupLandsItsTitleAtTheInset() {
        assertEquals(484, calloutTarget(500f, 100, 200, 16, 8))
        // Exactly as tall as the view less the inset still lands its title.
        assertEquals(484, calloutTarget(500f, 184, 200, 16, 8))
    }

    @Test
    fun aTallGroupEndsItsLastNoteEightAboveTheBottom() {
        assertEquals(500 + 185 + 8 - 200, calloutTarget(500f, 185, 200, 16, 8))
        assertEquals(500 + 400 + 8 - 200, calloutTarget(500f, 400, 200, 16, 8))
    }

    // A scroll of 1000 dp content in a 200 dp view: 500 dp, the group
    // (`groupHeight` tall), then 500 dp more, under a host that settles at
    // once and whose keyboard height the test sets.
    private class Rig(groupHeight: Int, keyboard: Int = 0) {
        val scroll = ScrollState(0)
        var pending by mutableStateOf(false)
        val ime = mutableIntStateOf(keyboard)
        val focus = CalloutFocus()
        var shown = 0
        val scene = ImageComposeScene(300, 200, Density(1f)) {
            ProvideWindowLook(reduceMotion = true) {
                var content by remember { mutableStateOf<LayoutCoordinates?>(null) }
                var group by remember { mutableStateOf<LayoutCoordinates?>(null) }
                val host = content?.let { CalloutHost(scroll, 16.dp, it, settled = { true }, imeBottom = { ime.intValue }, focus = focus) }
                Column(Modifier.fillMaxSize().verticalScroll(scroll).onGloballyPositioned { content = it }) {
                    Spacer(Modifier.height(500.dp))
                    Box(Modifier.fillMaxWidth().height(groupHeight.dp).onGloballyPositioned { group = it })
                    Spacer(Modifier.height(500.dp))
                }
                CalloutEffect(pending, host, { group }) {
                    pending = false
                    shown++
                }
            }
        }

        fun frames(count: Int = 4) = repeat(count) { scene.render() }
    }

    // Red when the effect does not scroll, or scrolls to the group's top.
    @Test
    fun aGroupBelowTheFoldIsBroughtUnderTheInset() {
        val rig = Rig(groupHeight = 100)
        try {
            rig.frames()
            rig.pending = true
            rig.frames()
            assertEquals(484, rig.scroll.value)
            assertEquals(1, rig.shown)
            assertFalse(rig.pending)
        } finally {
            rig.scene.close()
        }
    }

    // Red when a tall group still lands its title at the top.
    @Test
    fun aGroupTallerThanTheViewEndsItsLastNoteEightAboveTheBottom() {
        val rig = Rig(groupHeight = 300)
        try {
            rig.frames()
            rig.pending = true
            rig.frames()
            assertEquals(500 + 300 + 8 - 200, rig.scroll.value)
            assertEquals(1, rig.shown)
        } finally {
            rig.scene.close()
        }
    }

    // Red when the effect scrolls while nothing is pending.
    @Test
    fun nothingMovesWhileNothingIsPending() {
        val rig = Rig(groupHeight = 100)
        try {
            rig.frames(8)
            assertEquals(0, rig.scroll.value)
            assertEquals(0, rig.shown)
        } finally {
            rig.scene.close()
        }
    }

    // A field that takes the keyboard while the callout waits (here on a
    // keyboard 300 high) ends it with no scroll; the keyboard closing later
    // moves nothing. Red when the wait ignores the focus mark.
    @Test
    fun aFieldTakingTheKeyboardDuringTheWaitEndsItWithNoScroll() {
        val rig = Rig(groupHeight = 100, keyboard = 300)
        try {
            rig.frames()
            rig.pending = true
            rig.frames()
            assertEquals("still waiting on the keyboard", 0, rig.shown)
            rig.focus.taken = true
            rig.frames()
            assertEquals(1, rig.shown)
            assertEquals(0, rig.scroll.value)
            rig.ime.intValue = 0
            rig.frames()
            assertEquals(0, rig.scroll.value)
            assertEquals(1, rig.shown)
        } finally {
            rig.scene.close()
        }
    }

    // The same scroll with motion on: 500 dp, a group 100 dp tall, then
    // `below` dp, drawn at times the test picks.
    private class GlideRig {
        val scroll = ScrollState(0)
        var pending by mutableStateOf(false)
        var below by mutableIntStateOf(500)
        var shown = 0
        private var now = 0L
        val scene = ImageComposeScene(300, 200, Density(1f)) {
            ProvideWindowLook(reduceMotion = false) {
                var content by remember { mutableStateOf<LayoutCoordinates?>(null) }
                var group by remember { mutableStateOf<LayoutCoordinates?>(null) }
                val focus = remember { CalloutFocus() }
                val host = content?.let { CalloutHost(scroll, 16.dp, it, settled = { true }, imeBottom = { 0 }, focus = focus) }
                Column(Modifier.fillMaxSize().verticalScroll(scroll).onGloballyPositioned { content = it }) {
                    Spacer(Modifier.height(500.dp))
                    Box(Modifier.fillMaxWidth().height(100.dp).onGloballyPositioned { group = it })
                    Spacer(Modifier.height(below.dp))
                }
                CalloutEffect(pending, host, { group }) {
                    pending = false
                    shown++
                }
            }
        }

        // One frame, 16 ms after the last.
        fun frame() {
            now += 16_000_000L
            scene.render(now)
        }
    }

    // A part closing under the scroll while it glides (the 500 dp below the
    // group going to 300, which clamps the scroll from the bottom) still
    // leaves the group's title at the inset. Red when the scroll goes a
    // distance fixed at its start: it then ends short of the title by as much
    // as the clamp moved it.
    @Test
    fun aPartClosingMidScrollStillLandsTheTitleAtTheInset() {
        val rig = GlideRig()
        try {
            repeat(4) { rig.frame() }
            rig.scroll.dispatchRawDelta(10_000f)
            rig.frame()
            assertEquals(900, rig.scroll.value)
            rig.pending = true
            var frames = 0
            while (rig.scroll.value == 900 && frames < 60) {
                rig.frame()
                frames++
            }
            assertTrue("the glide started above the new bottom: ${rig.scroll.value}", rig.scroll.value in 701 until 900)
            rig.below = 300
            repeat(150) { rig.frame() }
            assertEquals(484, rig.scroll.value)
            assertEquals(1, rig.shown)
            assertFalse(rig.pending)
        } finally {
            rig.scene.close()
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            all += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return all
    }

    private fun named(scene: ImageComposeScene, name: String): SemanticsNode? =
        nodes(scene).firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true }

    private fun tab(scene: ImageComposeScene) {
        scene.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyDown, codePoint = '\t'.code))
        scene.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyUp, codePoint = '\t'.code))
        scene.render()
    }

    private fun focused(scene: ImageComposeScene): SemanticsNode? =
        nodes(scene).lastOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }

    // A field row's entry is named for its field, with the tag merged in for
    // one that may stay empty, and Tab goes from a secret's entry to the next
    // field, past Show. Red when the entry is named by its hint, or Show
    // takes a Tab stop.
    @Test
    fun aFieldRowIsNamedForItsFieldAndShowIsNoTabStop() {
        val scene = ImageComposeScene(480, 480, Density(1f)) {
            ProvideWindowLook(reduceMotion = true, focus = remember { FocusVisibility(keyboard = true) }) {
                Column {
                    FormFieldRow(SignInWords.PASSWORD, value = "pw", onChange = {}, placeholder = SignInWords.REQUIRED, secret = true, onSubmit = {})
                    FormFieldRow(SignInWords.HOME_ADDRESS, optional = true, value = "", onChange = {}, placeholder = SignInWords.HOME_PLACEHOLDER, onSubmit = {})
                }
            }
        }
        try {
            scene.render()
            assertTrue("the password entry is named for its field", named(scene, SignInWords.PASSWORD) != null)
            assertTrue("the home entry carries the tag in its name", named(scene, SignInWords.optionalName(SignInWords.HOME_ADDRESS)) != null)
            assertTrue("the tag is drawn", nodes(scene).any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == SignInWords.OPTIONAL_TAG } == true })
            tab(scene)
            assertEquals(SignInWords.PASSWORD, focused(scene)?.config?.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "))
            tab(scene)
            assertEquals(SignInWords.optionalName(SignInWords.HOME_ADDRESS), focused(scene)?.config?.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "))
            assertTrue("Show was never focused", named(scene, "Show")?.config?.getOrNull(SemanticsProperties.Focused) != true)
        } finally {
            scene.close()
        }
    }
}
