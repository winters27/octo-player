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
    data class Server(val sourceId: String, val coverId: String, val online: Boolean = false) : ArtworkRef {
        val drawn: Boolean get() = !online && isDrawnCoverId(coverId)
    }

    fun encode(): String = when (this) {
        is Device -> "device:$key|$uri"
        is Server -> "${if (online) "online" else "server"}:$sourceId|$coverId"
    }

    companion object {
        fun decode(text: String?): ArtworkRef? {
            if (text == null) return null
            val kind = text.substringBefore(':')
            val rest = text.substringAfter(':')
            return when (kind) {
                "device" -> Device(rest.substringBefore('|'), rest.substringAfter('|'))
                "server" -> Server(rest.substringBefore('|'), rest.substringAfter('|'))
                "online" -> Server(rest.substringBefore('|'), rest.substringAfter('|'), online = true)
                else -> null
            }
        }
    }
}

// The cover of something the server found online, as stored. The playlist
// queries in UserDao write the same form in SQL.
fun onlineArtwork(sourceId: String, coverId: String?): String? =
    coverId?.takeIf(String::isNotEmpty)?.let { ArtworkRef.Server(sourceId, it, online = true).encode() }
