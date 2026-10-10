package app.winters.octo.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

// A song the scout may not read: its server sends it whole from the start
// every time (a stream made smaller on the way, say), so reading its end
// would mean reading all of it.
class NotSeekable(message: String) : IOException(message)

// Whether a response to a request for its first byte shows the server
// sends parts of the song when asked: it says which part it sent.
internal fun answersRanges(headers: Map<String, List<String>>): Boolean =
    headers.entries.any { (key, values) -> key?.equals("Content-Range", ignoreCase = true) == true && values.orEmpty().any { it.isNotBlank() } }

// Opens server addresses only once the server has shown it answers
// requests for parts of a song: before each open it asks for the first
// byte alone, and gives up with NotSeekable when the whole song comes back
// instead. Phone files and anything that is not a web address open as
// they are. Sits right on the network, under the saved copies, so a saved
// song never asks the server at all.
@OptIn(UnstableApi::class)
class RangeGuardDataSource(private val upstream: DataSource) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme
        if (scheme == "http" || scheme == "https") {
            val probe = dataSpec.buildUpon().setPosition(0).setLength(1).build()
            val ranged = try {
                upstream.open(probe)
                answersRanges(upstream.responseHeaders)
            } finally {
                upstream.close()
            }
            if (!ranged) throw NotSeekable("the server sends this song only whole")
        }
        return upstream.open(dataSpec)
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = RangeGuardDataSource(upstream.createDataSource())
    }
}

// Opens through `upstream` but always names the address it was asked to
// open, never the one it was turned into (a signed server address, say).
// A reader that opens the song again at another place by the address its
// source names then goes through the whole chain again: the pinned
// request, the saved copies and a fresh signature.
@OptIn(UnstableApi::class)
class StableUriDataSource(private val upstream: DataSource) : DataSource {
    private var uri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstream.close()
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = StableUriDataSource(upstream.createDataSource())
    }
}

