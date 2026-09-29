package app.winters.octo.desktop.system

// The classes a start loads, in the order it loads them, made from a real
// start by scripts/startup-classes.py.
const val START_CLASSES = "/startup-classes.txt"

// Reads the start's classes ahead on a thread of its own, loaded but not
// set up (their static parts still run where the app first uses them), so
// the main thread and the window's find most of them ready instead of
// reading, parsing and defining each one in turn. A name this build does
// not have is skipped. Measured on the packaged app: Home composed 1 s
// sooner. Quiet priority, so it only fills the cores the start leaves.
fun preloadStartClasses(loader: ClassLoader = PreloadAnchor::class.java.classLoader) {
    Thread({ loadListed(loader) }, "octo-preload").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
        start()
    }
}

// Loads each listed class it can, and answers how many of how many.
fun loadListed(loader: ClassLoader): Pair<Int, Int> {
    val names = PreloadAnchor::class.java.getResourceAsStream(START_CLASSES)?.bufferedReader()?.use { it.readLines() } ?: return 0 to 0
    var loaded = 0
    var listed = 0
    for (line in names) {
        val name = line.trim()
        if (name.isEmpty()) continue
        listed++
        try {
            Class.forName(name, false, loader)
            loaded++
        } catch (e: ClassNotFoundException) {
            // Gone from this build: nothing to read ahead.
        } catch (e: LinkageError) {
            // Left for the app to meet, and say, where it first uses it.
        }
    }
    return loaded to listed
}

private object PreloadAnchor
