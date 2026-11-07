package app.winters.octo.offline

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import app.winters.octo.subsonic.gateway.SignInPageAnswer
import app.winters.octo.subsonic.gateway.looksLikeHtmlFile
import java.io.File

// Songs, covers, loops and downloads were once kept whatever the server's
// address answered, so a sign-in page in front of it could be kept as one.
// These tell such a page by its first bytes, so it can be let go and
// fetched again, and refuse one before it is kept.

// How many bytes from the start of a kept file are read to tell.
internal const val HTML_SNIFF_BYTES = 64

// Whether a file starts like an HTML page. A file that cannot be read does not.
internal fun isHtmlFile(file: File): Boolean = runCatching {
    file.inputStream().use { input ->
        val head = ByteArray(HTML_SNIFF_BYTES)
        var read = 0
        while (read < head.size) {
            val got = input.read(head, read, head.size - read)
            if (got < 0) break
            read += got
        }
        looksLikeHtmlFile(head.copyOf(read))
    }
}.getOrDefault(false)

// Whether the copy kept under `key` starts like an HTML page.
@OptIn(UnstableApi::class)
internal fun Cache.startsLikeHtml(key: String): Boolean {
    val first = getCachedSpans(key).firstOrNull() ?: return false
    val file = first.file ?: return false
    return first.position == 0L && first.isCached && isHtmlFile(file)
}

// Lets every copy in the store that starts like an HTML page go. Answers
// how many went.
@OptIn(UnstableApi::class)
internal fun Cache.dropHtmlCopies(): Int = keys.toList().count { key ->
    startsLikeHtml(key).also { if (it) removeResource(key) }
}

// Refuses the first bytes of a file being kept when they start like an
// HTML page: a sign-in page's answer in place of the song.
internal fun refuseHtmlStart(first: ByteArray, length: Int) {
    if (looksLikeHtmlFile(first.copyOf(minOf(length, HTML_SNIFF_BYTES)))) throw SignInPageAnswer(null, 200)
}
