package app.winters.octo.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ChooserTest {
    @Test
    fun homeOnlyWhenSetOnALocalNetworkAndAnswering() {
        assertEquals(Place.Home, choosePlace(hasHome = true, onLocalNetwork = true, homeAnswered = true))
        assertEquals(Place.Away, choosePlace(hasHome = true, onLocalNetwork = true, homeAnswered = false))
        assertEquals(Place.Away, choosePlace(hasHome = true, onLocalNetwork = false, homeAnswered = true))
        assertEquals(Place.Away, choosePlace(hasHome = false, onLocalNetwork = true, homeAnswered = true))
        assertEquals(Place.Away, choosePlace(hasHome = false, onLocalNetwork = false, homeAnswered = false))
    }

    @Test
    fun onlyFailuresToConnectAskForACheck() {
        assertTrue(isConnectionError(ConnectException("refused")))
        assertTrue(isConnectionError(SocketTimeoutException("timeout")))
        assertTrue(isConnectionError(UnknownHostException("music.test")))
        assertTrue(isConnectionError(InterruptedIOException("timeout")))
        assertFalse(isConnectionError(IOException("Canceled")))
        assertFalse(isConnectionError(InterruptedIOException("interrupted")))
    }
}
