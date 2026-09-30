package app.winters.octo.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.winters.octo.livelists.accountKey

// Small stores keep what belongs to one account under "<name>@<account>",
// the account being a kept server's id. Older versions kept one account's
// values under plain names, with the account written as "user@address".

// The id a kept server has for the account an older version wrote as
// "user@address": the same key the list gives it.
fun accountOfServerKey(serverKey: String): String =
    accountKey(serverKey.substringBeforeLast('@'), serverKey.substringAfterLast('@'))

// Moves the values under these plain names to the account's own names,
// unless the account already has its own.
fun MutablePreferences.moveToAccount(names: Collection<String>, account: String) {
    val plain = asMap().filterKeys { it.name in names }
    for ((key, value) in plain) {
        val name = "${key.name}@$account"
        when (value) {
            is String -> if (this[stringPreferencesKey(name)] == null) this[stringPreferencesKey(name)] = value
            is Set<*> -> if (this[stringSetPreferencesKey(name)] == null) this[stringSetPreferencesKey(name)] = value.filterIsInstance<String>().toSet()
            is Long -> if (this[longPreferencesKey(name)] == null) this[longPreferencesKey(name)] = value
            is Int -> if (this[intPreferencesKey(name)] == null) this[intPreferencesKey(name)] = value
            is Boolean -> if (this[booleanPreferencesKey(name)] == null) this[booleanPreferencesKey(name)] = value
        }
        remove(key)
    }
}

// Forgets every value kept for one account.
fun MutablePreferences.forgetAccount(account: String) {
    asMap().keys.filter { it.name.endsWith("@$account") }.forEach { remove(it) }
}
