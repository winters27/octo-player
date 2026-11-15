package app.winters.octo.desktop.update

import app.winters.octo.update.sha256Of
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

// An AppImage updates by taking the new AppImage in place of its own file
// (the one APPIMAGE names), so whatever starts it (a menu entry, octo://
// links, a shortcut the listener made) starts the new version. The new
// file is copied in beside the old one, checked against the release's
// signed SHA-256 and moved over it in one step: the file is always a whole
// AppImage, old or new. The running Octo keeps the old one open until it
// quits.

// Puts `download` in place of `target`. Null once done, else why not, in
// words for Settings; `target` is then as it was.
fun replaceAppImage(download: File, target: File, sha256: String): String? {
    val folder = target.absoluteFile.parentFile ?: return "Octo couldn't find the folder its AppImage is in."
    val next = File(folder, ".${target.name}.update")
    try {
        download.inputStream().use { input ->
            java.io.FileOutputStream(next).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        if (sha256Of(next) != sha256) {
            next.delete()
            return "The new AppImage didn't match its release, so this one was kept."
        }
        runnableLike(next, target)
        Files.move(next.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return null
    } catch (e: Exception) {
        if (e !is IOException && e !is SecurityException) throw e
        next.delete()
        return "Octo couldn't put the new AppImage in $folder, so this one was kept."
    }
}

// The new file gets the old one's permissions, and may always run.
private fun runnableLike(next: File, target: File) {
    val posix = runCatching {
        val kept = Files.getPosixFilePermissions(target.toPath()) + PosixFilePermission.OWNER_READ + PosixFilePermission.OWNER_EXECUTE
        Files.setPosixFilePermissions(next.toPath(), kept)
    }
    if (posix.isFailure) next.setExecutable(true)
}

// Whether an update can replace this AppImage: its folder must take a new
// file beside it.
fun canReplaceAppImage(file: File): Boolean = file.isFile && file.absoluteFile.parentFile?.let { it.isDirectory && Files.isWritable(it.toPath()) } == true

// The command that opens the AppImage again once this Octo (`pid`) has
// quit: Octo lets only one copy run, so one started sooner would hand over
// to this one and close with it. It waits on its own, silent, after Octo is
// gone; a copy that ran unpacked starts unpacked again.
fun appImageRelaunch(pid: Long, appImage: File, unpacked: Boolean): List<String> =
    listOf("sh", "-c", RELAUNCH_SCRIPT, "octo-relaunch", pid.toString(), appImage.absolutePath, if (unpacked) "1" else "0")

internal const val RELAUNCH_SCRIPT = """exec >/dev/null 2>&1 </dev/null
while kill -0 "$1" 2>/dev/null; do sleep 0.2; done
unset APPDIR APPIMAGE ARGV0 OWD
if [ "$3" = 1 ]; then export APPIMAGE_EXTRACT_AND_RUN=1; fi
exec "$2"
"""
