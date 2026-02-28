package io.github.saggeldi.gps

import platform.Foundation.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.DISPATCH_TIME_FOREVER

class IosNetworkMonitor : NetworkMonitor {

    private var timer: NSTimer? = null
    private var lastOnline: Boolean = true
    private var callback: ((Boolean) -> Unit)? = null

    override val isOnline: Boolean
        get() = checkConnectivity()

    override fun start(onNetworkChange: (Boolean) -> Unit) {
        callback = onNetworkChange
        lastOnline = isOnline
        timer = NSTimer.scheduledTimerWithTimeInterval(
            interval = 5.0,
            repeats = true
        ) { _ ->
            val online = checkConnectivity()
            if (online != lastOnline) {
                lastOnline = online
                callback?.invoke(online)
            }
        }
    }

    override fun stop() {
        timer?.invalidate()
        timer = null
        callback = null
    }

    private fun checkConnectivity(): Boolean {
        return try {
            val url = NSURL(string = "https://dns.google") ?: return true
            val request = NSMutableURLRequest(
                uRL = url,
                cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
                timeoutInterval = 5.0
            )
            request.setHTTPMethod("HEAD")

            var reachable = false
            val semaphore = dispatch_semaphore_create(0)
            NSURLSession.sharedSession.dataTaskWithRequest(request) { _, response, error ->
                val httpResponse = response as? NSHTTPURLResponse
                reachable = error == null && httpResponse != null && httpResponse.statusCode.toInt() in 200..399
                dispatch_semaphore_signal(semaphore)
            }.resume()
            dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER)
            reachable
        } catch (_: Exception) {
            true
        }
    }
}
