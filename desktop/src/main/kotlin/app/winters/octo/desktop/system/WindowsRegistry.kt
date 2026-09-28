package app.winters.octo.desktop.system

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.W32APIOptions

// The few calls on the current user's part of the Windows registry that
// Octo needs: text values, read, written and removed, and the first byte
// of a binary one. Only ever called on Windows.
internal object WindowsRegistry {
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
        fun RegQueryValueExW(key: Pointer, name: String?, reserved: Pointer?, type: IntByReference?, data: ByteArray?, size: IntByReference): Int
        fun RegDeleteValueW(key: Pointer, name: String?): Int
        fun RegCloseKey(key: Pointer): Int
        fun RegDeleteTreeW(key: Pointer, subKey: String): Int
    }

    private val api by lazy { Native.load("advapi32", Advapi32::class.java, W32APIOptions.UNICODE_OPTIONS) }
    private val root: Pointer = Pointer.createConstant(HKEY_CURRENT_USER.toInt())

    private inline fun <T> opened(path: String, access: Int, use: (Pointer) -> T): T? {
        val key = PointerByReference()
        if (api.RegOpenKeyExW(root, path, 0, access, key) != 0) return null
        return try {
            use(key.value)
        } finally {
            api.RegCloseKey(key.value)
        }
    }

    // A text value, the key's default one with a null name, or null when
    // there is none.
    fun read(path: String, name: String? = null): String? = opened(path, KEY_READ) { key ->
        val size = IntByReference(0)
        if (api.RegQueryValueExW(key, name, null, null, null as CharArray?, size) != 0) return@opened null
        val data = CharArray(size.value / 2 + 1)
        if (api.RegQueryValueExW(key, name, null, null, data, size) != 0) return@opened null
        String(data).substringBefore('\u0000')
    }

    // The first byte of a binary value, or null when there is none.
    fun firstByte(path: String, name: String): Int? = opened(path, KEY_READ) { key ->
        val size = IntByReference(0)
        if (api.RegQueryValueExW(key, name, null, null, null as ByteArray?, size) != 0 || size.value <= 0) return@opened null
        val data = ByteArray(size.value)
        if (api.RegQueryValueExW(key, name, null, null, data, size) != 0) return@opened null
        data[0].toInt() and 0xFF
    }

    // Removes a key and everything under it.
    fun remove(path: String) {
        api.RegDeleteTreeW(root, path)
    }

    // Removes one value. True when it is gone, or was never there.
    fun removeValue(path: String, name: String): Boolean =
        opened(path, KEY_WRITE) { key -> api.RegDeleteValueW(key, name).let { it == 0 || it == ERROR_FILE_NOT_FOUND } } ?: true

    // Sets a text value, the default one with a null name, making the key.
    fun write(path: String, name: String?, value: String): Boolean {
        val made = PointerByReference()
        if (api.RegCreateKeyExW(root, path, 0, null, 0, KEY_WRITE, null, made, null) != 0) return false
        try {
            val data = (value + '\u0000').toCharArray()
            return api.RegSetValueExW(made.value, name, 0, REG_SZ, data, data.size * 2) == 0
        } finally {
            api.RegCloseKey(made.value)
        }
    }

    private const val ERROR_FILE_NOT_FOUND = 2
}
