package app.winters.octo.desktop.system

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.W32APIOptions

// octo:// links open Octo. macOS learns this from the app's own details
// and Linux from its desktop entry; on Windows the installed app tells the
// system itself, for this user only, whenever it runs from somewhere new.
// Nothing is written when Octo runs from a build rather than an install,
// or while it runs its self-check.

// The installed app's own program, or null outside an installed app.
fun installedProgram(): String? = System.getProperty("jpackage.app-path")?.takeIf(String::isNotBlank)

// The command Windows runs for a link.
fun linkCommand(program: String): String = "\"$program\" \"%1\""

// Makes octo:// links open this program, unless they already do. True
// once they do. `key` is the scheme's place under the current user.
fun registerLinksOnWindows(program: String, key: String = LINK_KEY): Boolean {
    val command = linkCommand(program)
    if (linkProgramOnWindows(key) == command) return true
    Registry.write(key, null, "URL:Octo")
    Registry.write(key, "URL Protocol", "")
    Registry.write("$key\\DefaultIcon", null, "\"$program\",0")
    Registry.write("$key\\shell\\open\\command", null, command)
    return linkProgramOnWindows(key) == command
}

// The command octo:// links run now, or null when none is set.
fun linkProgramOnWindows(key: String = LINK_KEY): String? = Registry.read("$key\\shell\\open\\command")

// Removes a scheme's registration and everything under it.
internal fun forgetLinksOnWindows(key: String) = Registry.remove(key)

private const val LINK_KEY = "Software\\Classes\\octo"

// The few calls on the current user's part of the Windows registry that
// this needs, with text values only.
private object Registry {
    // The current user's part of the registry, as the system numbers it.
    private const val HKEY_CURRENT_USER = 0x80000001L
    private const val KEY_READ = 0x20019
    private const val KEY_WRITE = 0x20006
    private const val REG_SZ = 1

    private interface Advapi32 : Library {
        fun RegCreateKeyExW(key: Pointer, subKey: String, reserved: Int, cls: String?, options: Int, access: Int, security: Pointer?, result: PointerByReference, disposition: IntByReference?): Int
        fun RegOpenKeyExW(key: Pointer, subKey: String, options: Int, access: Int, result: PointerByReference): Int
        fun RegSetValueExW(key: Pointer, name: String?, reserved: Int, type: Int, data: CharArray, size: Int): Int
        fun RegQueryValueExW(key: Pointer, name: String?, reserved: Pointer?, type: IntByReference?, data: CharArray?, size: IntByReference): Int
        fun RegCloseKey(key: Pointer): Int
        fun RegDeleteTreeW(key: Pointer, subKey: String): Int
    }

    private val api by lazy { Native.load("advapi32", Advapi32::class.java, W32APIOptions.UNICODE_OPTIONS) }
    private val root: Pointer = Pointer.createConstant(HKEY_CURRENT_USER.toInt())

    // A key's default text value, or null when there is none.
    fun read(path: String): String? {
        val opened = PointerByReference()
        if (api.RegOpenKeyExW(root, path, 0, KEY_READ, opened) != 0) return null
        try {
            val size = IntByReference(0)
            if (api.RegQueryValueExW(opened.value, null, null, null, null, size) != 0) return null
            val data = CharArray(size.value / 2 + 1)
            if (api.RegQueryValueExW(opened.value, null, null, null, data, size) != 0) return null
            return String(data).substringBefore('\u0000')
        } finally {
            api.RegCloseKey(opened.value)
        }
    }

    fun remove(path: String) {
        api.RegDeleteTreeW(root, path)
    }

    // Sets a text value, the default one with a null name, making the key.
    fun write(path: String, name: String?, value: String) {
        val made = PointerByReference()
        if (api.RegCreateKeyExW(root, path, 0, null, 0, KEY_WRITE, null, made, null) != 0) return
        try {
            val data = (value + '\u0000').toCharArray()
            api.RegSetValueExW(made.value, name, 0, REG_SZ, data, data.size * 2)
        } finally {
            api.RegCloseKey(made.value)
        }
    }
}
