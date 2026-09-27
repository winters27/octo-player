package app.winters.octo.lyrics.engine

import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.max

// How long the press on a tapped line takes to go in.
const val PRESS_S = 0.08
const val PRESSED_SCALE = 0.97

// One line's moving parts: where it is (a spring), how big (a spring from
// 0 at the other lines' size to 1 at full size), how clear and how sharp,
// whether it is being sung, and whether its word animation is ready.
class LineState(val line: SyncLine) {
    val y = linePositionSpring()
    val grown = lineScaleSpring()
    var opacity = 0.0
    var blur = 0.0
    var status = LineStatus.Upcoming

    // Its height now; an interlude's changes as its dots open and close.
    var height = 0.0

    // Its word animation is prepared, near the focus.
    var live = false

    // Pressed by a finger, and how far the press has gone in (0 to 1).
    var pressed = false
    var pressHeldUntil = 0L
    var press = 0.0

    // An interlude reached by a seek shows its dots already grown.
    var grownIn = false

    // Bumped when its place, size, opacity or blur changes, and when what
    // it draws changes. Its layer and its drawing read one each, so a line
    // that did not change this frame is not touched.
    val layerVersion = mutableIntStateOf(0)
    val drawVersion = mutableIntStateOf(0)

    // What it last drew with, to tell when that changes.
    var drawnBrightness: Brightness? = null

    // From when to when its words move: bobs start 0.4 s before a letter,
    // and a held word's last letter (with the last word's longer swell and
    // its bob) settles about 2.2 lengths after the word starts. Lines not
    // timed word by word have no moving words.
    private val wordTimed = !line.isLineTimed && !line.isInterlude && !line.isCredit && line.words.isNotEmpty()
    val motionFrom: Double = if (wordTimed) line.start - 0.5 else Double.POSITIVE_INFINITY
    val motionUntil: Double = if (!wordTimed) {
        Double.NEGATIVE_INFINITY
    } else {
        line.words.maxOf { word ->
            val length = word.end - word.start
            max(word.start + max(1.0, length), word.start + 2.2 * length)
        }
    }

    // The brightness pair it is drawn with now.
    fun brightness(): Brightness = if (pressed || press > 0.5) TouchedBrightness else brightness(grown.value())
}
