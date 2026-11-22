package io.github.saggeldi.gps

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.Volatile

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
 * Set [useJsonApi] = true and provide [apiEndpoint] + [tokenProvider] for JSON mode.
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

    /**
     * Single-threaded: the per-position work (insert → accumulate km → send) must run
     * in arrival order. On a multi-threaded dispatcher position N+1 could be POSTed
     * before position N, and the km accumulator below assumes one writer.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dbScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    /**
     * Runs the token refresh a 401 triggers. Kept off [dbScope] so a refresh
     * waiting on the network cannot stall position persistence behind it.
     */
    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val tripTracker = TripTracker()

    /**
     * Reads the current Bearer token, at send time.
     *
     * This used to be a `var token: String?` the caller set once when it built
     * the controller. The foreground service then held that one string for the
     * whole shift while the Ktor client refreshed the real token out from under
     * it, so every location POST kept presenting a token that had expired
     * 30 minutes into the shift — and an online driver whose pings all 401 is
     * absent from the geo-index, i.e. unbookable, with nothing on screen to say
     * so. Auth state is mutable and shared, so it has to be read per request.
     */
    var tokenProvider: (() -> String?)? = null

    /**
     * Obtains a fresh access token after a 401, returning true when one is
     * available. Set by the app layer to whatever owns the refresh flow, so the
     * GPS path — which posts outside the Ktor client and therefore never hits
     * its 401 interceptor — can recover on its own instead of retrying a dead
     * token until the shift ends.
     */
    var tokenRefresher: (suspend () -> Boolean)? = null

    // Consecutive unrecovered 401s. Reset by any 2xx.
    @Volatile
    private var authFailureCount = 0

    @Volatile
    private var authFailureReported = false

    /** Held for the duration of a refresh so a burst of 401s triggers only one. */
    private val refreshMutex = Mutex()

    /**
     * Latest navigation guidance from the platform Navigation SDK. Set by the
     * app layer (Android: GpsTrackingService observing the SDK feed; iOS: the
     * NavigationMapManager bridge) so each location POST carries live
     * distance/ETA-to-pickup or -arrival. `null` means "no active guidance".
     */
    var navSnapshot: NavSnapshot? = null

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
                // The rows behind the km accumulator are gone, so forget them too —
                // otherwise reopening the same trip id would resume from a stale anchor.
                resetPaidKm()
            }
        }
    }

    fun getTripStats(): TripStats = tripTracker.getTripStats()

    /**
     * Computes the total driven distance (km) for [tripId] from the positions
     * persisted in the local database, counting only the paid `on_the_trip`
     * segment. Returns 0.0 when local storage is disabled or fewer than two
     * points are available.
     *
     * Call this before [endTrip], since [endTrip] purges the trip's rows.
     */
    suspend fun getTripDistanceKm(tripId: String): Double {
        val dao = positionDao ?: return 0.0
        return paidDistanceKm(dao, tripId)
    }

    fun start(config: GpsConfig) {
        stopped = false
        // PRB-D-A1-017: fixes outside a trip are only needed for the live ping; drop old ones
        // so the table doesn't grow for months. Trip rows are purged by endTrip().
        positionDao?.let { dao ->
            dbScope.launch {
                runCatching { dao.deleteUntaggedBefore(currentTimeMillis() - UNTAGGED_RETENTION_MS) }
            }
        }

        // Start the monitor first, then read it: a monitor that only determines its
        // state in start() would otherwise report offline here and never correct it,
        // since it has no change to report afterwards. Nothing would ever be sent.
        networkMonitor.start { online ->
            val cameBackOnline = online && !isOnline
            isOnline = online
            if (cameBackOnline) flushOfflineBacklog()
        }
        isOnline = networkMonitor.isOnline

        gpsTracker.start(config)
    }

    fun stop() {
        stopped = true
        gpsTracker.stop()
        networkMonitor.stop()
        // Drop in-flight work but keep the scope usable. Cancelling the scope itself
        // cancels its SupervisorJob for good, so a later start() on the same instance
        // would silently discard every insert and report accumulated_km = 0.
        dbScope.coroutineContext.cancelChildren()
        authScope.coroutineContext.cancelChildren()
        authFailureCount = 0
        authFailureReported = false
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

                val totalKm = accumulatePaidKm(positionWithTrip)

                listener?.onPositionUpdate(positionWithTrip)
                if (isOnline) send(positionWithTrip, totalKm) else hold(positionWithTrip, totalKm)
            }
        } else {
            listener?.onPositionUpdate(positionWithTrip)
            if (isOnline) {
                send(positionWithTrip)
            } else {
                // Onto dbScope even with no DAO: it is the single thread that
                // owns the backlog, and this path runs on the GPS callback thread.
                dbScope.launch { hold(positionWithTrip, 0.0) }
            }
        }
    }

    // ==================== Offline backlog ====================
    //
    // Positions produced while offline used to be dropped outright. A tunnel or a
    // dead zone therefore punched a permanent hole in the trip: the passenger's
    // map skipped, and — because the server only ever saw the fixes either side —
    // the distance and any toll gate crossed inside the gap went unbilled.
    //
    // They are now held and replayed in order once connectivity returns. The
    // buffer is in-memory and bounded: persisting it would need a schema change
    // on the positions table, and this already covers the case that actually
    // happens, which is a short outage inside a single session.

    private val offlineBacklog = mutableListOf<Pair<Position, Double>>()

    /** Called from [dbScope], which is single-threaded — no locking needed. */
    private fun hold(position: Position, totalKm: Double) {
        if (offlineBacklog.size >= MAX_OFFLINE_BACKLOG) {
            // Keep the newest: the oldest fixes contribute least to the replay.
            offlineBacklog.removeAt(0)
        }
        offlineBacklog.add(position to totalKm)
    }

    private fun flushOfflineBacklog() {
        dbScope.launch {
            if (offlineBacklog.isEmpty()) return@launch
            val pending = offlineBacklog.toList()
            offlineBacklog.clear()
            // In order, so the server's view of the route matches how it was driven.
            for ((position, totalKm) in pending) {
                if (!isOnline) {
                    // Dropped out again mid-replay; hold what is left.
                    offlineBacklog.add(position to totalKm)
                    continue
                }
                send(position, totalKm)
            }
        }
    }

    // Running paid distance in metres behind `accumulated_km`, and the fix it was last
    // advanced from. Read/written only from [dbScope], which is single-threaded.
    private var paidTripId: String? = null
    private var paidMeters = 0.0
    private var lastPaidLat = 0.0
    private var lastPaidLon = 0.0
    private var hasLastPaid = false

    /**
     * Paid (`on_the_trip`) distance in km for the position just persisted.
     *
     * Seeded from the stored rows on the trip's first paid fix — so a mid-trip process
     * restart resumes from what is on disk — then advanced one leg at a time. The
     * arithmetic is the same sum of consecutive haversines [getTripDistanceKm] returns;
     * the difference is that it no longer re-reads and re-sums the trip's entire row
     * set on every position, which grew linearly with trip length.
     */
    private suspend fun accumulatePaidKm(position: Position): Double {
        val tripId = position.tripId
        if (tripId != paidTripId) {
            resetPaidKm()
            paidTripId = tripId
        }
        if (tripId == null || position.tripStatus != PAID_STATUS) return paidMeters / 1000.0
        if (hasLastPaid) {
            paidMeters += DistanceCalculator.distance(
                lastPaidLat, lastPaidLon, position.latitude, position.longitude
            )
        } else {
            paidMeters = getTripDistanceKm(tripId) * 1000.0
        }
        lastPaidLat = position.latitude
        lastPaidLon = position.longitude
        hasLastPaid = true
        return paidMeters / 1000.0
    }

    private fun resetPaidKm() {
        paidTripId = null
        paidMeters = 0.0
        hasLastPaid = false
    }

    private fun send(position: Position, totalKm: Double = 0.0, isAuthRetry: Boolean = false) {
        if (useJsonApi && apiEndpoint != null) {
            val jsonBody = ProtocolFormatter.formatJsonBody(
                position = position,
                totalKm = totalKm,
                nav = navSnapshot,
                tripStatus = tripTracker.status
            )
            positionSender.sendJsonPost(apiEndpoint, jsonBody, tokenProvider?.invoke()) { status, responseBody ->
                when {
                    status != null && status in 200..299 -> {
                        authFailureCount = 0
                        authFailureReported = false
                        listener?.onPositionSent(position)
                        responseBody?.let { body ->
                            parseFare(body)?.let { fare -> listener?.onFareReceived(fare) }
                        }
                    }

                    // The token expired mid-shift. Refresh once and resend this
                    // fix; only a 401 that survives the refresh counts as a real
                    // auth failure.
                    status == UNAUTHORIZED && !isAuthRetry -> refreshTokenAndResend(position, totalKm)

                    status == UNAUTHORIZED -> recordAuthFailure(position)

                    else -> listener?.onSendFailed(position)
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

    /**
     * Refreshes the access token and resends [position] with it.
     *
     * Single-flight: at the 5 s fix rate an expired token 401s a dozen pings a
     * minute, and one refresh serves them all. A ping that 401s while a refresh
     * is running waits for it and is resent once with the new token; only a 401
     * after a resend counts towards [MAX_CONSECUTIVE_AUTH_FAILURES].
     */
    private fun refreshTokenAndResend(position: Position, totalKm: Double) {
        val refresher = tokenRefresher
        if (refresher == null) {
            recordAuthFailure(position)
            return
        }
        authScope.launch {
            if (!refreshMutex.tryLock()) {
                // Another 401 is already refreshing (PRB-D-A1-010): wait for it and resend with
                // the new token. Counting this one as a failure took drivers offline while the
                // refresh was about to succeed.
                refreshMutex.lock()
                refreshMutex.unlock()
                send(position, totalKm, isAuthRetry = true)
                return@launch
            }
            val refreshed = try {
                refresher()
            } catch (ex: CancellationException) {
                // stop() cancelled the scope — not an auth verdict.
                throw ex
            } catch (_: Exception) {
                false
            } finally {
                refreshMutex.unlock()
            }
            if (refreshed) {
                send(position, totalKm, isAuthRetry = true)
            } else {
                recordAuthFailure(position)
            }
        }
    }

    /**
     * A 401 the refresh could not fix. Once enough of them pile up the driver is
     * told: sitting "online" while every ping is rejected means being invisible
     * to riders, which is worse than a visible error.
     */
    private fun recordAuthFailure(position: Position) {
        listener?.onSendFailed(position)
        authFailureCount++
        if (authFailureCount >= MAX_CONSECUTIVE_AUTH_FAILURES && !authFailureReported) {
            authFailureReported = true
            listener?.onAuthFailure()
        }
    }

    private companion object {
        /** Trip phase whose distance is billable, and the only one `accumulated_km` counts. */
        const val PAID_STATUS = "on_the_trip"

        const val UNAUTHORIZED = 401

        /**
         * Unrecovered 401s tolerated before the driver is told. Three at the 5 s
         * fix rate is ~15 s — long enough to ride out a single bad refresh, short
         * enough that nobody drives around unbookable.
         */
        const val MAX_CONSECUTIVE_AUTH_FAILURES = 3

        /** Untagged (no trip) fixes are kept this long. */
        const val UNTAGGED_RETENTION_MS = 24L * 60 * 60 * 1000

        /**
         * Positions held while offline. At the 5 s fix interval this is ~25
         * minutes of outage, well past any tunnel.
         */
        const val MAX_OFFLINE_BACKLOG = 300
    }

    /** Extracts current_fare from: {"data":{"current_fare":"25.50"},"success":true} */
    private fun parseFare(json: String): String? {
        return try {
            val match = Regex(""""current_fare"\s*:\s*"([^"]+)"""").find(json)
            match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}

/** Sum of consecutive haversines over [tripId]'s paid (`on_the_trip`) fixes, in km. */
internal suspend fun paidDistanceKm(dao: PositionDao, tripId: String): Double {
    val positions = dao.getByTripAndStatus(tripId, "on_the_trip")
    if (positions.size < 2) return 0.0
    var totalMeters = 0.0
    for (i in 1 until positions.size) {
        totalMeters += DistanceCalculator.distance(
            positions[i - 1].latitude, positions[i - 1].longitude,
            positions[i].latitude, positions[i].longitude
        )
    }
    return totalMeters / 1000.0
}
