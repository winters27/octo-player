package app.winters.octo.output

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

// The phone's own address on the Wi-Fi (or a cable), where TVs and
// speakers on the same network can reach it. Null when it is on neither,
// such as on mobile data alone.
fun localNetworkAddress(context: Context): Inet4Address? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    // The network in use, when it is the Wi-Fi or a cable.
    connectivity.activeNetwork?.let { network ->
        val caps = connectivity.getNetworkCapabilities(network)
        val local = caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        if (local) {
            connectivity.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.let { return it as Inet4Address }
        }
    }
    // Behind a VPN the network in use is the VPN's, so look at the Wi-Fi
    // itself: a private address on a Wi-Fi or cable interface.
    return runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && isLocalInterface(it.name) }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress } as Inet4Address?
    }.getOrNull()
}

private fun isLocalInterface(name: String): Boolean = name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("swlan")
