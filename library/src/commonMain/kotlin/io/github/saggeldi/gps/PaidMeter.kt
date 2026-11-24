package io.github.saggeldi.gps

/**
 * The metres a rider pays for. Both billed figures use it, the live `accumulated_km` and the final
 * distance, so they always agree. Same rules as [TripTracker]: summing every raw fix billed the GPS
 * noise around a standing car as driving.
 * - a fix worse than [MAX_ACCURACY_M] is skipped (0 = accuracy not reported: trusted);
 * - a step counts once it clears the anchor by 10 m and by both fixes' error circles;
 * - a jump faster than about 250 km/h is dropped. The anchor stays, so real driving catches up.
 */
internal class PaidMeter {
    var meters = 0.0
        private set

    private var hasAnchor = false
    private var lat = 0.0
    private var lon = 0.0
    private var time = 0L
    private var error = 0.0

    fun add(latitude: Double, longitude: Double, timeMillis: Long, accuracy: Double) {
        if (accuracy > MAX_ACCURACY_M) return
        val fixError = accuracy.coerceAtLeast(0.0)
        if (hasAnchor) {
            val step = DistanceCalculator.distance(lat, lon, latitude, longitude)
            if (step < maxOf(MIN_STEP_M, minOf(error + fixError, MAX_ERROR_STEP_M))) return
            val seconds = (timeMillis - time) / 1000.0
            if (seconds > 0 && step / seconds > MAX_SPEED_MPS) return
            meters += step
        }
        hasAnchor = true
        lat = latitude
        lon = longitude
        time = timeMillis
        error = fixError
    }

    private companion object {
        const val MAX_ACCURACY_M = 50.0
        const val MIN_STEP_M = 10.0
        const val MAX_ERROR_STEP_M = 50.0
        const val MAX_SPEED_MPS = 70.0
    }
}
