package app.winters.octo.desktop.hotkeys

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Where global shortcuts are claimed from the system. Each is claimed
// under a number, and a press of it comes back with that number.
interface HotkeyBackend : AutoCloseable {
    // Starts listening; false when this system has none.
    fun start(onPress: (Int) -> Unit): Boolean

    // Claims the keys; false when the system refuses them (another app
    // has them).
    fun register(id: Int, combo: KeyCombo, repeats: Boolean): Boolean

    fun unregister(id: Int)
}

// Keeps the claimed shortcuts in step with the listener's: claims what is
// new or changed, lets go of what is gone, and remembers which ones the
// system refused. Lets go of all of them while a new one is being pressed
// in Settings, so those keys reach the window instead.
class HotkeyKeeper(private val backend: HotkeyBackend) : AutoCloseable {
    private val claimed = HashMap<HotkeyAction, KeyCombo>()
    private var started = false
    private var working = false

    // What a press asks for.
    var onAction: (HotkeyAction) -> Unit = {}

    // The ones the system would not give, from the last apply.
    var refused: Set<HotkeyAction> = emptySet()
        private set

    // Claims `keys` when on (and not held off), otherwise lets go of all.
    // Answers the ones refused.
    @Synchronized
    fun apply(on: Boolean, keys: Map<HotkeyAction, KeyCombo?>, holdOff: Boolean = false): Set<HotkeyAction> {
        val wanted = if (on && !holdOff) keys.filterValues { it != null }.mapValues { it.value!! } else emptyMap()
        if (wanted.isNotEmpty() && !started) {
            started = true
            working = backend.start { id -> HotkeyAction.entries.getOrNull(id - 1)?.let { onAction(it) } }
        }
        for ((action, combo) in claimed.toMap()) {
            if (wanted[action] != combo) {
                backend.unregister(idOf(action))
                claimed.remove(action)
            }
        }
        val refusedNow = HashSet<HotkeyAction>()
        for ((action, combo) in wanted) {
            if (claimed[action] == combo) continue
            if (working && backend.register(idOf(action), combo, action.repeats)) claimed[action] = combo else refusedNow += action
        }
        // Held off, nothing is refused; the last answer stands.
        if (!holdOff) refused = refusedNow
        return refused
    }

    // Whether this system lets Octo take global shortcuts at all.
    val available: Boolean get() = !started || working

    private fun idOf(action: HotkeyAction) = action.ordinal + 1

    @Synchronized
    override fun close() {
        claimed.keys.forEach { backend.unregister(idOf(it)) }
        claimed.clear()
        backend.close()
    }
}

// Windows: RegisterHotKey on a thread of Octo's own with its own message
// queue, which Windows posts each press to. Registering and letting go
// happen on that thread too, as Windows requires, handed over as work.
class WindowsHotkeys : HotkeyBackend {
    private val user32: User32? = runCatching { Native.load("user32", User32::class.java) }.getOrNull()
    private val kernel32: Kernel32? = runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull()
    private val work = ConcurrentLinkedQueue<Runnable>()
    @Volatile private var threadId = 0
    private var thread: Thread? = null

    override fun start(onPress: (Int) -> Unit): Boolean {
        val user = user32 ?: return false
        val kernel = kernel32 ?: return false
        val ready = CountDownLatch(1)
        thread = Thread({
            val message = Msg()
            threadId = kernel.GetCurrentThreadId()
            // Asking for a message makes Windows give the thread its queue.
            user.PeekMessageW(message, null, WM_USER, WM_USER, PM_NOREMOVE)
            ready.countDown()
            while (user.GetMessageW(message, null, 0, 0) > 0) {
                when (message.message) {
                    WM_HOTKEY -> runCatching { onPress(Pointer.nativeValue(message.wParam).toInt()) }
                    WM_WORK -> while (true) work.poll()?.run() ?: break
                }
            }
        }, "octo-hotkeys").apply {
            isDaemon = true
            start()
        }
        return ready.await(2, TimeUnit.SECONDS)
    }

    override fun register(id: Int, combo: KeyCombo, repeats: Boolean): Boolean =
        onQueue(false) { user32!!.RegisterHotKey(null, id, combo.windowsModifiers or (if (repeats) 0 else MOD_NOREPEAT), combo.key.windowsCode) }

    override fun unregister(id: Int) {
        onQueue(false) { user32!!.UnregisterHotKey(null, id) }
    }

    // Runs `call` on the shortcut thread and waits a moment for its answer.
    private fun <T> onQueue(otherwise: T, call: () -> T): T {
        val id = threadId
        val user = user32
        if (id == 0 || user == null) return otherwise
        val answer = CompletableFuture<T>()
        work.add { answer.complete(runCatching(call).getOrDefault(otherwise)) }
        if (!user.PostThreadMessageW(id, WM_WORK, null, null)) return otherwise
        return runCatching { answer.get(2, TimeUnit.SECONDS) }.getOrDefault(otherwise)
    }

    override fun close() {
        val id = threadId
        if (id != 0) user32?.PostThreadMessageW(id, WM_QUIT, null, null)
        thread?.join(1_000)
    }

    @Suppress("FunctionName")
    internal interface User32 : Library {
        fun RegisterHotKey(hWnd: Pointer?, id: Int, fsModifiers: Int, vk: Int): Boolean

        fun UnregisterHotKey(hWnd: Pointer?, id: Int): Boolean

        fun GetMessageW(msg: Msg, hWnd: Pointer?, min: Int, max: Int): Int

        fun PeekMessageW(msg: Msg, hWnd: Pointer?, min: Int, max: Int, remove: Int): Boolean

        fun PostThreadMessageW(threadId: Int, msg: Int, wParam: Pointer?, lParam: Pointer?): Boolean
    }

    @Suppress("FunctionName")
    internal interface Kernel32 : Library {
        fun GetCurrentThreadId(): Int
    }

    // Windows' MSG: the window, the message, its two values, when, and where the pointer was.
    @Structure.FieldOrder("hwnd", "message", "wParam", "lParam", "time", "x", "y", "extra")
    class Msg : Structure() {
        @JvmField var hwnd: Pointer? = null
        @JvmField var message: Int = 0
        @JvmField var wParam: Pointer? = null
        @JvmField var lParam: Pointer? = null
        @JvmField var time: Int = 0
        @JvmField var x: Int = 0
        @JvmField var y: Int = 0
        @JvmField var extra: Int = 0
    }

    private companion object {
        const val WM_QUIT = 0x0012
        const val WM_HOTKEY = 0x0312
        const val WM_USER = 0x0400
        // Octo's own message: work is waiting.
        const val WM_WORK = 0x8000 + 0x4F
        const val PM_NOREMOVE = 0
        const val MOD_NOREPEAT = 0x4000
    }
}

// Other systems: no global shortcuts (yet); the settings leave them out.
class NoHotkeys : HotkeyBackend {
    override fun start(onPress: (Int) -> Unit) = false

    override fun register(id: Int, combo: KeyCombo, repeats: Boolean) = false

    override fun unregister(id: Int) {}

    override fun close() {}
}
