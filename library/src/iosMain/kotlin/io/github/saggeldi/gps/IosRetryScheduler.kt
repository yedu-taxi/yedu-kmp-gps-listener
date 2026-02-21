package io.github.saggeldi.gps

import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_MSEC
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

class IosRetryScheduler : RetryScheduler {

    override fun schedule(delayMs: Long, action: () -> Unit) {
        val deadline = dispatch_time(DISPATCH_TIME_NOW, (delayMs * NSEC_PER_MSEC.toLong()))
        dispatch_after(deadline, dispatch_get_main_queue(), action)
    }
}
