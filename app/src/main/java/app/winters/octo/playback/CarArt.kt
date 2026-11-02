package app.winters.octo.playback

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// A cover reference longer than this, as written in an address (encoded),
// goes to a car by a short handle, so no item it is on grows past what one
// answer can carry.
const val INLINE_REF_MAX = 200

// How many long references are kept on the phone for their handles; the
// ones least recently handed out go first.
const val CAR_REFS_KEPT = 4_000

// How a cover address is marked: `m` for the "Not in your library" mark, `h`
// for a handle in place of the reference. A car keeps pictures by address, so
// the mark's letter changes whenever the mark is drawn anew.
const val MARK_FLAG = 'm'
const val HANDLE_FLAG = 'h'

// Long cover references by their handles (the SHA-1 of the reference), in
// memory and as one small file each, so a cover still serves after the app
// restarts or its cache is cleared. Safe across threads and processes'
// writers: each file is written beside itself under its own name, then
// moved into place.
class CarArtRefs(private val dir: File, private val kept: Int = CAR_REFS_KEPT) {
    private val known = ConcurrentHashMap<String, String>()

    // New files since the folder was last brought back under `kept`: it is
    // looked over once for every fortieth of `kept` new ones.
    private val sincePrune = java.util.concurrent.atomic.AtomicInteger()

    // The handle a car is given for `ref`, kept for it. A reference already
    // handed out since the app started is on file and touched then, so it
    // costs no reading or writing again.
    fun handleFor(ref: String): String {
        val handle = carSha1(ref)
        if (known.put(handle, ref) == ref) return handle
        dir.mkdirs()
        val file = File(dir, "$handle.ref")
        if (!file.isFile || runCatching { file.readText() }.getOrNull() != ref) {
            writeWhole(file, ref.toByteArray())
            if (sincePrune.incrementAndGet() >= max(1, kept / 40)) {
                sincePrune.set(0)
                prune()
            }
        }
        file.setLastModified(System.currentTimeMillis())
        return handle
    }

    // The reference a handle stands for, or null for one that is not a
    // handle or no longer kept.
    fun refFor(handle: String): String? {
        if (!isHandle(handle)) return null
        known[handle]?.let { return it }
        val file = File(dir, "$handle.ref")
        val ref = runCatching { file.readText() }.getOrNull()?.takeIf { carSha1(it) == handle } ?: return null
        known[handle] = ref
        return ref
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(".ref") } ?: return
        if (files.size <= kept) return
        files.sortedBy { it.lastModified() }.take(files.size - kept).forEach { old ->
            known.remove(old.name.removeSuffix(".ref"))
            old.delete()
        }
    }
}

// A handle: 40 lower-case hex digits.
fun isHandle(text: String): Boolean = text.length == 40 && text.all { it in '0'..'9' || it in 'a'..'f' }

fun carSha1(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

// Writes `bytes` to `file` whole: beside it under a name no other writer
// uses, on the disk before it is moved into place, so a reader never sees
// half of it, nor an empty file after the phone stops suddenly. When
// another writer got there first, theirs is kept.
fun writeWhole(file: File, bytes: ByteArray) {
    val part = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.part")
    try {
        FileOutputStream(part).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!part.renameTo(file) && !file.isFile) part.copyTo(file, overwrite = true)
    } finally {
        part.delete()
    }
}

// The phone's "Not in your library" mark, drawn into a cover's pixels
// (ARGB, row by row): a small disc in the bottom right, a fifth of the
// cover's side, under a dark film with a faint light edge, holding a white
// plus at 68% of the disc. A car shows covers far smaller than it is sent
// them, so the mark is about the phone's smallest (12 dp on a 56 dp row)
// and its plus thick enough to last the shrinking. On a round picture
// (an artist's) it sits along the diagonal, far enough in that the whole
// disc stays inside the circle, as notInLibraryMarkInset places it.
fun drawOutsideMark(pixels: IntArray, width: Int, height: Int, round: Boolean) {
    val side = min(width, height).toFloat()
    val mark = side * MARK_SHARE
    val margin = max(side * MARK_MARGIN, 3f)
    val inset = if (round) {
        val radius = side / 2
        val center = radius - (radius - margin - mark / 2) / sqrt(2f)
        center - mark / 2
    } else {
        margin
    }
    val r = mark / 2
    val cx = width - inset - r
    val cy = height - inset - r
    val edge = max(1f, mark * EDGE_SHARE)
    val arm = mark * PLUS_SPAN / 2
    val half = max(0.6f, mark * PLUS_STROKE / 2)
    val left = max(0, (cx - r - 1).toInt())
    val top = max(0, (cy - r - 1).toInt())
    val right = min(width - 1, (cx + r + 1).toInt())
    val bottom = min(height - 1, (cy + r + 1).toInt())
    for (y in top..bottom) {
        for (x in left..right) {
            val px = x + 0.5f - cx
            val py = y + 0.5f - cy
            val d = hypot(px, py)
            val inside = coverage(r - d)
            if (inside <= 0f) continue
            val i = y * width + x
            var c = blend(pixels[i], 0xFF000000.toInt(), FILM_ALPHA * inside)
            val ring = coverage(d - (r - edge)) * inside
            if (ring > 0f) c = blend(c, 0xFFFFFFFF.toInt(), EDGE_ALPHA * ring)
            // The plus: two bars with round ends.
            val bar = max(capsule(px, py, arm, half), capsule(py, px, arm, half))
            if (bar > 0f) c = blend(c, 0xFFFFFFFF.toInt(), PLUS_ALPHA * bar)
            pixels[i] = c
        }
    }
}

// How much of a pixel lies on the inside of an edge `distance` away
// (positive inside), over one pixel of soft edge.
private fun coverage(distance: Float): Float = (distance + 0.5f).coerceIn(0f, 1f)

// Coverage of a pixel by a horizontal bar from -arm to +arm, `half` thick
// each side, with round ends.
private fun capsule(x: Float, y: Float, arm: Float, half: Float): Float {
    val along = x.coerceIn(-arm, arm)
    return coverage(half - hypot(x - along, y))
}

private fun blend(under: Int, over: Int, alpha: Float): Int {
    val a = alpha.coerceIn(0f, 1f)
    fun channel(shift: Int): Int {
        val u = (under ushr shift) and 0xFF
        val o = (over ushr shift) and 0xFF
        return (u + (o - u) * a + 0.5f).toInt().coerceIn(0, 255)
    }
    val ua = (under ushr 24) and 0xFF
    val outA = (ua + (255 - ua) * a + 0.5f).toInt().coerceIn(0, 255)
    return (outA shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

// The mark's measures, as shares of the cover's side or of the mark. The
// plus has the phone's arms (Phosphor's plus: 176 of 256) at 68% of the
// disc, with a stroke of an eighth of the disc, which keeps over a pixel
// once a 512 px cover shows at 56 px.
internal const val MARK_SHARE = 0.2f
internal const val MARK_MARGIN = 0.04f
private const val EDGE_SHARE = 0.04f
private const val PLUS_SPAN = 0.68f * 176f / 256f
private const val PLUS_STROKE = 0.125f
private const val FILM_ALPHA = 0.42f
private const val EDGE_ALPHA = 0.22f
private const val PLUS_ALPHA = 0.9f
