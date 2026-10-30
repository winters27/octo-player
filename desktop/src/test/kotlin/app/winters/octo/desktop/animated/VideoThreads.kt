package app.winters.octo.desktop.animated

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException

// A snapshot with identities and fail-closed enumeration. A retiring thread
// may disappear during lookup; an unreadable live thread is not zero threads.
internal object VideoThreads {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    val supported: Boolean get() = windows || File("/proc/self/task").isDirectory

    fun ids(): Set<Long> = if (windows) {
        val kernel = Native.load("kernel32", Kernel32::class.java)
        processThreads(kernel).filter { nameOf(kernel, it) == "octo-video" }.map { it.toUInt().toLong() }.toSet()
    } else {
        checkNotNull(File("/proc/self/task").listFiles()) { "Cannot enumerate /proc/self/task" }.mapNotNull { task ->
            val name = try {
                Files.readString(File(task, "comm").toPath()).trim()
            } catch (_: NoSuchFileException) {
                null // this exact task exited after the directory snapshot
            }
            task.name.toLong().takeIf { name == "octo-video" }
        }.toSet()
    }

    @Suppress("FunctionName")
    private interface Kernel32 : StdCallLibrary {
        fun CreateToolhelp32Snapshot(flags: Int, processId: Int): Pointer?
        fun Thread32First(snapshot: Pointer, entry: ThreadEntry): Boolean
        fun Thread32Next(snapshot: Pointer, entry: ThreadEntry): Boolean
        fun OpenThread(access: Int, inherit: Boolean, threadId: Int): Pointer?
        fun GetThreadDescription(thread: Pointer, description: PointerByReference): Int
        fun LocalFree(memory: Pointer): Pointer?
        fun CloseHandle(handle: Pointer): Boolean
        fun GetCurrentProcessId(): Int
    }

    @Structure.FieldOrder("dwSize", "cntUsage", "th32ThreadID", "th32OwnerProcessID", "tpBasePri", "tpDeltaPri", "dwFlags")
    class ThreadEntry : Structure() {
        @JvmField var dwSize = 0
        @JvmField var cntUsage = 0
        @JvmField var th32ThreadID = 0
        @JvmField var th32OwnerProcessID = 0
        @JvmField var tpBasePri = 0
        @JvmField var tpDeltaPri = 0
        @JvmField var dwFlags = 0
        init { dwSize = size() }
    }

    private fun processThreads(kernel: Kernel32): Set<Int> {
        val self = kernel.GetCurrentProcessId()
        val snapshot = kernel.CreateToolhelp32Snapshot(0x4, 0) // TH32CS_SNAPTHREAD
        check(snapshot != null && Pointer.nativeValue(snapshot) != -1L) {
            "CreateToolhelp32Snapshot failed: Win32=${Native.getLastError()}"
        }
        try {
            val entry = ThreadEntry()
            val ids = mutableSetOf<Int>()
            var more = kernel.Thread32First(snapshot, entry)
            while (more) {
                check(entry.dwSize >= 16) { "Short THREADENTRY32: ${entry.dwSize}" }
                if (entry.th32OwnerProcessID == self) ids += entry.th32ThreadID
                entry.dwSize = entry.size()
                more = kernel.Thread32Next(snapshot, entry)
            }
            val error = Native.getLastError()
            check(error == 18) { "Thread32First/Next failed: Win32=$error" } // ERROR_NO_MORE_FILES
            check(ids.isNotEmpty()) { "Snapshot omitted this process's threads" }
            return ids
        } finally {
            check(kernel.CloseHandle(snapshot)) { "CloseHandle(snapshot): Win32=${Native.getLastError()}" }
        }
    }

    private fun nameOf(kernel: Kernel32, id: Int): String? {
        val thread = kernel.OpenThread(0x0800, false, id) // THREAD_QUERY_LIMITED_INFORMATION
        if (thread == null) {
            val error = Native.getLastError()
            // One identity-only resnapshot distinguishes an exited task from an
            // unreadable live one. Never retry a decoder count until it is zero.
            check(id !in processThreads(kernel)) { "OpenThread($id): Win32=$error" }
            println("Thread $id exited during lookup (OpenThread Win32=$error)")
            return null
        }
        try {
            val text = PointerByReference()
            val result = kernel.GetThreadDescription(thread, text)
            check(result >= 0) { "GetThreadDescription($id): HRESULT=0x${result.toUInt().toString(16)}" }
            val words = checkNotNull(text.value) { "GetThreadDescription($id) returned no string" }
            try {
                return words.getWideString(0)
            } finally {
                check(kernel.LocalFree(words) == null) { "LocalFree($id): Win32=${Native.getLastError()}" }
            }
        } finally {
            check(kernel.CloseHandle(thread)) { "CloseHandle($id): Win32=${Native.getLastError()}" }
        }
    }
}
