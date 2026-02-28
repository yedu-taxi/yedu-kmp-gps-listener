package io.github.saggeldi.gps

interface GpsTrackerListener {
    fun onPositionUpdate(position: Position)
    fun onError(error: String)
    fun onStatusChange(status: TrackerStatus)
}

enum class TrackerStatus {
    STARTED,
    STOPPED
}
