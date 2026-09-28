package app.winters.octo.desktop.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemMotionTest {
    @Test
    fun readsWhatTheSystemToolsPrint() {
        assertTrue(macReduceMotion("1\n"))
        assertFalse(macReduceMotion("0\n"))
        // `defaults` prints nothing useful when the key was never set.
        assertFalse(macReduceMotion(null))
        assertTrue(gnomeAnimationsOff("false\n"))
        assertFalse(gnomeAnimationsOff("true\n"))
        assertFalse(gnomeAnimationsOff(null))
    }
}
