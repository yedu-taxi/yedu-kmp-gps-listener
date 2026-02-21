package io.github.saggeldi.gps

interface PositionSender {
    fun sendPosition(request: String, onComplete: (Boolean) -> Unit)
}
