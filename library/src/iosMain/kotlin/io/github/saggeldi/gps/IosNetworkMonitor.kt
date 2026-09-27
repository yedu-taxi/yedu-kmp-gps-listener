@file:OptIn(ExperimentalForeignApi::class)

package io.github.saggeldi.gps

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

/**
 * Connectivity via `NWPathMonitor`, the OS path observer: it publishes changes as they
 * happen, costs no network traffic and no battery.
 *
 * The previous implementation polled `https://dns.google` with a HEAD request every 5
 * seconds and blocked the caller on a semaphore until it answered — so reading
 * [isOnline] from the trip-start path could stall the calling thread for seconds.
 */
class IosNetworkMonitor : NetworkMonitor {

    private val monitor = nw_path_monitor_create()
    private var callback: ((Boolean) -> Unit)? = null

    /**
     * Starts optimistic: the first handler callback lands asynchronously, and a send
     * attempted while actually offline just fails through `onSendFailed`, whereas
     * assuming offline would silently drop the position.
     */
    private var online: Boolean = true

    override val isOnline: Boolean get() = online

    init {
        nw_path_monitor_set_update_handler(monitor) { path ->
            val reachable = path != null && nw_path_get_status(path) == nw_path_status_satisfied
            if (reachable != online) {
                online = reachable
                callback?.invoke(reachable)
            }
        }
        nw_path_monitor_set_queue(monitor, dispatch_queue_create(QUEUE_LABEL, null))
        nw_path_monitor_start(monitor)
    }

    override fun start(onNetworkChange: (Boolean) -> Unit) {
        callback = onNetworkChange
    }

    override fun stop() {
        // ponytail: the monitor keeps observing (it is idle and free) so start() can be
        // called again — nw_path_monitor_cancel is one-way. Cancel it here if a monitor
        // instance ever needs to be fully torn down.
        callback = null
    }

    private companion object {
        const val QUEUE_LABEL = "io.github.saggeldi.gps.network"
    }
}
