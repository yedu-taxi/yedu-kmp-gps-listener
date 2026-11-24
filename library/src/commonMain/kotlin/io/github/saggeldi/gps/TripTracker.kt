package io.github.saggeldi.gps

/**
 * Tracks distance and time for the current trip and per-status segments.
 * Call [updatePosition] each time a new GPS position is received.
 * Call [startTrip] / [updateStatus] / [endTrip] to manage trip lifecycle.
 *
 * @param timeProvider injectable time source for testability; defaults to [currentTimeMillis].
 */
class TripTracker(private val timeProvider: () -> Long = ::currentTimeMillis) {

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

    // Per-status tracking (current/live status)
    private var statusStartTime: Long = 0L
    private var statusDistanceMeters: Double = 0.0
    private var statusLastLat: Double = 0.0
    private var statusLastLon: Double = 0.0
    private var hasStatusLastPosition: Boolean = false

    // Per-status history (finalized statuses)
    private val statusHistoryMap: MutableMap<String, StatusAccumulator> = mutableMapOf()

    // Pause detection state
    private var lastSignificantMoveLat: Double = 0.0
    private var lastSignificantMoveLon: Double = 0.0
    private var lastSignificantMoveTime: Long = 0L
    private var hasAnchor: Boolean = false
    private var _isPaused: Boolean = false
    private var pauseStartTime: Long = 0L
    private var totalStoppingMillis: Long = 0L

    // Pause detection config
    var pauseDistanceThresholdMeters: Double = 2.0
    var pauseTimeThresholdSeconds: Long = 15L

    // Distance filter. The billed distance is built from every raw fix (about one a second on a
    // trip), so GPS noise around a standing car used to add up: a parked phone "drove" several km
    // in a few minutes and the rider paid for it. A step only counts once the car is clearly away
    // from the last counted point, poor fixes are skipped and impossible jumps are dropped.
    /** Fixes with a reported accuracy worse than this are not used for distance. */
    var maxAccuracyMeters: Double = 50.0
    /** Smallest step that counts, so noise around a standing car never adds up. */
    var minStepMeters: Double = 10.0
    /**
     * The step also has to clear both fixes' error circles (anchor accuracy + this fix's), up to
     * this much: two fixes 8 m accurate can sit 16 m apart around a car that never moved.
     */
    var maxAccuracyStepMeters: Double = 50.0
    /** A jump faster than this (about 250 km/h) is a bad fix, not driving. */
    var maxSpeedMetersPerSecond: Double = 70.0
    private var lastTime: Long = 0L
    private var lastAccuracy: Double = 0.0
    private var statusLastTime: Long = 0L
    private var statusLastAccuracy: Double = 0.0

    // Listener
    private var listener: TripTrackerListener? = null

    fun setListener(listener: TripTrackerListener?) {
        this.listener = listener
    }

    fun startTrip(tripId: String, status: String) {
        val now = timeProvider()
        _tripId = tripId
        _status = status
        tripStartTime = now
        tripDistanceMeters = 0.0
        hasLastPosition = false
        statusStartTime = now
        statusDistanceMeters = 0.0
        hasStatusLastPosition = false
        statusHistoryMap.clear()
        // Reset pause state
        hasAnchor = false
        _isPaused = false
        pauseStartTime = 0L
        totalStoppingMillis = 0L
    }

    fun updateStatus(newStatus: String) {
        val now = timeProvider()
        // Finalize current status into history
        val currentStatus = _status
        if (currentStatus != null && _tripId != null) {
            val elapsed = now - statusStartTime
            val acc = statusHistoryMap.getOrPut(currentStatus) { StatusAccumulator() }
            acc.distanceMeters += statusDistanceMeters
            acc.timeMillis += elapsed
        }
        _status = newStatus
        statusStartTime = now
        statusDistanceMeters = 0.0
        hasStatusLastPosition = false
    }

    fun endTrip() {
        val now = timeProvider()
        // Finalize current status
        val currentStatus = _status
        if (currentStatus != null) {
            val elapsed = now - statusStartTime
            val acc = statusHistoryMap.getOrPut(currentStatus) { StatusAccumulator() }
            acc.distanceMeters += statusDistanceMeters
            acc.timeMillis += elapsed
        }
        // Reset live counters so buildTripStats doesn't double-count
        statusDistanceMeters = 0.0
        statusStartTime = now
        // Finalize ongoing pause
        if (_isPaused) {
            totalStoppingMillis += now - pauseStartTime
            _isPaused = false
        }
        val stats = buildTripStats(now)
        _tripId = null
        _status = null
        tripDistanceMeters = 0.0
        hasLastPosition = false
        statusDistanceMeters = 0.0
        hasStatusLastPosition = false
        statusHistoryMap.clear()
        hasAnchor = false
        _isPaused = false
        pauseStartTime = 0L
        totalStoppingMillis = 0L
        listener?.onTripEnded(stats)
    }

    fun updatePosition(position: Position) {
        if (_tripId == null) return

        val lat = position.latitude
        val lon = position.longitude
        val time = position.time
        val accuracy = position.accuracy

        // 0 = accuracy not reported (tests, some providers): trust the fix.
        if (accuracy <= 0.0 || accuracy <= maxAccuracyMeters) {
            val fixError = maxOf(accuracy, 0.0)
            fun minStep(anchorError: Double) =
                maxOf(minStepMeters, minOf(anchorError + fixError, maxAccuracyStepMeters))

            // Total trip distance: measured from the last counted point, not the last fix.
            if (!hasLastPosition) {
                lastLat = lat
                lastLon = lon
                lastTime = time
                lastAccuracy = fixError
                hasLastPosition = true
            } else {
                // null = still within noise; STEP_DROPPED = impossible jump (keep the anchor).
                val step = countedStep(lastLat, lastLon, lastTime, lat, lon, time, minStep(lastAccuracy))
                if (step != null && step != STEP_DROPPED) {
                    tripDistanceMeters += step
                    lastLat = lat
                    lastLon = lon
                    lastTime = time
                    lastAccuracy = fixError
                }
            }

            // Per-status distance, same rule from its own anchor.
            if (!hasStatusLastPosition) {
                statusLastLat = lat
                statusLastLon = lon
                statusLastTime = time
                statusLastAccuracy = fixError
                hasStatusLastPosition = true
            } else {
                val step =
                    countedStep(statusLastLat, statusLastLon, statusLastTime, lat, lon, time, minStep(statusLastAccuracy))
                if (step != null && step != STEP_DROPPED) {
                    statusDistanceMeters += step
                    statusLastLat = lat
                    statusLastLon = lon
                    statusLastTime = time
                    statusLastAccuracy = fixError
                }
            }
        }

        // Pause detection
        detectPauseOrResume(lat, lon, time)

        listener?.onTripStatsUpdated(getTripStats())
    }

    fun getTripStats(): TripStats {
        val now = timeProvider()
        return buildTripStats(now)
    }

    val isActive: Boolean get() = _tripId != null

    private fun buildTripStats(now: Long): TripStats {
        // Build status history: finalized + live current status
        val history = mutableMapOf<String, StatusStats>()
        for ((name, acc) in statusHistoryMap) {
            history[name] = StatusStats(
                status = name,
                distanceKm = acc.distanceMeters / 1000.0,
                distanceMeters = acc.distanceMeters,
                timeSeconds = acc.timeMillis / 1000L,
                timeMinutes = (acc.timeMillis / 1000.0) / 60.0
            )
        }
        // Merge live current status
        val currentStatus = _status
        if (currentStatus != null && _tripId != null) {
            val liveElapsed = now - statusStartTime
            val existing = statusHistoryMap[currentStatus]
            val totalMeters = (existing?.distanceMeters ?: 0.0) + statusDistanceMeters
            val totalMillis = (existing?.timeMillis ?: 0L) + liveElapsed
            history[currentStatus] = StatusStats(
                status = currentStatus,
                distanceKm = totalMeters / 1000.0,
                distanceMeters = totalMeters,
                timeSeconds = totalMillis / 1000L,
                timeMinutes = (totalMillis / 1000.0) / 60.0
            )
        }

        // Total stopping seconds including live pause
        var stoppingMillis = totalStoppingMillis
        if (_isPaused && _tripId != null) {
            stoppingMillis += now - pauseStartTime
        }

        val totalTimeSeconds = if (_tripId != null) (now - tripStartTime) / 1000L else 0L

        return TripStats(
            tripId = _tripId,
            status = _status,
            totalDistanceKm = tripDistanceMeters / 1000.0,
            totalTimeSeconds = totalTimeSeconds,
            statusDistanceKm = statusDistanceMeters / 1000.0,
            statusTimeSeconds = if (_tripId != null) (now - statusStartTime) / 1000L else 0L,
            totalDistanceMeters = tripDistanceMeters,
            totalTimeMinutes = totalTimeSeconds / 60.0,
            statusHistory = history,
            totalStoppingSeconds = stoppingMillis / 1000L,
            isPaused = _isPaused
        )
    }

    private fun detectPauseOrResume(lat: Double, lon: Double, timeMillis: Long) {
        if (!hasAnchor) {
            lastSignificantMoveLat = lat
            lastSignificantMoveLon = lon
            lastSignificantMoveTime = timeMillis
            hasAnchor = true
            return
        }

        val distFromAnchor = DistanceCalculator.distance(
            lastSignificantMoveLat, lastSignificantMoveLon, lat, lon
        )

        if (distFromAnchor > pauseDistanceThresholdMeters) {
            // Significant movement — update anchor, resume if paused
            lastSignificantMoveLat = lat
            lastSignificantMoveLon = lon
            lastSignificantMoveTime = timeMillis
            if (_isPaused) {
                _isPaused = false
                totalStoppingMillis += timeMillis - pauseStartTime
                pauseStartTime = 0L
                listener?.onDriverResumed()
            }
        } else {
            // Within threshold — check if duration exceeds pause threshold
            val dwellMillis = timeMillis - lastSignificantMoveTime
            if (!_isPaused && dwellMillis >= pauseTimeThresholdSeconds * 1000L) {
                _isPaused = true
                pauseStartTime = timeMillis
                listener?.onDriverPaused()
            }
        }
    }

    /**
     * The distance to add for a move from the anchor to this fix: null while the car is still
     * within [minStep] of the anchor (noise), [STEP_DROPPED] for a jump faster than
     * [maxSpeedMetersPerSecond]. Fixes with no time between them skip the speed check.
     */
    private fun countedStep(
        fromLat: Double,
        fromLon: Double,
        fromTime: Long,
        lat: Double,
        lon: Double,
        time: Long,
        minStep: Double,
    ): Double? {
        val dist = DistanceCalculator.distance(fromLat, fromLon, lat, lon)
        if (dist < minStep) return null
        val dtSeconds = (time - fromTime) / 1000.0
        if (dtSeconds > 0 && dist / dtSeconds > maxSpeedMetersPerSecond) return STEP_DROPPED
        return dist
    }

    private companion object {
        /** Marker for a dropped jump (negative, so it is never a real distance). */
        const val STEP_DROPPED = -1.0
    }

    private class StatusAccumulator(
        var distanceMeters: Double = 0.0,
        var timeMillis: Long = 0L
    )
}

data class StatusStats(
    val status: String,
    val distanceKm: Double,
    val distanceMeters: Double,
    val timeSeconds: Long,
    val timeMinutes: Double
)

data class TripStats(
    val tripId: String?,
    val status: String?,
    val totalDistanceKm: Double,
    val totalTimeSeconds: Long,
    val statusDistanceKm: Double,
    val statusTimeSeconds: Long,
    val totalDistanceMeters: Double = 0.0,
    val totalTimeMinutes: Double = 0.0,
    val statusHistory: Map<String, StatusStats> = emptyMap(),
    val totalStoppingSeconds: Long = 0L,
    val isPaused: Boolean = false
) {
    fun statsForStatus(statusName: String): StatusStats? = statusHistory[statusName]
}

interface TripTrackerListener {
    fun onTripStatsUpdated(stats: TripStats)
    fun onTripEnded(stats: TripStats)
    fun onDriverPaused() {}
    fun onDriverResumed() {}
}

internal expect fun currentTimeMillis(): Long
