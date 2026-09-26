package app.winters.octo.server

import app.winters.octo.catalog.ArtworkRef
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import coil3.ImageLoader
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import coil3.toUri
import kotlinx.coroutines.flow.first

// Covers are asked for at one of these sizes, so one download serves every
// size up to it.
private val Sizes = listOf(150, 300, 600, 1200)

// Draws a cover from the signed-in server. Its address is signed afresh for
// each download, so the download is cached under what the picture is, never
// under the address.
class ServerArtworkFetcher(
    private val sessions: SessionRepository,
    private val art: ArtworkRef.Server,
    private val options: Options,
    private val imageLoader: ImageLoader,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        // Covers asked for while the app is still opening wait for the sign-in.
        val state = sessions.state.first { it !is SessionState.Loading }
        val session = (state as? SessionState.SignedIn)?.session ?: return null
        val client = session.client
        if (session.sourceId != art.sourceId) return null
        // With no size given, a middling one.
        val wanted = maxOf(options.size.width.pxOrElse { 0 }, options.size.height.pxOrElse { 0 }).takeIf { it > 0 } ?: 600
        val px = Sizes.firstOrNull { it >= wanted } ?: Sizes.last()
        val url = client.coverArtUrl(art.coverId, px).toString().toUri()
        val download = options.copy(diskCacheKey = "server-art:${art.sourceId}|${art.coverId}|$px")
        val (fetcher, _) = imageLoader.components.newFetcher(url, download, imageLoader) ?: return null
        return fetcher.fetch()
    }

    class Factory(private val sessions: SessionRepository) : Fetcher.Factory<ArtworkRef.Server> {
        override fun create(data: ArtworkRef.Server, options: Options, imageLoader: ImageLoader): Fetcher =
            ServerArtworkFetcher(sessions, data, options, imageLoader)
    }
}

// A cover is remembered by its server and id, whatever address fetched it.
class ServerArtworkKeyer : Keyer<ArtworkRef.Server> {
    override fun key(data: ArtworkRef.Server, options: Options) = "server-art:${data.sourceId}|${data.coverId}"
}
