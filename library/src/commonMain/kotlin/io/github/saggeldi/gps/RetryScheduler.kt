package io.github.saggeldi.gps

fun interface RetryScheduler {
    fun schedule(delayMs: Long, action: () -> Unit)
}
