package app.winters.octo.desktop.secrets

import com.sun.jna.LastErrorException
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference

// Windows Credential Manager, through advapi32's Cred* calls. Each secret
// is a generic credential named "Octo:<account>", kept for this user on
// this machine (it does not roam to other PCs).
class WindowsCredentials : SecretStore {
    private val api: CredentialApi = Native.load("Advapi32", CredentialApi::class.java)

    override val label = "Windows Credential Manager"

    override fun read(account: String): String? {
        val found = PointerByReference()
        try {
            api.CredReadW(WString(target(account)), CRED_TYPE_GENERIC, 0, found)
        } catch (e: LastErrorException) {
            if (e.errorCode == ERROR_NOT_FOUND) return null
            throw SecretStoreException("Windows Credential Manager could not read it (error ${e.errorCode})")
        }
        val pointer = found.value ?: return null
        try {
            val credential = Credential(pointer)
            val size = credential.CredentialBlobSize
            val blob = credential.CredentialBlob ?: return ""
            return blob.getByteArray(0, size).decodeToString()
        } finally {
            api.CredFree(pointer)
        }
    }

    override fun write(account: String, secret: String) {
        val bytes = secret.encodeToByteArray()
        val blob = Memory(maxOf(1, bytes.size).toLong()).apply { write(0, bytes, 0, bytes.size) }
        val credential = Credential().apply {
            Type = CRED_TYPE_GENERIC
            TargetName = WString(target(account))
            UserName = WString(account)
            CredentialBlobSize = bytes.size
            CredentialBlob = blob
            Persist = CRED_PERSIST_LOCAL_MACHINE
        }
        try {
            api.CredWriteW(credential, 0)
        } catch (e: LastErrorException) {
            throw SecretStoreException("Windows Credential Manager refused it (error ${e.errorCode})")
        } finally {
            // The copy in memory is overwritten once Windows has it.
            blob.clear()
        }
    }

    override fun delete(account: String) {
        try {
            api.CredDeleteW(WString(target(account)), CRED_TYPE_GENERIC, 0)
        } catch (e: LastErrorException) {
            if (e.errorCode != ERROR_NOT_FOUND) throw SecretStoreException("Windows Credential Manager could not remove it (error ${e.errorCode})")
        }
    }

    private fun target(account: String) = "${SecretStore.SERVICE}:$account"

    // The CREDENTIALW structure, field for field.
    @Suppress("PropertyName")
    @Structure.FieldOrder(
        "Flags", "Type", "TargetName", "Comment", "LastWrittenLow", "LastWrittenHigh", "CredentialBlobSize",
        "CredentialBlob", "Persist", "AttributeCount", "Attributes", "TargetAlias", "UserName",
    )
    class Credential() : Structure() {
        @JvmField var Flags: Int = 0
        @JvmField var Type: Int = 0
        @JvmField var TargetName: WString? = null
        @JvmField var Comment: WString? = null
        @JvmField var LastWrittenLow: Int = 0
        @JvmField var LastWrittenHigh: Int = 0
        @JvmField var CredentialBlobSize: Int = 0
        @JvmField var CredentialBlob: Pointer? = null
        @JvmField var Persist: Int = 0
        @JvmField var AttributeCount: Int = 0
        @JvmField var Attributes: Pointer? = null
        @JvmField var TargetAlias: WString? = null
        @JvmField var UserName: WString? = null

        constructor(pointer: Pointer) : this() {
            useMemory(pointer)
            read()
        }
    }

    @Suppress("FunctionName")
    interface CredentialApi : Library {
        @Throws(LastErrorException::class)
        fun CredReadW(target: WString, type: Int, flags: Int, credential: PointerByReference): Boolean

        @Throws(LastErrorException::class)
        fun CredWriteW(credential: Credential, flags: Int): Boolean

        @Throws(LastErrorException::class)
        fun CredDeleteW(target: WString, type: Int, flags: Int): Boolean

        fun CredFree(buffer: Pointer)
    }

    private companion object {
        const val CRED_TYPE_GENERIC = 1
        const val CRED_PERSIST_LOCAL_MACHINE = 2
        const val ERROR_NOT_FOUND = 1168
    }
}
