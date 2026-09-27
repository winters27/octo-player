package app.winters.octo.lyrics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

// Follows the phone's network, so a lyrics lookup that failed can be tried
// again once there is a network to try on.
@Singleton
class NetworkWatch @Inject constructor(@ApplicationContext context: Context) {
    // The network the phone uses now, or null while it has none.
    private val network = MutableStateFlow<Network?>(null)

    init {
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    this@NetworkWatch.network.value = network
                }

                override fun onLost(network: Network) {
                    this@NetworkWatch.network.compareAndSet(network, null)
                }
            },
        )
    }

    // Returns once the phone has a network other than the one it has now:
    // one back after none, or a switch to another.
    suspend fun awaitChange() {
        val now = network.value
        network.first { it != null && it != now }
    }
}
