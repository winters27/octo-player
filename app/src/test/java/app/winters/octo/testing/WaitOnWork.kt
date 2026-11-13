package app.winters.octo.testing

// Waits until `done`, running `step` between looks. With `progress` it waits
// for as long as that keeps moving and gives up once it has stood still for
// `quietMillis`; without it, after `quietMillis` in all. A busy machine slows
// the work, not the verdict, and work that stops still fails.
fun waitOnWork(what: String, quietMillis: Long, progress: (() -> Any?)?, step: () -> Unit, done: () -> Boolean) {
    var seen = progress?.invoke()
    var end = System.nanoTime() + quietMillis * 1_000_000
    while (!done()) {
        step()
        if (progress != null) {
            val now = progress()
            if (now != seen) {
                seen = now
                end = System.nanoTime() + quietMillis * 1_000_000
            }
        }
        if (System.nanoTime() > end) throw AssertionError("never: $what")
        Thread.sleep(5)
    }
}
