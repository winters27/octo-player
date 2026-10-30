package app.winters.octo.wayland

import app.winters.octo.desktop.system.FilePicker
import app.winters.octo.desktop.system.FileRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Opt-in acceptance check, analogous to the desktop's octo.checkPlay. Uses
// the exact same portal adapter as playlist/EQ import and export actions.
internal suspend fun checkFileDialogs(files: FilePicker) {
    println("wayland: check-files save")
    val saved = checkNotNull(files.choose(FileRequest("Octo check: Save", save = true, suggested = "Octo native files.txt")))
    withContext(Dispatchers.IO) { saved.writeText("Octo portal UTF-8 ✓\n") }
    println("wayland: check-files open")
    val opened = checkNotNull(files.choose(FileRequest("Octo check: Open", folder = saved.parentFile)))
    check(withContext(Dispatchers.IO) { opened.readText() } == "Octo portal UTF-8 ✓\n")
    println("wayland: check-files PASS")
}
