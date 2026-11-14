package app.winters.octo.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Fetching ahead, stopped: a stop and the start of a fetch never cross.
class FetchGateTest {
    private class Writer(val name: String)

    @Test
    fun aStopBeforeAFetchStartsMeansItNeverStarts() {
        val cancelled = mutableListOf<String>()
        val gate = FetchGate<Writer> { cancelled += it.name }
        var active = true
        gate.stop { active = false }
        // The fetch made its writer before the stop and publishes it after:
        // it sees the stop and does not run.
        assertFalse(gate.start(Writer("late")) { active })
        assertEquals(emptyList<String>(), cancelled)
    }

    @Test
    fun aStopAfterAFetchStartsCancelsIt() {
        val cancelled = mutableListOf<String>()
        val gate = FetchGate<Writer> { cancelled += it.name }
        var active = true
        assertTrue(gate.start(Writer("one")) { active })
        gate.stop { active = false }
        assertEquals(listOf("one"), cancelled)
        // A second stop has nothing left to cancel.
        gate.stop {}
        assertEquals(listOf("one"), cancelled)
    }

    @Test
    fun anOldFetchEndingLeavesTheNewOneStoppable() {
        val cancelled = mutableListOf<String>()
        val gate = FetchGate<Writer> { cancelled += it.name }
        val old = Writer("old")
        val new = Writer("new")
        assertTrue(gate.start(old) { true })
        assertTrue(gate.start(new) { true })
        gate.done(old)
        gate.stop {}
        assertEquals(listOf("new"), cancelled)
    }
}
