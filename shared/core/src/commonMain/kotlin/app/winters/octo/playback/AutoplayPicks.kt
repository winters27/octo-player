package app.winters.octo.playback

// The pure part of Autoplay: how many songs it adds and which. The app's
// Autoplay (Media3 and the catalog) calls these.

// How many songs Autoplay adds at a time. When they run out, it adds more.
const val AUTOPLAY_BATCH = 10

// What Autoplay adds: the server's songs like the last one, in its order,
// when it has any not excluded; otherwise songs by the same artist, then in
// the same genre, as given (the caller shuffles each). Songs in `exclude`
// (the queue, and the last 50 played) are never picked, nor is any twice.
fun autoplayPicks(
    similar: List<String>,
    sameArtist: List<String>,
    sameGenre: List<String>,
    exclude: Set<String>,
    limit: Int = AUTOPLAY_BATCH,
): List<String> {
    val fromServer = similar.filter { it !in exclude }.distinct()
    if (fromServer.isNotEmpty()) return fromServer.take(limit)
    return (sameArtist + sameGenre).filter { it !in exclude }.distinct().take(limit)
}
