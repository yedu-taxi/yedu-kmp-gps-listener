package io.github.saggeldi.gps

interface NetworkMonitor {
    val isOnline: Boolean
    fun start(onNetworkChange: (Boolean) -> Unit)
    fun stop()
}
