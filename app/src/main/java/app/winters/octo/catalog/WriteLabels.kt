package app.winters.octo.catalog

import kotlinx.coroutines.asContextElement
import kotlin.coroutines.CoroutineContext

// Which writer is writing to the library, so each write transaction can be
// told apart when the writes are timed. A writer runs under its label as a
// coroutine context element, which follows the coroutine onto whichever
// thread runs it, Room's transaction thread included; a thread-local set
// by hand stays on the thread that set it.
object WriteLabels {
    val current = ThreadLocal<String?>()

    fun of(label: String): CoroutineContext.Element = current.asContextElement(label)
}
