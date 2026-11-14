package app.winters.octo.desktop.server

import app.winters.octo.desktop.secrets.SecretStore
import app.winters.octo.desktop.secrets.SecretStoreException
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// A password store like the real ones that a test can make refuse writes or
// deletes and fail reads, account by account, and that writes down every
// write and delete it is asked for. `onChange` hears each one before it is
// done, for the order tests.
class CertStore(override val lasting: Boolean = true) : SecretStore {
    val kept = ConcurrentHashMap<String, String>()
    val writes = CopyOnWriteArrayList<String>()
    val deletes = CopyOnWriteArrayList<String>()

    @Volatile var refuseWrites: (String) -> Boolean = { false }

    @Volatile var refuseDeletes: (String) -> Boolean = { false }

    @Volatile var failReads: (String) -> Boolean = { false }

    @Volatile var onChange: ((String) -> Unit)? = null

    override val label: String = "Test store"

    override fun read(account: String): String? {
        if (failReads(account)) throw SecretStoreException("locked")
        return kept[account]
    }

    override fun write(account: String, secret: String) {
        onChange?.invoke("write $account")
        writes += account
        if (refuseWrites(account)) throw SecretStoreException("full")
        kept[account] = secret
    }

    override fun delete(account: String) {
        onChange?.invoke("delete $account")
        deletes += account
        if (refuseDeletes(account)) throw SecretStoreException("locked")
        kept.remove(account)
    }

    // A server's certificate item, read back.
    fun item(id: String): CertSecret? = kept["certificate@$id"]?.let { Json.decodeFromString(CertSecret.serializer(), it) }

    fun putItem(id: String, item: CertSecret) {
        kept["certificate@$id"] = Json.encodeToString(CertSecret.serializer(), item)
    }
}
