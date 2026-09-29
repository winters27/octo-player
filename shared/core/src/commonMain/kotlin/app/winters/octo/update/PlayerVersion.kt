package app.winters.octo.update

// The two player apps that update themselves from releases on the public
// Octo repository. Each has its own tags there, and never the server's
// (those are dated, like 2026.09.23, or start with a bare "v").
enum class PlayerApp(val tagPrefix: String, val product: String) {
    Desktop("desktop-v", "desktop"),
    Android("android-v", "android"),
}

// A player version as its tag writes it: 1.2.0, or 1.3.0-beta.1 for an
// early version. Ordered the way semantic versioning orders them, so an
// early version comes before the release of the same numbers.
class PlayerVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val early: List<String> = emptyList(),
) : Comparable<PlayerVersion> {
    val isEarly: Boolean get() = early.isNotEmpty()

    override fun compareTo(other: PlayerVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        // A release is newer than any early version of the same numbers.
        if (early.isEmpty() && other.early.isEmpty()) return 0
        if (early.isEmpty()) return 1
        if (other.early.isEmpty()) return -1
        for (i in 0 until minOf(early.size, other.early.size)) {
            val a = early[i]
            val b = other.early[i]
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            val order = when {
                an != null && bn != null -> compareValues(an, bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (order != 0) return order
        }
        return compareValues(early.size, other.early.size)
    }

    override fun equals(other: Any?): Boolean = other is PlayerVersion && compareTo(other) == 0

    override fun hashCode(): Int = listOf(major, minor, patch, early).hashCode()

    override fun toString(): String = "$major.$minor.$patch" + if (early.isEmpty()) "" else "-" + early.joinToString(".")

    companion object {
        private val Pattern = Regex("""(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?""")

        // The version in some text, or null when it is not one: a build from
        // source ("Development build") or a phone test build ("0.2.0.189-debug").
        fun parse(text: String?): PlayerVersion? {
            val match = text?.trim()?.let(Pattern::matchEntire) ?: return null
            val (major, minor, patch, early) = match.destructured
            return PlayerVersion(major.toInt(), minor.toInt(), patch.toInt(), if (early.isEmpty()) emptyList() else early.split('.'))
        }
    }
}

// The version a tag names for this app, or null for any other tag: the
// other app's, the server's dated ones, or anything else.
fun versionOfTag(tag: String, app: PlayerApp): PlayerVersion? =
    if (tag.startsWith(app.tagPrefix)) PlayerVersion.parse(tag.removePrefix(app.tagPrefix)) else null
