package io.github.saggeldi.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Fixes from Google Play services' fused location provider.
 *
 * This used to read `LocationManager.GPS_PROVIDER` directly. Raw GPS has no
 * sensor fusion and no Wi-Fi/cell assistance, so it produces noisier points and
 * long dropouts in exactly the places taxis spend their time — between tall
 * buildings, in tunnels, in underground pickup areas. The fused provider fills
 * those gaps, which is what the passenger's map ends up showing.
 */
class AndroidLocationProvider(
    private val context: Context
) : PlatformLocationProvider {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val batteryProvider = AndroidBatteryProvider(context)
    private var locationCallback: LocationCallback? = null

    /**
     * Fixes are delivered here, not on the main looper: each one reads the battery and starts
     * persistence work, which competed with the map and the Navigation SDK for main-thread time.
     */
    private val callbackThread: HandlerThread by lazy {
        HandlerThread("yedu-location").apply { start() }
    }
    private val callbackLooper: Looper
        get() = callbackThread.looper

    /**
     * Last heading Android actually knew, in degrees.
     *
     * `hasBearing()` is false whenever the heading is unknown, which is the normal
     * state while the car is stopped. Reporting 0 there would spin the car icon on
     * the passenger's map to due north every time the driver stops, so the last
     * known good heading is held instead.
     */
    private var lastKnownCourse: Double = 0.0

    companion object {
        private const val MINIMUM_INTERVAL_MS = 1000L
        /** Reject GPS fixes older than this many seconds. */
        private const val MAX_LOCATION_AGE_SECONDS = 10L
    }

    @SuppressLint("MissingPermission")
    override fun startUpdates(
        config: GpsConfig,
        onLocation: (Position) -> Unit,
        onError: (String) -> Unit
    ) {
        stopUpdates()

        val priority = when (config.accuracy) {
            LocationAccuracy.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
            LocationAccuracy.MEDIUM -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            LocationAccuracy.LOW -> Priority.PRIORITY_LOW_POWER
        }

        // With a distance or angle trigger configured, fixes have to arrive
        // faster than `interval` for [GpsTracker] to have anything to test those
        // triggers against — it can only filter fixes it is given.
        val intervalMs = if (config.distance > 0 || config.angle > 0) {
            config.fastestIntervalMillis.coerceIn(MINIMUM_INTERVAL_MS, config.interval * 1000)
        } else {
            config.interval * 1000
        }

        val request = LocationRequest.Builder(priority, intervalMs)
            .setMaxUpdateDelayMillis(config.maxBatchDelayMillis.coerceAtLeast(0))
            // Accept an early fix rather than holding it back to the interval
            // boundary; GpsTracker applies the real rate limit and now allows a
            // small tolerance, so this is what keeps the cadence steady.
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                // A batch (maxBatchDelayMillis) carries several fixes, oldest first: send them
                // all so the trail keeps its corners, skipping stale ones.
                val battery = batteryProvider.getBatteryStatus()
                for (location in result.locations) {
                    if (isLocationStale(location)) continue
                    onLocation(location.toPosition(config.deviceId, battery))
                }
            }
        }
        locationCallback = callback

        try {
            fusedClient.requestLocationUpdates(
                request, callback, callbackLooper
            ).addOnFailureListener { e ->
                onError("Location provider error: ${e.message}")
            }
        } catch (e: SecurityException) {
            onError("Location permission not granted: ${e.message}")
        } catch (e: Exception) {
            onError("Location provider error: ${e.message}")
        }
    }

    override fun stopUpdates() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    @SuppressLint("MissingPermission")
    override fun requestSingleLocation(
        config: GpsConfig,
        onLocation: (Position) -> Unit
    ) {
        try {
            fusedClient.lastLocation.addOnSuccessListener { location ->
                if (location != null && !isLocationStale(location)) {
                    val battery = batteryProvider.getBatteryStatus()
                    onLocation(location.toPosition(config.deviceId, battery))
                }
            }
        } catch (_: SecurityException) {
        }
    }

    /**
     * Reject locations whose GPS fix is older than [MAX_LOCATION_AGE_SECONDS].
     * Uses elapsed-realtime clock which is immune to wall-clock adjustments.
     */
    private fun isLocationStale(location: Location): Boolean {
        val ageNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        val ageSeconds = ageNanos / 1_000_000_000L
        return ageSeconds > MAX_LOCATION_AGE_SECONDS
    }

    private fun Location.toPosition(deviceId: String, battery: BatteryStatus): Position {
        if (hasBearing()) lastKnownCourse = bearing.toDouble()

        return Position(
            deviceId = deviceId,
            time = time,
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            // Reported in m/s and stored as-is; each wire format converts.
            speed = if (hasSpeed()) speed.toDouble() else 0.0,
            course = lastKnownCourse,
            accuracy = if (hasAccuracy()) accuracy.toDouble() else 0.0,
            battery = battery,
            mock = isMockLocation()
        )
    }

    private fun Location.isMockLocation(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            isMock
        } else {
            @Suppress("DEPRECATION")
            isFromMockProvider
        }
    }
}
