package io.github.saggeldi.gps

import android.os.Handler
import android.os.Looper

class AndroidRetryScheduler : RetryScheduler {

    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(delayMs: Long, action: () -> Unit) {
        handler.postDelayed(action, delayMs)
    }
}
