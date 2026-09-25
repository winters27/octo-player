package app.winters.octo.catalog

import java.text.Normalizer

private val marks = "\\p{Mn}+".toRegex()
private val articles = listOf("the ", "a ", "an ")

// For matching what someone types: lowercase, accents gone.
fun searchKey(text: String): String =
    marks.replace(Normalizer.normalize(text.trim(), Normalizer.Form.NFD), "").lowercase()

// A song's identity apart from its id: album artist, album, disc, track,
// title and length in seconds. Used to find a liked or played song again
// after the phone gives its file a new id.
fun relinkKey(albumArtist: String, album: String, disc: Int?, track: Int?, title: String, durationMs: Long): String =
    searchKey(listOf(albumArtist, album, disc ?: "", track ?: "", title, durationMs / 1000).joinToString("|"))

// For ordering lists: like searchKey, with a leading article dropped so
// "The Beatles" sorts under B.
fun sortKey(text: String): String {
    val key = searchKey(text)
    val article = articles.firstOrNull { key.startsWith(it) && key.length > it.length }
    return if (article != null) key.removePrefix(article) else key
}
