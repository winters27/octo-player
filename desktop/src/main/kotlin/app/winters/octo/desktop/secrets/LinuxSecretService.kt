package app.winters.octo.desktop.secrets

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference

// The freedesktop Secret Service (GNOME Keyring, KWallet, KeePassXC),
// through libsecret's simple password calls. Each secret is filed under a
// schema of the app's own with two attributes: the service and the account.
// Loading fails where libsecret is missing, and the app then keeps the
// password only while it runs.
class LinuxSecretService : SecretStore {
    private val secret: SecretApi = Native.load("secret-1", SecretApi::class.java)
    private val glib: GlibApi = Native.load("glib-2.0", GlibApi::class.java)
    private val schema = Schema()

    override val label = "the system keyring (Secret Service)"

    override fun read(account: String): String? {
        val error = PointerByReference()
        val found = secret.secret_password_lookup_sync(schema, null, error, ATTR_SERVICE, SecretStore.SERVICE, ATTR_ACCOUNT, account, null)
        failIfSet(error, "read")
        found ?: return null
        try {
            return found.getString(0, "UTF-8")
        } finally {
            secret.secret_password_free(found)
        }
    }

    override fun write(account: String, secret: String) {
        val error = PointerByReference()
        val label = "Octo: $account"
        this.secret.secret_password_store_sync(
            schema, null, label, secret, null, error,
            ATTR_SERVICE, SecretStore.SERVICE, ATTR_ACCOUNT, account, null,
        )
        failIfSet(error, "save")
    }

    override fun delete(account: String) {
        val error = PointerByReference()
        secret.secret_password_clear_sync(schema, null, error, ATTR_SERVICE, SecretStore.SERVICE, ATTR_ACCOUNT, account, null)
        failIfSet(error, "remove")
    }

    // Turns a GError into an exception, and frees it.
    private fun failIfSet(error: PointerByReference, what: String) {
        val pointer = error.value ?: return
        // A GError is a domain, a code and a message.
        val message = pointer.getPointer(8)?.getString(0, "UTF-8")
        glib.g_error_free(pointer)
        throw SecretStoreException("The keyring could not $what it${message?.let { ": $it" } ?: ""}")
    }

    // A SecretSchema: a name, flags, 32 attribute slots and reserved space.
    @Structure.FieldOrder("name", "flags", "attributes", "reserved", "reserved1", "reserved2", "reserved3", "reserved4", "reserved5", "reserved6", "reserved7")
    class Schema : Structure() {
        @JvmField var name: String = "app.winters.octo.Password"
        @JvmField var flags: Int = 0

        @Suppress("UNCHECKED_CAST")
        @JvmField var attributes: Array<SchemaAttribute> = SchemaAttribute().toArray(32) as Array<SchemaAttribute>
        @JvmField var reserved: Int = 0
        @JvmField var reserved1: Pointer? = null
        @JvmField var reserved2: Pointer? = null
        @JvmField var reserved3: Pointer? = null
        @JvmField var reserved4: Pointer? = null
        @JvmField var reserved5: Pointer? = null
        @JvmField var reserved6: Pointer? = null
        @JvmField var reserved7: Pointer? = null

        init {
            attributes[0].name = ATTR_SERVICE
            attributes[1].name = ATTR_ACCOUNT
            write()
        }
    }

    // One attribute slot: its name and its kind (0 is a string).
    @Structure.FieldOrder("name", "type")
    class SchemaAttribute : Structure() {
        @JvmField var name: String? = null
        @JvmField var type: Int = 0
    }

    @Suppress("FunctionName")
    interface SecretApi : Library {
        fun secret_password_store_sync(
            schema: Schema,
            collection: String?,
            label: String,
            password: String,
            cancellable: Pointer?,
            error: PointerByReference,
            vararg attributes: Any?,
        ): Boolean

        fun secret_password_lookup_sync(schema: Schema, cancellable: Pointer?, error: PointerByReference, vararg attributes: Any?): Pointer?

        fun secret_password_clear_sync(schema: Schema, cancellable: Pointer?, error: PointerByReference, vararg attributes: Any?): Boolean

        fun secret_password_free(password: Pointer)
    }

    @Suppress("FunctionName")
    interface GlibApi : Library {
        fun g_error_free(error: Pointer)
    }

    private companion object {
        const val ATTR_SERVICE = "service"
        const val ATTR_ACCOUNT = "account"
    }
}
