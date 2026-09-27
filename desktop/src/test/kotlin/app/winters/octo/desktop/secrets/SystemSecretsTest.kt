package app.winters.octo.desktop.secrets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

// Writes, reads and removes one made-up entry in this system's real
// password store. It touches the machine, so it only runs when asked:
// OCTO_OS_SECRETS_TEST=1 ./gradlew :desktop:test
class SystemSecretsTest {
    @Test
    fun theSystemStoreKeepsAndForgetsASecret() {
        assumeTrue(System.getenv("OCTO_OS_SECRETS_TEST") == "1")
        val store = SecretStore.forSystem()
        val account = "octo-test-${System.nanoTime()}@http://store.test/"
        val secret = "pässwörd with spaces & symbols ✓"
        try {
            assertNull(store.read(account))
            store.write(account, secret)
            assertEquals(secret, store.read(account))
            store.write(account, "changed")
            assertEquals("changed", store.read(account))
        } finally {
            store.delete(account)
        }
        assertNull(store.read(account))
        // Removing what is not there is fine.
        store.delete(account)
    }
}
