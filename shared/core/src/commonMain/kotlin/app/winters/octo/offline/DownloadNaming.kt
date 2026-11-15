package app.winters.octo.offline

// The types of lossless audio, which a download at a smaller quality is
// always made smaller from.
private val LosslessTypes = setOf(
    "audio/flac", "audio/x-flac", "audio/alac", "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave",
    "audio/aiff", "audio/x-aiff", "audio/ape", "audio/x-ape", "audio/wavpack", "audio/x-wavpack",
)

fun isLosslessType(mimeType: String?): Boolean = mimeType?.substringBefore(';')?.trim()?.lowercase() in LosslessTypes

// The kilobits a second a download is made smaller to, or null when it
// comes as the server's file: a quality of the file as it is (`capKbps`
// null), or a lossy file already at or under the cap, which a smaller copy
// would only make worse, whatever format smaller copies are made as.
// `bitrateBps` is bits a second, as the catalog keeps it.
fun smallerKbps(mimeType: String?, bitrateBps: Int?, capKbps: Int?): Int? {
    val cap = capKbps ?: return null
    if (!isLosslessType(mimeType) && bitrateBps != null && bitrateBps / 1000 <= cap) return null
    return cap
}

// The mark a smaller download's file name carries inside its last bracket:
// "Artist - Title [a1b2c3d4~s].opus". A title can never reach inside the
// bracket the name ends with, so no title can fake it.
private const val SMALLER_MARK = "~s"

// A name stem ending in "[...]" with the smaller mark put inside that
// bracket. A stem not ending in "]" is given back as it is.
fun madeSmallerStem(stem: String): String =
    if (stem.endsWith("]") && !stem.endsWith("$SMALLER_MARK]")) stem.dropLast(1) + "$SMALLER_MARK]" else stem

private val MadeSmallerName = Regex("\\[[0-9a-f]{8}~s]\\.[A-Za-z0-9]+$")

// Whether a download's file (a path or a name) was made smaller than the
// server's file, by the mark its name carries.
fun isMadeSmallerName(path: String): Boolean = MadeSmallerName.containsMatchIn(path.substringAfterLast('/').substringAfterLast('\\'))
