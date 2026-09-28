package app.winters.octo.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.winters.octo.design.AccentButton
import app.winters.octo.design.AccentPeak
import app.winters.octo.design.ButtonGroup
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.ChromeButton
import app.winters.octo.design.DarkGlaze
import app.winters.octo.design.FloatingGlaze
import app.winters.octo.design.GlassField
import app.winters.octo.design.Glaze
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.GlazeInset
import app.winters.octo.design.GlazeLight
import app.winters.octo.design.GlazeSegments
import app.winters.octo.design.GlazeSelected
import app.winters.octo.design.GlazeTabs
import app.winters.octo.design.GlazedIconButton
import app.winters.octo.design.GlowIcon
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconAccent
import app.winters.octo.design.IconAction
import app.winters.octo.design.LcdGlass
import app.winters.octo.design.LineSlider
import app.winters.octo.design.LocalWindowFocused
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.MenuFilm
import app.winters.octo.design.MenuFrost
import app.winters.octo.design.MenuRow
import app.winters.octo.design.MenuSeparator
import app.winters.octo.design.MenuShape
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoInk
import app.winters.octo.design.OctoShapes
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.OctoType
import app.winters.octo.design.Scrubber
import app.winters.octo.design.SelectedRow
import app.winters.octo.design.SliderLook
import app.winters.octo.design.SwitchSize
import app.winters.octo.design.TooltipBubble
import app.winters.octo.design.Txt
import app.winters.octo.design.TypingState
import app.winters.octo.design.accentPose
import app.winters.octo.design.artworkRim
import app.winters.octo.design.chromeShadow
import app.winters.octo.design.glassPanel
import app.winters.octo.design.liftShadow
import app.winters.octo.design.raisedShadow
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.swing.SwingUtilities

// Draws every design primitive in each of its states, on the dark
// background (left) and over a busy picture (right), for reviewing the
// controls side by side. It only runs when asked, like the page shots:
// OCTO_SHOTS=1 ./gradlew :desktop:test --tests "*GalleryShotsTest*"
// and writes desktop/build/shots/gallery-*.png.
class GalleryShotsTest {
    @Test
    fun drawTheGallery() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val out = File("build/shots").apply { mkdirs() }
        shoot(out, "gallery-buttons", 560, 470) { Buttons() }
        shoot(out, "gallery-chrome", 560, 560) { Chrome() }
        shoot(out, "gallery-selection", 560, 430) { Selection() }
        shoot(out, "gallery-sliders", 560, 470) { Sliders() }
        shoot(out, "gallery-inputs", 560, 260) { Inputs() }
        shoot(out, "gallery-menus", 560, 330) { Menus() }
        shoot(out, "gallery-materials", 560, 560) { Materials() }
    }

    // Renders `content` twice, on the dark background and over the busy
    // picture, `width` by `height` dp each, at twice the pixels.
    private fun shoot(out: File, name: String, width: Int, height: Int, content: @Composable ColumnScope.() -> Unit) {
        val scale = 2f
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene((width * 2 * scale).toInt(), (height * scale).toInt(), Density(scale)) {
                CompositionLocalProvider(LocalTyping provides TypingState()) {
                    Row(Modifier.fillMaxSize().background(OctoColors.Background)) {
                        Pane(busy = false, content = content)
                        Pane(busy = true, content = content)
                    }
                }
            }
        }
        var t = 0L
        val end = System.currentTimeMillis() + 1_200
        while (System.currentTimeMillis() < end) {
            SwingUtilities.invokeAndWait { scene.render(t) }
            t += 16_000_000
            Thread.sleep(20)
        }
        lateinit var bytes: ByteArray
        SwingUtilities.invokeAndWait {
            bytes = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
            scene.close()
        }
        File(out, "$name.png").writeBytes(bytes)
    }
}

// What the glass in a pane frosts.
private val LocalBackdrop = staticCompositionLocalOf<HazeState> { error("No backdrop") }

@Composable
private fun RowScope.Pane(busy: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val backdrop = rememberHazeState()
    Box(Modifier.weight(1f).fillMaxHeight()) {
        Box(Modifier.matchParentSize().hazeSource(backdrop)) {
            if (busy) BusyPicture(Modifier.matchParentSize()) else Box(Modifier.matchParentSize().background(OctoColors.Background))
        }
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

// A loud, detailed picture: saturated blobs, bright stripes and dark bars,
// the worst case for glass and the words on it.
@Composable
private fun BusyPicture(modifier: Modifier) {
    Canvas(modifier.clipToBounds()) {
        drawRect(Brush.linearGradient(listOf(Color(0xFFE0457B), Color(0xFFF7B32B), Color(0xFF1FB5C9), Color(0xFF5B2A86))))
        val blobs = listOf(
            Triple(0.2f, 0.25f, Color(0xFFFFF15C)),
            Triple(0.75f, 0.2f, Color(0xFF2E86FF)),
            Triple(0.5f, 0.6f, Color(0xFFFF3D7F)),
            Triple(0.15f, 0.85f, Color(0xFF3DFFB5)),
            Triple(0.85f, 0.8f, Color.White),
        )
        for ((x, y, c) in blobs) {
            val centre = Offset(size.width * x, size.height * y)
            drawCircle(Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), centre, size.minDimension * 0.35f), size.minDimension * 0.35f, centre)
        }
        rotate(-30f) {
            var x = -size.width
            while (x < size.width * 2) {
                drawRect(Color.White.copy(alpha = 0.22f), Offset(x, -size.height), androidx.compose.ui.geometry.Size(10f, size.height * 3))
                drawRect(Color.Black.copy(alpha = 0.25f), Offset(x + 34f, -size.height), androidx.compose.ui.geometry.Size(6f, size.height * 3))
                x += 70f
            }
        }
    }
}

// How a specimen is being touched.
private enum class Pose { Rest, Hover, Pressed, Focused, Dragged }

// Interactions that say the specimen is in `pose`.
@Composable
private fun posed(pose: Pose): MutableInteractionSource {
    val source = remember { MutableInteractionSource() }
    LaunchedEffect(pose) {
        // Interactions are not replayed, so wait a few frames for the
        // specimen to start listening.
        repeat(3) { withFrameNanos { } }
        when (pose) {
            Pose.Rest -> Unit
            Pose.Hover -> source.emit(HoverInteraction.Enter())
            Pose.Pressed -> source.emit(PressInteraction.Press(Offset.Zero))
            Pose.Focused -> source.emit(FocusInteraction.Focus())
            Pose.Dragged -> source.emit(DragInteraction.Start())
        }
    }
    return source
}

@Composable
private fun Title(text: String) {
    Txt(text, OctoType.label, OctoInk.Primary, Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
}

// One specimen with its name under it.
@Composable
private fun Specimen(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(contentAlignment = Alignment.Center) { content() }
        // On a dark chip, so it reads over the busy picture too.
        Txt(
            label,
            OctoType.caption,
            OctoInk.Primary,
            Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun Line(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom, content = content)
}

@Composable
private fun painter(icon: ImageVector) = rememberVectorPainter(icon)

@Composable
private fun ColumnScope.Buttons() {
    Title("Accent button: sizes XS, S, M, L")
    Line {
        Specimen("XS") { AccentButton("Play", {}, size = ButtonSize.ExtraSmall) }
        Specimen("S") { AccentButton("Play", {}, size = ButtonSize.Small) }
        Specimen("M") { AccentButton("Play", {}, size = ButtonSize.Medium) }
        Specimen("L") { AccentButton("Play", {}, size = ButtonSize.Large, icon = painter(OctoIcons.Play)) }
    }
    Title("Accent button (M): states")
    Line {
        Specimen("rest") { AccentButton("Save", {}, size = ButtonSize.Medium) }
        Specimen("hover") { AccentButton("Save", {}, size = ButtonSize.Medium, interactionSource = posed(Pose.Hover)) }
        Specimen("pressed") { AccentButton("Save", {}, size = ButtonSize.Medium, interactionSource = posed(Pose.Pressed)) }
        Specimen("focused") { AccentButton("Save", {}, size = ButtonSize.Medium, interactionSource = posed(Pose.Focused)) }
        Specimen("disabled") { AccentButton("Save", {}, size = ButtonSize.Medium, enabled = false) }
        Specimen("loading") { AccentButton("Save", {}, size = ButtonSize.Medium, loading = true) }
    }
    Title("Fills pick their own words: tonal, plain accent")
    Line {
        Specimen("tonal") { AccentButton("Tonal", {}, size = ButtonSize.Medium, fill = OctoColors.AccentTonal) }
        Specimen("tonal hover") { AccentButton("Tonal", {}, size = ButtonSize.Medium, fill = OctoColors.AccentTonal, interactionSource = posed(Pose.Hover)) }
        Specimen("accent: black words") { AccentButton("Accent", {}, size = ButtonSize.Medium, fill = OctoColors.Accent) }
    }
    Title("Glaze button (M): states")
    Line {
        Specimen("rest") { GlazeButton("Shuffle", {}, size = ButtonSize.Medium) }
        Specimen("hover") { GlazeButton("Shuffle", {}, size = ButtonSize.Medium, interactionSource = posed(Pose.Hover)) }
        Specimen("pressed") { GlazeButton("Shuffle", {}, size = ButtonSize.Medium, interactionSource = posed(Pose.Pressed)) }
        Specimen("focused") { GlazeButton("Shuffle", {}, size = ButtonSize.Medium, interactionSource = posed(Pose.Focused)) }
        Specimen("disabled") { GlazeButton("Shuffle", {}, size = ButtonSize.Medium, enabled = false) }
        Specimen("tinted") { GlazeButton("Shuffle", {}, size = ButtonSize.Medium, tint = Color(0xFFE0457B)) }
    }
    Title("Desktop glaze capsule")
    Line { Specimen("rest") { GlazeCapsule(OctoIcons.Shuffle, "Shuffle", {}) } }
}

@Composable
private fun ColumnScope.Chrome() {
    Title("Chrome button, alone")
    Line {
        Specimen("rest") { ChromeButton(painter(OctoIcons.Queue), "Queue", {}) }
        Specimen("hover") { ChromeButton(painter(OctoIcons.Queue), "Queue", {}, interactionSource = posed(Pose.Hover)) }
        Specimen("pressed") { ChromeButton(painter(OctoIcons.Queue), "Queue", {}, interactionSource = posed(Pose.Pressed)) }
        Specimen("focused") { ChromeButton(painter(OctoIcons.Queue), "Queue", {}, interactionSource = posed(Pose.Focused)) }
        Specimen("active") { ChromeButton(painter(OctoIcons.Queue), "Queue", {}, active = true) }
        Specimen("disabled") { ChromeButton(painter(OctoIcons.Queue), "Queue", {}, enabled = false) }
    }
    Title("Button group: rest, hover, pressed, active, focused")
    Line {
        ButtonGroup {
            ChromeButton(painter(OctoIcons.Previous), "Previous", {})
            ChromeButton(painter(OctoIcons.Play), "Play", {}, interactionSource = posed(Pose.Hover))
            ChromeButton(painter(OctoIcons.Next), "Next", {}, interactionSource = posed(Pose.Pressed))
            ChromeButton(painter(OctoIcons.Shuffle), "Shuffle", {}, active = true)
            ChromeButton(painter(OctoIcons.Repeat), "Repeat", {}, interactionSource = posed(Pose.Focused))
        }
    }
    Title("Glazed icon button over content")
    Line {
        val backdrop = LocalBackdrop.current
        Specimen("rest") { GlazedIconButton(backdrop, OctoIcons.Search, "Search", {}) }
        Specimen("hover") { GlazedIconButton(backdrop, OctoIcons.Search, "Search", {}, interactionSource = posed(Pose.Hover)) }
        Specimen("pressed") { GlazedIconButton(backdrop, OctoIcons.Search, "Search", {}, interactionSource = posed(Pose.Pressed)) }
        Specimen("focused") { GlazedIconButton(backdrop, OctoIcons.Search, "Search", {}, interactionSource = posed(Pose.Focused)) }
    }
    Title("Player toggles: white, glowing only when on")
    Line {
        Specimen("off") { GlowIcon(painter(OctoIcons.Like), Color.White.copy(alpha = 0.45f), lit = false, Modifier.size(26.dp)) }
        Specimen("on") { GlowIcon(painter(OctoIcons.Liked), Color.White, lit = true, Modifier.size(26.dp)) }
        Specimen("desktop off") { IconAction(OctoIcons.Shuffle, "Shuffle", {}) }
        Specimen("desktop on") { IconAction(OctoIcons.Shuffle, "Shuffle", {}, active = true) }
    }
    Title("Icon accents, at their peak")
    Line {
        val shown = listOf(
            IconAccent.Pulse to OctoIcons.Liked,
            IconAccent.SlideForward to OctoIcons.Next,
            IconAccent.SlideBack to OctoIcons.Previous,
            IconAccent.Lift to OctoIcons.Share,
            IconAccent.Drop to OctoIcons.Download,
            IconAccent.Ring to OctoIcons.Radio,
            IconAccent.SpinStep to OctoIcons.History,
            IconAccent.Expand to OctoIcons.Expand,
        )
        for ((accent, icon) in shown) {
            Specimen(accent.name) {
                val progress = if (accent == IconAccent.Ring) 0.08f else if (accent == IconAccent.SpinStep) 0.3f else AccentPeak
                val pose = accentPose(accent, progress)
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Glyph(icon, size = 20.dp, tint = Color.White.copy(alpha = 0.25f))
                    Glyph(
                        icon,
                        size = 20.dp,
                        modifier = Modifier.graphicsLayer {
                            translationX = pose.dx * density
                            translationY = pose.dy * density
                            scaleX = pose.scale
                            scaleY = pose.scale
                            rotationZ = pose.rotation
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Selection() {
    Title("Switch: small, default, large; off and on")
    Line {
        for (size in SwitchSize.entries) {
            Specimen("${size.name} off") { OctoSwitch(false, {}, size = size) }
            Specimen("on") { OctoSwitch(true, {}, size = size) }
        }
    }
    Title("Switch (default): states")
    Line {
        Specimen("hover off") { OctoSwitch(false, {}, interactionSource = posed(Pose.Hover)) }
        Specimen("hover on") { OctoSwitch(true, {}, interactionSource = posed(Pose.Hover)) }
        Specimen("held off") { OctoSwitch(false, {}, interactionSource = posed(Pose.Pressed)) }
        Specimen("held on") { OctoSwitch(true, {}, interactionSource = posed(Pose.Pressed)) }
        Specimen("focused") { OctoSwitch(true, {}, interactionSource = posed(Pose.Focused)) }
        Specimen("disabled") { OctoSwitch(true, {}, enabled = false) }
    }
    Title("Segmented: the phone's (shared width) and the desktop's")
    GlazeTabs(3, 1, {}, Modifier.width(360.dp)) { index, chosen ->
        Txt(listOf("Songs", "Albums", "Artists")[index], OctoType.label, if (chosen) OctoColors.Accent else OctoColors.TextPrimary)
    }
    GlazeSegments(listOf("All", "Albums", "Songs", "Artists"), "Songs", { it }, {})
    Title("Chosen in a glaze vs a selected list row")
    Line {
        Specimen("glaze pill") {
            Box(Modifier.size(160.dp, 40.dp), contentAlignment = Alignment.Center) {
                GlazeSelected(Modifier.matchParentSize())
                Txt("Library", OctoType.label, OctoColors.Accent)
            }
        }
        Specimen("selected row") {
            Box(Modifier.size(240.dp, 48.dp), contentAlignment = Alignment.CenterStart) {
                SelectedRow(Modifier.matchParentSize())
                Txt("Karma Police", OctoType.bodySmall, Color.White, Modifier.padding(horizontal = 14.dp))
            }
        }
    }
}

@Composable
private fun ColumnScope.Sliders() {
    val wide = Modifier.width(500.dp)
    Title("Glow line (song progress, volume): rest, dragged")
    LineSlider({ 0.4f }, {}, wide)
    LineSlider({ 0.4f }, {}, wide, interactionSource = posed(Pose.Dragged))
    Title("Settings slider (jewel): rest, hover, dragged with steps and the value")
    LineSlider({ 0.6f }, {}, wide, look = SliderLook.Jewel)
    LineSlider({ 0.6f }, {}, wide, look = SliderLook.Jewel, interactionSource = posed(Pose.Hover))
    Box(Modifier.padding(top = 18.dp)) {
        LineSlider({ 0.6f }, {}, wide, look = SliderLook.Jewel, steps = 10, valueLabel = { "${(it * 100).toInt()}%" }, interactionSource = posed(Pose.Dragged))
    }
    Title("Scrubber: rest, hover, dragged")
    Box(wide) { Scrubber({ 0.35f }, {}) }
    Box(wide) { Scrubber({ 0.35f }, {}, interactionSource = posed(Pose.Hover)) }
    Box(wide) { Scrubber({ 0.35f }, {}, interactionSource = posed(Pose.Dragged)) }
}

@Composable
private fun ColumnScope.Inputs() {
    Title("Text field: empty, filled, focused")
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Line {
        GlassField("", {}, Modifier.width(160.dp), placeholder = "Search", icon = OctoIcons.Search)
        GlassField("Radiohead", {}, Modifier.width(160.dp), icon = OctoIcons.Search)
        GlassField("Karma", {}, Modifier.width(160.dp), icon = OctoIcons.Search, focusRequester = focus)
    }
    Title("Tooltip")
    Line {
        TooltipBubble("Shuffle")
        TooltipBubble("Add to the queue, after what is playing")
    }
}

@Composable
private fun ColumnScope.Menus() {
    Title("Menu: halo and hairline (left), plain glass (right)")
    val backdrop = LocalBackdrop.current
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for (halo in listOf(true, false)) {
            FloatingGlaze(backdrop, Modifier.width(240.dp), shape = MenuShape, film = MenuFilm, frost = MenuFrost, halo = halo) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    MenuTitle("Karma Police")
                    MenuRow("Play", {}, icon = OctoIcons.Play)
                    MenuRow("Play next", {}, icon = OctoIcons.PlayNext, interactionSource = posed(Pose.Hover))
                    MenuRow("Add to playlist", {}, icon = OctoIcons.AddToPlaylist, more = true)
                    MenuSeparator()
                    MenuRow("Go to artist", {}, icon = OctoIcons.Artist, enabled = false)
                    MenuRow("Remove from playlist", {}, icon = OctoIcons.RemoveFromPlaylist, destructive = true)
                    MenuRow("Delete", {}, icon = OctoIcons.Delete, destructive = true, interactionSource = posed(Pose.Hover))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Materials() {
    val backdrop = LocalBackdrop.current
    Title("Chrome glaze: rest, lifted, window behind, floating, floating with halo")
    Line {
        Specimen("rest") { Glaze(Modifier.size(96.dp, 40.dp), backdrop = backdrop) }
        Specimen("lifted") { Glaze(Modifier.size(96.dp, 40.dp), light = GlazeLight.Lifted, backdrop = backdrop) }
        Specimen("unfocused") {
            CompositionLocalProvider(LocalWindowFocused provides false) { Glaze(Modifier.size(96.dp, 40.dp), backdrop = backdrop) }
        }
        Specimen("floating") { FloatingGlaze(backdrop, Modifier.size(96.dp, 40.dp)) {} }
        Specimen("halo") { FloatingGlaze(backdrop, Modifier.size(96.dp, 40.dp), halo = true) {} }
    }
    Title("Floating dark glass; LCD glass, in front and behind")
    Line {
        Specimen("dark glass") {
            DarkGlaze(backdrop, Modifier.size(160.dp, 44.dp)) { Txt("Now playing", OctoType.label, modifier = Modifier.align(Alignment.Center)) }
        }
        Specimen("LCD") {
            LcdGlass(Modifier.size(160.dp, 56.dp), backdrop = backdrop) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt("Karma Police", OctoType.label)
                    Txt("Radiohead", OctoType.caption, OctoInk.Secondary)
                }
            }
        }
        Specimen("LCD unfocused") {
            LcdGlass(Modifier.size(160.dp, 56.dp), backdrop = backdrop, focused = false) { Txt("Karma Police", OctoType.label) }
        }
    }
    Title("Glass inside glass: insets in a glaze, no second blur")
    Line {
        Glaze(Modifier.height(44.dp), backdrop = backdrop) {
            Row(Modifier.padding(horizontal = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                GlazeInset(OctoInk.Quinary, CircleShape, Modifier.size(84.dp, 34.dp)) { Txt("Inset", OctoType.label) }
                GlazeInset(OctoColors.AccentTonal, CircleShape, Modifier.size(84.dp, 34.dp)) { Txt("Tonal", OctoType.label) }
                CompositionLocalProvider(LocalWindowFocused provides false) {
                    GlazeInset(OctoInk.Quinary, CircleShape, Modifier.size(84.dp, 34.dp)) { Txt("Behind", OctoType.label) }
                }
            }
        }
    }
    Title("Panel; artwork rim and shadows: raised, lift, chrome")
    Line {
        Specimen("panel") { Box(Modifier.size(90.dp, 64.dp).glassPanel(RoundedCornerShape(16.dp))) }
        Specimen("raised") { Art(Modifier.raisedShadow(OctoShapes.ArtM)) }
        Specimen("lift") { Art(Modifier.liftShadow(OctoShapes.ArtM)) }
        Specimen("chrome") { Art(Modifier.chromeShadow(OctoShapes.ArtM)) }
    }
}

// A small square of artwork with its rim.
@Composable
private fun Art(shadow: Modifier) {
    Box(
        shadow
            .size(64.dp)
            .background(Brush.linearGradient(listOf(Color(0xFF3A3F58), Color(0xFF9AA7C7))), OctoShapes.ArtM)
            .artworkRim(OctoShapes.ArtM),
    )
}
