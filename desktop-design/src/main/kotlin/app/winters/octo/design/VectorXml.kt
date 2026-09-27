package app.winters.octo.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

// One filled path of an icon, as the vector file writes it.
data class IconPath(val data: String, val evenOdd: Boolean = false)

// An icon as its vector file describes it: its size, the square its paths
// are drawn in, and the paths. Kept apart from the ImageVector so an icon
// can be drawn turned or mirrored from the same paths.
data class IconSource(
    val name: String,
    val widthDp: Float,
    val heightDp: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val paths: List<IconPath>,
) {
    // The icon as Compose draws it, white, optionally turned by `rotation`
    // degrees or mirrored left to right around its middle, and moved down
    // by `shiftY` (for paths written above the viewport, as the web's are).
    fun toImageVector(rotation: Float = 0f, mirrored: Boolean = false, shiftY: Float = 0f): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = widthDp.dp,
            defaultHeight = heightDp.dp,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
        builder.addGroup(
            rotate = rotation,
            pivotX = viewportWidth / 2,
            pivotY = viewportHeight / 2,
            scaleX = if (mirrored) -1f else 1f,
            translationY = shiftY,
        )
        paths.forEach { path ->
            builder.addPath(
                pathData = addPathNodes(path.data),
                pathFillType = if (path.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                fill = SolidColor(Color.White),
            )
        }
        builder.clearGroup()
        return builder.build()
    }
}

// Reads an Android vector drawable, such as the phone app's sym_*.xml
// icons. Only what those files use is read: the size, the viewport and each
// path's data and fill rule. A file with groups is refused, since their
// moves would be lost.
fun parseVectorXml(name: String, xml: String): IconSource {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // The files are ours, but a parser that fetches nothing is the only
        // kind worth having.
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isExpandEntityReferences = false
    }
    val root = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
    require(root.tagName == "vector") { "$name is not a vector drawable" }
    require(root.getElementsByTagName("group").length == 0) { "$name has groups, which are not read" }
    fun Element.number(attribute: String): Float {
        val raw = getAttributeNS(ANDROID_NS, attribute)
        return raw.removeSuffix("dp").toFloatOrNull() ?: error("$name has no usable $attribute")
    }
    val nodes = root.getElementsByTagName("path")
    val paths = (0 until nodes.length).map { index ->
        val path = nodes.item(index) as Element
        IconPath(
            data = path.getAttributeNS(ANDROID_NS, "pathData").also { require(it.isNotBlank()) { "$name has an empty path" } },
            evenOdd = path.getAttributeNS(ANDROID_NS, "fillType") == "evenOdd",
        )
    }
    require(paths.isNotEmpty()) { "$name has no paths" }
    return IconSource(
        name = name,
        widthDp = root.number("width"),
        heightDp = root.number("height"),
        viewportWidth = root.number("viewportWidth"),
        viewportHeight = root.number("viewportHeight"),
        paths = paths,
    )
}

// Reads one of the phone app's icons from the resources this module packs.
fun loadIcon(file: String): IconSource {
    val stream = OctoIcons::class.java.getResourceAsStream("/octo-icons/$file.xml")
        ?: error("The icon $file is not packaged")
    return parseVectorXml(file, stream.use { it.readBytes().decodeToString() })
}
