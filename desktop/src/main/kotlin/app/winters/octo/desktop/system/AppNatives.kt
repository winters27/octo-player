package app.winters.octo.desktop.system

import java.io.File

// Where the packaged app keeps files beside its jars (Compose sets it).
const val APP_RESOURCES_PROPERTY = "compose.application.resources.dir"

// The installed app keeps its native libraries (the audio engine, the
// system library and JNA's own) as files in its resources folder (see
// shareAppNatives in the build). Pointed there, JNA loads them where they
// are; from the jars it would copy each to a new temporary file at every
// start. Runs before anything touches JNA. A build without them (run from
// source, the tests) finds them on the class path as before.
fun useAppNatives(resources: String? = System.getProperty(APP_RESOURCES_PROPERTY)) {
    val folder = appNativesFolder(resources) ?: return
    System.setProperty("jna.boot.library.path", folder.path)
    val before = System.getProperty("jna.library.path")?.takeIf(String::isNotBlank)
    System.setProperty("jna.library.path", listOfNotNull(folder.path, before).joinToString(File.pathSeparator))
}

// The resources folder, when it holds JNA's own library.
fun appNativesFolder(resources: String?): File? =
    resources?.takeIf(String::isNotBlank)?.let(::File)?.takeIf { File(it, System.mapLibraryName("jnidispatch")).isFile }
