package io.github.saggeldi.gps

interface TrackingControllerListener {
    fun onPositionUpdate(position: Position) {}
    fun onPositionSent(position: Position) {}
    fun onSendFailed(position: Position) {}
    fun onError(error: String) {}
    fun onStatusChange(status: TrackerStatus) {}
}
