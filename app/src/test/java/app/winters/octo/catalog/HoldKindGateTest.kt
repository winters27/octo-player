package app.winters.octo.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Every hold of the merge lock names its kind, so a whole walk never passes
// as a read: betweenRebuilds and stableRead take the kind with no default,
// and every call in the app passes one (or hands on its own `kind`).
class HoldKindGateTest {
    private val kindArgument = """(HoldKind\.(Read|Page|Walk)|kind)\b"""

    // Each matched at the start of the text it is given.
    private val declarations = listOf(
        Regex("""^fun <T> betweenRebuilds\(kind: HoldKind, """),
        Regex("""^fun <T> stableRead\(\s*merge: CatalogMerge,\s*kind: HoldKind,"""),
    )
    private val calls = listOf(
        Regex("""^betweenRebuilds\(\s*$kindArgument"""),
        Regex("""^stableRead\(\s*[^,()]+(\(\))?\s*,\s*$kindArgument"""),
    )
    private val anyCall = Regex("""\b(betweenRebuilds|stableRead)\s*\(""")

    @Test
    fun everyHoldNamesItsKind() {
        val root = File(System.getProperty("octo.mainSources")!!)
        val missing = ArrayList<String>()
        var named = 0
        var declared = 0
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            // Comments describe calls in words; only code counts.
            val code = file.readLines().joinToString("\n") { it.substringBefore("//") }
            for (match in anyCall.findAll(code)) {
                val at = code.substring(match.range.first)
                val withFun = code.substring(match.range.first - minOf("fun <T> ".length, match.range.first))
                when {
                    declarations.any { it.containsMatchIn(withFun) } -> declared++
                    calls.any { it.containsMatchIn(at) } -> named++
                    else -> missing += "${file.relativeTo(root).invariantSeparatorsPath}: ${at.lineSequence().first()}"
                }
            }
        }
        assertEquals("holds of the merge lock without a kind", emptyList<String>(), missing)
        // betweenRebuilds once, and stableRead's two forms.
        assertEquals(3, declared)
        assertTrue("only $named calls found", named >= 5)
    }
}
