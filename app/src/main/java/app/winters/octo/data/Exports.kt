package app.winters.octo.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

// How much of an export's file is copied to the picked file at a time.
private const val EXPORT_COPY = 64 * 1024

// Where an export (a backup, a playlist file) is written whole before it
// is copied to the file the listener picked: app storage Android never
// clears on its own. The copy to the picked file, which may be a network
// write, happens only after the export is read, outside every lock. The
// folder is emptied before the first export of each run, so one the app's
// end cut off leaves nothing behind.
@Singleton
class Exports internal constructor(private val files: Drains) {
    @Inject constructor(@ApplicationContext context: Context) : this(Drains(File(context.noBackupFilesDir, "export")))

    @Volatile private var swept = false

    // Runs `block` with the file named `name`, deleted once it ends.
    suspend fun <T> use(name: String, block: suspend (File) -> T): T {
        if (!swept) {
            files.sweep()
            swept = true
        }
        return files.use(name, block)
    }
}

// Copies an export's file to the picked file's stream, EXPORT_COPY bytes at
// a time.
fun copyExport(from: File, to: OutputStream) {
    from.inputStream().use { input: InputStream ->
        val buffer = ByteArray(EXPORT_COPY)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            to.write(buffer, 0, read)
        }
    }
}
