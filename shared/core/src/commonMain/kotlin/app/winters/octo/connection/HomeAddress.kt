package app.winters.octo.connection

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

// Which of the server's addresses is in use.
enum class Place { Home, Away }

// Home only when a home address is set, the device is on a local network
// (Wi-Fi or a cable), and the home address answered just now. Anything
// else uses the main address, which works from anywhere.
fun choosePlace(hasHome: Boolean, onLocalNetwork: Boolean, homeAnswered: Boolean): Place =
    if (hasHome && onLocalNetwork && homeAnswered) Place.Home else Place.Away

// A failure to reach the address at all, as opposed to a cancelled call
// or a server that answered badly.
fun isConnectionError(e: IOException): Boolean = when (e) {
    is ConnectException, is NoRouteToHostException, is UnknownHostException, is SocketTimeoutException -> true
    is InterruptedIOException -> e.message == "timeout"
    else -> false
}
