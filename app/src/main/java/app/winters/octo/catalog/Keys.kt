package app.winters.octo.catalog

import java.text.Normalizer

private val marks = "\\p{Mn}+".toRegex()
private val articles = listOf("the ", "a ", "an ")

// For matching what someone types: lowercase, accents gone.
fun searchKey(text: String): String =
    marks.replace(Normalizer.normalize(text.trim(), Normalizer.Form.NFD), "").lowercase()

// For ordering lists: like searchKey, with a leading article dropped so
// "The Beatles" sorts under B.
fun sortKey(text: String): String {
    val key = searchKey(text)
    val article = articles.firstOrNull { key.startsWith(it) && key.length > it.length }
    return if (article != null) key.removePrefix(article) else key
}
