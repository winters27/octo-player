package app.winters.octo.device

import android.content.Context
import android.net.Uri
import com.kyant.taglib.TagLib
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

// Reads a file's own tags, rather than the phone's summary of them, which
// drops album artists, multi-value tags and original release dates.
class TagReader @Inject constructor(@ApplicationContext private val context: Context) {
    fun read(uri: Uri): FileTags? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { file ->
            // The tag library closes the descriptor it is given, so it gets
            // its own copy and ours is closed here as usual.
            TagLib.getMetadata(file.dup().detachFd(), readPictures = false)
                ?.propertyMap
                ?.let(::parseTags)
        }
    }.getOrNull()
}
