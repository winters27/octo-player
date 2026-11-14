package app.winters.octo.desktop.secrets

import app.winters.octo.desktop.server.CertStore
import app.winters.octo.desktop.server.startOn
import app.winters.octo.desktop.settings.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RunOnlySecretsTest {
    @get:Rule val folder = TemporaryFolder()
    private val real = CertStore()
    private val run = RunOnlySecrets(real)

    @Test
    fun aWriteIsReadBackForTheRunAndNeverReachesTheStore() {
        real.kept["a"] = "disk"
        run.write("a", "run")
        assertEquals("run", run.read("a"))
        assertEquals("disk", real.kept["a"])
        assertTrue(real.writes.isEmpty())
    }

    @Test
    fun aDeleteReadsAsNoneOverAnItemTheStoreStillHas() {
        real.kept["a"] = "disk"
        run.delete("a")
        assertNull(run.read("a"))
        assertEquals("disk", real.kept["a"])
        assertTrue(real.deletes.isEmpty())
    }

    @Test
    fun anAccountTheRunNeverTouchedReadsFromTheStore() {
        real.kept["a"] = "disk"
        assertEquals("disk", run.read("a"))
    }

    @Test
    fun writeThroughWritesWhereTheStoreHoldsOne() {
        real.kept["a"] = "old"
        run.writeThrough("a", "new")
        assertEquals("new", real.kept["a"])
        assertEquals("new", run.read("a"))
    }

    @Test
    fun writeThroughWritesWhereTheStoreCannotBeRead() {
        real.failReads = { true }
        run.writeThrough("a", "new")
        assertEquals("new", real.kept["a"])
    }

    @Test
    fun writeThroughLeavesTheStoreAloneWhereItHoldsNone() {
        run.writeThrough("a", "new")
        assertNull(real.kept["a"])
        assertEquals("new", run.read("a"))
    }

    @Test
    fun itsLabelAndLastingAreTheStores() {
        assertEquals(real.label, run.label)
        assertEquals(real.lasting, run.lasting)
        assertFalse(RunOnlySecrets(SessionOnlySecrets()).lasting)
    }

    // Red when a run on its settings file wraps the store, or a read-only
    // run (Open without my settings) does not.
    @Test
    fun aRunWrapsTheStoreOnlyWhenItsSettingsAreReadOnly() {
        assertSame(real, secretsForRun(startOn(File(folder.root, "settings.json")), real))
        assertTrue(secretsForRun(SettingsStore.readOnly(), real) is RunOnlySecrets)
    }
}
