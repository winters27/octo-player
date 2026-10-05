package app.winters.octo.catalog

// Where a picture comes from, stored as one string in the catalog.
sealed interface ArtworkRef {
    // Artwork inside a file on the phone. The key groups one album's
    // tracks so they share a single cached picture.
    data class Device(val key: String, val uri: String) : ArtworkRef

    // A cover on a server, by the server's own id for it. The address to
    // fetch it is signed only when it is drawn, so none is ever stored.
    // `online` is for a song, album or artist the server found online
    // rather than one in the library, so its cover is cached apart.
    // `drawn` means Octo paints the cover itself, a station's or a mix's.
    // Its id tells, so a stored cover needs nothing new to say so.
    // `fallbackId` is a second cover for the same thing, drawn when the
    // server answers the first with its stand-in picture: a library song
    // drawn with its album's cover keeps its own cover here.
    data class Server(
        val sourceId: String,
        val coverId: String,
        val online: Boolean = false,
        val fallbackId: String? = null,
    ) : ArtworkRef {
        val drawn: Boolean get() = !online && isDrawnCoverId(coverId)
    }

    fun encode(): String = when (this) {
        is Device -> "device:$key|$uri"
        is Server -> "${if (online) "online" else "server"}:$sourceId|$coverId" +
            fallbackId?.takeIf { it.isNotEmpty() && it != coverId }?.let { "|$it" }.orEmpty()
    }

    companion object {
        fun decode(text: String?): ArtworkRef? {
            if (text == null) return null
            val kind = text.substringBefore(':')
            val rest = text.substringAfter(':')
            return when (kind) {
                "device" -> Device(rest.substringBefore('|'), rest.substringAfter('|'))
                "server", "online" -> {
                    // Source, cover, then the fallback cover when there is one.
                    // Cover ids never hold a "|".
                    val covers = rest.substringAfter('|')
                    Server(
                        sourceId = rest.substringBefore('|'),
                        coverId = covers.substringBefore('|'),
                        online = kind == "online",
                        fallbackId = covers.substringAfter('|', "").takeIf(String::isNotEmpty),
                    )
                }
                else -> null
            }
        }
    }
}

// The cover of something the server found online, as stored. The playlist
// queries in UserDao write the same form in SQL.
fun onlineArtwork(sourceId: String, coverId: String?): String? =
    coverId?.takeIf(String::isNotEmpty)?.let { ArtworkRef.Server(sourceId, it, online = true).encode() }
