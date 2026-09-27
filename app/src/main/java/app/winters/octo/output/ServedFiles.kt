package app.winters.octo.output

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

// A song file on the phone (a library song or a download), read for a
// device from any byte onwards.
class PhoneFileContent(private val context: Context, private val uri: Uri, override val mimeType: String) : ServedContent {
    override fun open(offset: Long): Opened {
        val file = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
        val total = file.length.takeIf { it != AssetFileDescriptor.UNKNOWN_LENGTH } ?: sizeOf(uri)
        val input = file.createInputStream()
        try {
            // For a file, skipping is a seek, not a read.
            var left = offset
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped <= 0) break
                left -= skipped
            }
        } catch (e: IOException) {
            input.close()
            throw e
        }
        return Opened(input, total)
    }

    private fun sizeOf(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()
}

// A server song passed on through the phone, for a server the device
// cannot reach by itself: one behind extra headers, a client certificate
// or a certificate only this phone trusts. The phone fetches it with its
// own connection (and its saved copy when there is one) and hands it on.
@OptIn(UnstableApi::class)
class RelayedContent(
    private val source: () -> DataSource,
    private val uri: Uri,
    override val mimeType: String,
) : ServedContent {
    override fun open(offset: Long): Opened {
        val data = source()
        val length = data.open(DataSpec.Builder().setUri(uri).setPosition(offset).build())
        val total = if (length == C.LENGTH_UNSET.toLong()) null else offset + length
        return Opened(DataSourceStream(data), total)
    }
}

@OptIn(UnstableApi::class)
private class DataSourceStream(private val data: DataSource) : InputStream() {
    private val one = ByteArray(1)

    override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val read = data.read(b, off, len)
        return if (read == C.RESULT_END_OF_INPUT) -1 else read
    }

    override fun close() = data.close()
}
