package app.melogold.android.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the device has a network with internet access, for "No network" hints. It says nothing
 * about YouTube itself being reachable: requests still decide that.
 */
class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService<ConnectivityManager>()
    private val mutableOnline = MutableStateFlow(currentlyOnline())
    val isOnline: StateFlow<Boolean> = mutableOnline.asStateFlow()

    init {
        connectivity?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    mutableOnline.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }

                override fun onLost(network: Network) {
                    mutableOnline.value = false
                }
            }
        )
    }

    private fun currentlyOnline(): Boolean {
        val manager = connectivity ?: return true
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
