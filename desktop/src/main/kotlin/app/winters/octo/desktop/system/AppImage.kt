package app.winters.octo.desktop.system

import java.io.File

// An AppImage's runtime starts Octo from the image's files (mounted, or
// unpacked into a folder on a system without FUSE) and tells it where they
// are (APPDIR) and which file it came from (APPIMAGE). Both are believed
// only when Octo's own program lies inside APPDIR: another AppImage that
// started Octo, a terminal say, passes its own on, and Octo must never take
// that file for its own.

// The AppImage file this Octo runs from, and whether it runs from an
// unpacked copy (--appimage-extract-and-run) rather than a mounted one.
data class RunningAppImage(val file: File, val unpacked: Boolean)

fun runningAppImage(
    program: String? = installedProgram(),
    env: (String) -> String? = System::getenv,
): RunningAppImage? {
    val folder = appImageFolder(program, env) ?: return null
    val file = env("APPIMAGE")?.takeIf(String::isNotBlank)?.let(::File) ?: return null
    // The runtime mounts the image at /tmp/.mount_<name><random>; anything
    // else is a copy it unpacked.
    return RunningAppImage(file.absoluteFile, unpacked = !folder.name.startsWith(".mount_"))
}

// Whether Octo runs from an AppImage's files that were unpacked by hand
// (--appimage-extract) and started from that folder: no AppImage file goes
// with them, to update or to point links at.
fun inUnpackedAppImageFolder(
    program: String? = installedProgram(),
    env: (String) -> String? = System::getenv,
): Boolean = appImageFolder(program, env) != null && env("APPIMAGE").isNullOrBlank()

// APPDIR, when Octo's program is inside it.
private fun appImageFolder(program: String?, env: (String) -> String?): File? {
    val folder = env("APPDIR")?.takeIf(String::isNotBlank)?.let { File(it).absoluteFile.normalize() } ?: return null
    val own = program?.let { File(it).absoluteFile.normalize() } ?: return null
    return folder.takeIf { generateSequence(own.parentFile) { it.parentFile }.any { it == folder } }
}
