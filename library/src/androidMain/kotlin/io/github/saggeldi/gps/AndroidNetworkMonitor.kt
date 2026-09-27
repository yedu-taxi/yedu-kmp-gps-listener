package io.github.saggeldi.gps

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Connectivity via [ConnectivityManager.registerDefaultNetworkCallback].
 *
 * Requires `NET_CAPABILITY_VALIDATED`, i.e. a network that has been proven to reach the
 * internet. The previous implementation reported online on `isConnectedOrConnecting`, so
 * positions were sent — and lost — during the seconds a network spends merely connecting
 * or on a captive portal.
 */
class AndroidNetworkMonitor(
    context: Context
) : NetworkMonitor {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Last value handed to the change callback; null until the first one is delivered. */
    private var lastNotified: Boolean? = null

    /**
     * Queried live, not cached. Callers read this before [start] — `TrackingController`
     * does — and a field that is only filled in by [start] would answer `false` there
     * and never be corrected, because the callback below suppresses no-change events.
     */
    override val isOnline: Boolean get() = currentStatus()

    override fun start(onNetworkChange: (Boolean) -> Unit) {
        if (networkCallback != null) return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                update(networkCapabilities.isUsable(), onNetworkChange)
            }

            override fun onLost(network: Network) = update(false, onNetworkChange)

            override fun onUnavailable() = update(false, onNetworkChange)
        }

        connectivityManager.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    override fun stop() {
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        networkCallback = null
        lastNotified = null
    }

    private fun update(reachable: Boolean, onNetworkChange: (Boolean) -> Unit) {
        if (reachable == lastNotified) return
        lastNotified = reachable
        onNetworkChange(reachable)
    }

    private fun currentStatus(): Boolean =
        connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            ?.isUsable() == true

    private fun NetworkCapabilities.isUsable(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
