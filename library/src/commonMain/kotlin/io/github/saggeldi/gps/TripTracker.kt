package io.github.saggeldi.gps

/**
 * Tracks distance and time for the current trip and per-status segments.
 * Call [updatePosition] each time a new GPS position is received.
 * Call [startTrip] / [updateStatus] / [endTrip] to manage trip lifecycle.
 */
class TripTracker {

    private var _tripId: String? = null
    val tripId: String? get() = _tripId

    private var _status: String? = null
    val status: String? get() = _status

    // Total trip tracking
    private var tripStartTime: Long = 0L
    private var tripDistanceMeters: Double = 0.0
    private var lastLat: Double = 0.0
    private var lastLon: Double = 0.0
    private var hasLastPosition: Boolean = false

    // Per-status tracking
    private var statusStartTime: Long = 0L
    private var statusDistanceMeters: Double = 0.0
    private var statusLastLat: Double = 0.0
    private var statusLastLon: Double = 0.0
    private var hasStatusLastPosition: Boolean = false

    // Listener
    private var listener: TripTrackerListener? = null

    fun setListener(listener: TripTrackerListener?) {
        this.listener = listener
    }

    fun startTrip(tripId: String, status: String) {
        _tripId = tripId
        _status = status
        tripStartTime = currentTimeMillis()
        tripDistanceMeters = 0.0
        hasLastPosition = false
        statusStartTime = tripStartTime
        statusDistanceMeters = 0.0
        hasStatusLastPosition = false
    }

    fun updateStatus(newStatus: String) {
        _status = newStatus
        statusStartTime = currentTimeMillis()
        statusDistanceMeters = 0.0
        hasStatusLastPosition = false
    }

    fun endTrip() {
        val stats = getTripStats()
        _tripId = null
        _status = null
        tripDistanceMeters = 0.0
        hasLastPosition = false
        statusDistanceMeters = 0.0
        hasStatusLastPosition = false
        listener?.onTripEnded(stats)
    }

    fun updatePosition(position: Position) {
        if (_tripId == null) return

        val now = position.time
        val lat = position.latitude
        val lon = position.longitude

        // Update total trip distance
        if (hasLastPosition) {
            val dist = DistanceCalculator.distance(lastLat, lastLon, lat, lon)
            tripDistanceMeters += dist
        }
        lastLat = lat
        lastLon = lon
        hasLastPosition = true

        // Update per-status distance
        if (hasStatusLastPosition) {
            val dist = DistanceCalculator.distance(statusLastLat, statusLastLon, lat, lon)
            statusDistanceMeters += dist
        }
        statusLastLat = lat
        statusLastLon = lon
        hasStatusLastPosition = true

        listener?.onTripStatsUpdated(getTripStats())
    }

    fun getTripStats(): TripStats {
        val now = currentTimeMillis()
        return TripStats(
            tripId = _tripId,
            status = _status,
            totalDistanceKm = tripDistanceMeters / 1000.0,
            totalTimeSeconds = if (tripStartTime > 0) (now - tripStartTime) / 1000L else 0L,
            statusDistanceKm = statusDistanceMeters / 1000.0,
            statusTimeSeconds = if (statusStartTime > 0) (now - statusStartTime) / 1000L else 0L
        )
    }

    val isActive: Boolean get() = _tripId != null
}

data class TripStats(
    val tripId: String?,
    val status: String?,
    val totalDistanceKm: Double,
    val totalTimeSeconds: Long,
    val statusDistanceKm: Double,
    val statusTimeSeconds: Long
)

interface TripTrackerListener {
    fun onTripStatsUpdated(stats: TripStats)
    fun onTripEnded(stats: TripStats)
}

internal expect fun currentTimeMillis(): Long
