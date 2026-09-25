package app.winters.octo.catalog

// Where a picture comes from, stored as one string in the catalog.
sealed interface ArtworkRef {
    // Artwork inside a file on the phone. The key groups one album's
    // tracks so they share a single cached picture.
    data class Device(val key: String, val uri: String) : ArtworkRef

    // A cover on a server, by the server's own id for it. The address to
    // fetch it is signed only when it is drawn, so none is ever stored.
    data class Server(val sourceId: String, val coverId: String) : ArtworkRef

    fun encode(): String = when (this) {
        is Device -> "device:$key|$uri"
        is Server -> "server:$sourceId|$coverId"
    }

    companion object {
        fun decode(text: String?): ArtworkRef? {
            if (text == null) return null
            val kind = text.substringBefore(':')
            val rest = text.substringAfter(':')
            return when (kind) {
                "device" -> Device(rest.substringBefore('|'), rest.substringAfter('|'))
                "server" -> Server(rest.substringBefore('|'), rest.substringAfter('|'))
                else -> null
            }
        }
    }
}
