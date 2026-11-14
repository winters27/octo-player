package app.winters.octo.desktop.secrets

import app.winters.octo.desktop.settings.SettingsStore
import java.util.concurrent.ConcurrentHashMap

// The password store for a read-only run (Open without my settings, while
// another program holds the settings file): every write and delete stays
// in memory, by the store's own account name, so nothing this run does
// outlives it, and a read answers from that memory first and from the
// system's store otherwise. It stands for the store for the whole run,
// apart from the memory the accounts keep per sign-in (which a sign-out
// clears), so header values stay across a sign-out in the run as they do
// on disk.
class RunOnlySecrets(private val real: SecretStore) : SecretStore {
    // A value written this run, or a delete.
    private sealed interface Shadow {
        class Kept(val secret: String) : Shadow

        data object Gone : Shadow
    }

    private val shadows = ConcurrentHashMap<String, Shadow>()

    override val label: String get() = real.label

    override val lasting: Boolean get() = real.lasting

    override fun read(account: String): String? = when (val shadow = shadows[account]) {
        is Shadow.Kept -> shadow.secret
        Shadow.Gone -> null
        null -> real.read(account)
    }

    override fun write(account: String, secret: String) {
        shadows[account] = Shadow.Kept(secret)
    }

    override fun delete(account: String) {
        shadows[account] = Shadow.Gone
    }

    // A new password the server already has: kept for the run, and written
    // to the system's store too when that holds one for this account (a read
    // that fails counts as holding one), so the next start never signs in
    // with the old one. Throws SecretStoreException when the store refuses.
    fun writeThrough(account: String, secret: String) {
        shadows[account] = Shadow.Kept(secret)
        val held = try {
            real.read(account) != null
        } catch (e: Exception) {
            true
        }
        if (held) real.write(account, secret)
    }
}

// The store a run uses: the system's own, or, for a read-only run, one
// that keeps this run's changes in memory. Chosen once, at the start; a
// store's readOnly never changes during a run.
fun secretsForRun(settings: SettingsStore, store: SecretStore): SecretStore =
    if (settings.readOnly) RunOnlySecrets(store) else store
