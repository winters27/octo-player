package app.winters.octo.ui.nav

// Goes back one page in a tab. The tab's first page always stays: a second
// Back that lands while a page is still leaving (a quick double tap, or a key
// that reaches the page on its way out) finds nothing more to close, where
// a tab with no page left would end the app.
fun <T> MutableList<T>.backOne() {
    if (size > 1) removeAt(lastIndex)
}
