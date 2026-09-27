package app.winters.octo.output

// A "Range: bytes=..." ask from a device: from `start` to `end` (both
// counted from 0, `end` included), from `start` to the end when `end` is
// null, or the last `suffix` bytes. Devices ask for a part of a song to
// start in the middle of it, which is how seeking works.
data class ByteRange(val start: Long? = null, val end: Long? = null, val suffix: Long? = null)

// Reads a Range header. Null means "send the whole file": no header, a unit
// other than bytes, several ranges at once, or anything malformed, all of
// which a server may answer with the whole file.
fun parseByteRange(header: String?): ByteRange? {
    val value = header?.trim() ?: return null
    if (!value.startsWith("bytes=", ignoreCase = true)) return null
    val spec = value.substring("bytes=".length).trim()
    if (',' in spec) return null
    val dash = spec.indexOf('-')
    if (dash < 0) return null
    val first = spec.substring(0, dash).trim()
    val last = spec.substring(dash + 1).trim()
    if (first.isEmpty()) {
        val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return ByteRange(suffix = suffix)
    }
    val start = first.toLongOrNull()?.takeIf { it >= 0 } ?: return null
    if (last.isEmpty()) return ByteRange(start = start)
    val end = last.toLongOrNull() ?: return null
    if (end < start) return null
    return ByteRange(start = start, end = end)
}

// The bytes to send from a file `total` long, first to last. Null when the
// ask lies wholly past the end, which is answered with 416.
fun ByteRange.within(total: Long): LongRange? {
    if (total <= 0) return null
    suffix?.let { return (total - it).coerceAtLeast(0)..(total - 1) }
    val from = start ?: 0
    if (from >= total) return null
    val to = (end ?: (total - 1)).coerceAtMost(total - 1)
    return from..to
}
