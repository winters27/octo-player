@file:OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)

package app.winters.octo.wayland

import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory
import org.jetbrains.desktop.linux.Application
import kotlin.coroutines.CoroutineContext

// Coil, animated artwork and application jobs must all return to the native
// event loop. A scene-local dispatcher alone would leave Dispatchers.Main on Swing.
class WaylandDispatcherFactory : MainDispatcherFactory {
    override val loadPriority: Int = 10_000
    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher = WaylandDispatcher
    override fun hintOnError(): String = "Start Octo through its Wayland entry point."
}

internal object WaylandDispatcher : MainCoroutineDispatcher() {
    lateinit var application: Application
    var stopped = false
    override val immediate: MainCoroutineDispatcher get() = this
    // Always unwind a native callback before coroutine work. For example,
    // DataTransferAvailable is delivered while KDT holds the offer's mutex;
    // querying/pasting the clipboard inline would re-enter that same mutex.
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (!stopped) application.runOnEventLoopAsync { block.run() }
    }
}
