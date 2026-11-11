package app.winters.octo.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// Where a server's answer is written whole before anything reads it
// (SubsonicClient's drain): app storage Android never clears on its own,
// unlike the cache, which it may empty between the write and the read.
// Each read names its own file, with no colon in the name; the file goes
// when its read ends, however it ends, or, for a file a later job owns,
// when that job lets it go. The folder is emptied at app start, so a read
// or a job the app's end cut off leaves nothing behind.
@Singleton
class Drains internal constructor(val folder: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "drain"))

    // Runs `block` with the file named `name`, deleted once it ends.
    suspend fun <T> use(name: String, block: suspend (File) -> T): T {
        val file = file(name)
        try {
            return block(file)
        } finally {
            withContext(NonCancellable) { file.delete() }
        }
    }

    // The file named `name`, for a job that outlives the read writing it
    // (a play's start hands its answer to the list's build): its owner
    // deletes it.
    fun file(name: String): File {
        require(':' !in name && '/' !in name && '\\' !in name) { "a drain file's name is a plain name: $name" }
        return File(folder, name)
    }

    // At app start: the files reads cut off by the app's end left.
    fun sweep() {
        folder.listFiles()?.forEach { it.delete() }
    }
}
