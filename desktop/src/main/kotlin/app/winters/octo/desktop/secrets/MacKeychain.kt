package app.winters.octo.desktop.secrets

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

// The macOS Keychain, through the Security framework's generic password
// calls. Each secret is a generic password with the service "Octo" and the
// account name, in the user's login keychain.
class MacKeychain : SecretStore {
    private val security: SecurityApi = Native.load("Security", SecurityApi::class.java)
    private val foundation: FoundationApi = Native.load("CoreFoundation", FoundationApi::class.java)

    override val label = "macOS Keychain"

    override fun read(account: String): String? {
        val service = SecretStore.SERVICE.encodeToByteArray()
        val name = account.encodeToByteArray()
        val length = IntByReference()
        val data = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(null, service.size, service, name.size, name, length, data, null)
        if (status == ERR_NOT_FOUND) return null
        if (status != 0) throw SecretStoreException("The Keychain could not read it (status $status)")
        val pointer = data.value ?: return ""
        try {
            return pointer.getByteArray(0, length.value).decodeToString()
        } finally {
            security.SecKeychainItemFreeContent(null, pointer)
        }
    }

    override fun write(account: String, secret: String) {
        val service = SecretStore.SERVICE.encodeToByteArray()
        val name = account.encodeToByteArray()
        val bytes = secret.encodeToByteArray()
        val item = PointerByReference()
        val found = security.SecKeychainFindGenericPassword(null, service.size, service, name.size, name, null, null, item)
        val status = if (found == 0 && item.value != null) {
            try {
                security.SecKeychainItemModifyContent(item.value, null, bytes.size, bytes)
            } finally {
                foundation.CFRelease(item.value)
            }
        } else {
            security.SecKeychainAddGenericPassword(null, service.size, service, name.size, name, bytes.size, bytes, null)
        }
        if (status != 0) throw SecretStoreException("The Keychain refused it (status $status)")
    }

    override fun delete(account: String) {
        val service = SecretStore.SERVICE.encodeToByteArray()
        val name = account.encodeToByteArray()
        val item = PointerByReference()
        val found = security.SecKeychainFindGenericPassword(null, service.size, service, name.size, name, null, null, item)
        if (found == ERR_NOT_FOUND || item.value == null) return
        try {
            val status = security.SecKeychainItemDelete(item.value)
            if (status != 0) throw SecretStoreException("The Keychain could not remove it (status $status)")
        } finally {
            foundation.CFRelease(item.value)
        }
    }

    @Suppress("FunctionName")
    interface SecurityApi : Library {
        fun SecKeychainAddGenericPassword(
            keychain: Pointer?,
            serviceNameLength: Int,
            serviceName: ByteArray,
            accountNameLength: Int,
            accountName: ByteArray,
            passwordLength: Int,
            passwordData: ByteArray,
            itemRef: PointerByReference?,
        ): Int

        fun SecKeychainFindGenericPassword(
            keychainOrArray: Pointer?,
            serviceNameLength: Int,
            serviceName: ByteArray,
            accountNameLength: Int,
            accountName: ByteArray,
            passwordLength: IntByReference?,
            passwordData: PointerByReference?,
            itemRef: PointerByReference?,
        ): Int

        fun SecKeychainItemModifyContent(itemRef: Pointer, attrList: Pointer?, length: Int, data: ByteArray): Int

        fun SecKeychainItemDelete(itemRef: Pointer): Int

        fun SecKeychainItemFreeContent(attrList: Pointer?, data: Pointer?): Int
    }

    @Suppress("FunctionName")
    interface FoundationApi : Library {
        fun CFRelease(ref: Pointer)
    }

    private companion object {
        // errSecItemNotFound.
        const val ERR_NOT_FOUND = -25300
    }
}
