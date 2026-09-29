package app.winters.octo.subsonic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CredentialsTest {
    // The worked example from the Subsonic API documentation.
    @Test
    fun tokenMatchesTheSpecExample() {
        assertEquals("26719a1196d2a940705a59634eb18eab", Credentials("u", "sesame").sign("c19b2d"))
    }

    @Test
    fun hexIsTwoLowerCaseDigitsForEveryByte() {
        val every = ByteArray(256) { it.toByte() }
        assertEquals(every.joinToString("") { "%02x".format(it) }, every.toHex())
        assertEquals("", ByteArray(0).toHex())
        assertEquals(16, newSalt().length)
    }

    @Test
    fun toStringHidesThePassword() {
        assertFalse("sesame" in Credentials("u", "sesame").toString())
    }
}
