package io.github.saggeldi.gps

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Tracking pipeline that combines GPS listening, network monitoring, and HTTP sending.
 *
 * Sends the latest position in realtime when online.
 * Positions received while offline are dropped.
 *
 * Supports two API modes:
 * - Legacy: URL query parameters (Traccar/OsmAnd compatible)
 * - JSON: POST with JSON body and Bearer token (Yedu backend API)
 *
 * Set [useJsonApi] = true and provide [apiEndpoint] + [token] for JSON mode.
 */
class TrackingController(
    private val locationProvider: PlatformLocationProvider,
    private val positionSender: PositionSender,
    private val networkMonitor: NetworkMonitor,
    private val serverUrl: String,
    private val listener: TrackingControllerListener? = null,
    private val useJsonApi: Boolean = false,
    private val apiEndpoint: String? = null,
    private val positionDao: PositionDao? = null
) {
    private val gpsTracker: GpsTracker
    private var isOnline = false
    private var stopped = true
    private val dbScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val tripTracker = TripTracker()

    // Runtime-changeable token for Bearer auth
    var token: String? = null

    // Runtime-changeable trip ID and status
    var currentTripId: String?
        get() = tripTracker.tripId
        set(value) {
            // Use startTrip/endTrip instead
        }

    var currentTripStatus: String?
        get() = tripTracker.status
        set(value) {
            // Use updateTripStatus instead
        }

    init {
        gpsTracker = GpsTracker(
            locationProvider = locationProvider,
            listener = object : GpsTrackerListener {
                override fun onPositionUpdate(position: Position) {
                    handlePosition(position)
                }
                override fun onError(error: String) {
                    listener?.onError(error)
                }
                override fun onStatusChange(status: TrackerStatus) {
                    listener?.onStatusChange(status)
                }
            }
        )
    }

    fun startTrip(tripId: String, status: String) {
        tripTracker.startTrip(tripId, status)
    }

    fun updateTripStatus(newStatus: String) {
        tripTracker.updateStatus(newStatus)
    }

    fun endTrip() {
        val tripId = tripTracker.tripId
        tripTracker.endTrip()
        if (tripId != null && positionDao != null) {
            dbScope.launch {
                positionDao.deleteByTrip(tripId)
            }
        }
    }

    fun getTripStats(): TripStats = tripTracker.getTripStats()

    fun start(config: GpsConfig) {
        stopped = false
        isOnline = networkMonitor.isOnline

        networkMonitor.start { online ->
            isOnline = online
        }

        gpsTracker.start(config)
    }

    fun stop() {
        stopped = true
        gpsTracker.stop()
        networkMonitor.stop()
        dbScope.cancel()
    }

    fun updateConfig(newConfig: GpsConfig) {
        gpsTracker.updateConfig(newConfig)
    }

    fun requestSingleLocation() {
        gpsTracker.requestSingleLocation()
    }

    fun isTracking(): Boolean = gpsTracker.isTracking()

    fun currentConfig(): GpsConfig? = gpsTracker.currentConfig()

    private fun handlePosition(position: Position) {
        // Attach trip info to position
        val positionWithTrip = position.copy(
            tripId = tripTracker.tripId,
            tripStatus = tripTracker.status
        )

        // Update trip tracker with new position
        tripTracker.updatePosition(positionWithTrip)

        if (positionDao != null) {
            dbScope.launch {
                positionDao.insert(positionWithTrip.toEntity())

                val totalKm = calculateTotalKmFromDb(positionWithTrip)

                listener?.onPositionUpdate(positionWithTrip)
                if (isOnline) send(positionWithTrip, totalKm)
            }
        } else {
            listener?.onPositionUpdate(positionWithTrip)
            if (isOnline) send(positionWithTrip)
        }
    }

    private suspend fun calculateTotalKmFromDb(position: Position): Double {
        val tripId = position.tripId ?: return 0.0
        val all = positionDao!!.getByTrip(tripId)
        println("DB positions for trip: total=${all.size}, statuses=${all.map { it.tripStatus }.distinct()}")
        val positions = all.filter { it.tripStatus == "on_the_trip" }
        println("on_the_way positions: ${positions.size}")
        if (positions.size < 2) return 0.0
        var totalMeters = 0.0
        for (i in 1 until positions.size) {
            totalMeters += DistanceCalculator.distance(
                positions[i - 1].latitude, positions[i - 1].longitude,
                positions[i].latitude, positions[i].longitude
            )
        }
        println("Total km: ${totalMeters / 1000.0}")
        return totalMeters / 1000.0
    }

    private fun send(position: Position, totalKm: Double = 0.0) {
        if (useJsonApi && apiEndpoint != null) {
            val jsonBody = ProtocolFormatter.formatJsonBody(position, totalKm)
            positionSender.sendJsonPost(apiEndpoint, jsonBody, token) { success ->
                if (success) {
                    listener?.onPositionSent(position)
                } else {
                    listener?.onSendFailed(position)
                }
            }
        } else {
            val request = ProtocolFormatter.formatRequest(serverUrl, position)
            positionSender.sendPosition(request) { success ->
                if (success) {
                    listener?.onPositionSent(position)
                } else {
                    listener?.onSendFailed(position)
                }
            }
        }
    }
}
