package io.github.saggeldi.gps

/**
 * Latest turn-by-turn navigation figures from the platform Navigation SDK,
 * handed to the [TrackingController] so each `POST /trips/driver-location`
 * carries the live route guidance the backend forwards to the passenger.
 *
 * Which fields the server reads depends on the trip phase (see
 * [ProtocolFormatter.formatJsonBody]):
 *  - `on_the_way`  → distance_to_pickup_in_km / time_to_pickup_in_min
 *  - `on_the_trip` → distance_to_arrival_in_km / time_to_arrival_in_min
 *
 * Values are raw SDK units (meters / seconds); the formatter converts to the
 * km / minutes the API expects.
 */
data class NavSnapshot(
    val remainingDistanceMeters: Int,
    val remainingTimeSeconds: Int,
) {
    /** Remaining distance to the active destination in km (1 decimal). */
    val remainingKm: Double
        get() = if (remainingDistanceMeters <= 0) 0.0
        else ((remainingDistanceMeters / 100.0).toLong()) / 10.0

    /** Remaining time to the active destination in minutes (1 decimal). */
    val remainingMinutes: Double
        get() = if (remainingTimeSeconds <= 0) 0.0
        else ((remainingTimeSeconds / 6.0).toLong()) / 10.0

    val hasDistance: Boolean get() = remainingDistanceMeters > 0
    val hasTime: Boolean get() = remainingTimeSeconds > 0
}
