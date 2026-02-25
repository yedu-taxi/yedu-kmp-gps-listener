package io.github.saggeldi.gps

/**
 * Full tracking pipeline that combines GPS listening, position caching,
 * network monitoring, and HTTP sending.
 *
 * State machine: write -> readAll -> sendBatch -> deleteSent -> readAll
 * With retry on failure: readAll -> sendBatch -> retry -> readAll -> sendBatch
 *
 * Based on TrackingController from both Android and iOS Traccar clients.
 */
class TrackingController(
    private val locationProvider: PlatformLocationProvider,
    private val positionStore: PositionStore,
    private val positionSender: PositionSender,
    private val networkMonitor: NetworkMonitor,
    private val retryScheduler: RetryScheduler,
    private val serverUrl: String,
    private val buffer: Boolean = true,
    private val listener: TrackingControllerListener? = null
) {
    private val gpsTracker: GpsTracker
    private var isOnline = false
    private var isWaiting = false
    private var stopped = true

    companion object {
        const val RETRY_DELAY_MS = 30_000L
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

    fun start(config: GpsConfig) {
        stopped = false
        isOnline = networkMonitor.isOnline

        networkMonitor.start { online ->
            if (!isOnline && online) {
                read()
            }
            isOnline = online
        }

        if (isOnline) {
            read()
        }

        gpsTracker.start(config)
    }

    fun stop() {
        stopped = true
        gpsTracker.stop()
        networkMonitor.stop()
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
        listener?.onPositionUpdate(position)
        if (buffer) {
            write(position)
        } else {
            send(position)
        }
    }

    private fun write(position: Position) {
        positionStore.insertPosition(position) { success ->
            if (success) {
                if (isOnline && isWaiting) {
                    read()
                    isWaiting = false
                }
            }
        }
    }

    private fun read() {
        if (stopped) return
        positionStore.selectAllPositions { success, positions ->
            if (success) {
                if (positions.isNotEmpty()) {
                    val currentDeviceId = gpsTracker.currentConfig()?.deviceId
                    val toSend = mutableListOf<Position>()
                    val toDelete = mutableListOf<Position>()

                    for (position in positions) {
                        if (currentDeviceId == null || position.deviceId == currentDeviceId) {
                            toSend.add(position)
                        } else {
                            toDelete.add(position)
                        }
                    }

                    if (toDelete.isNotEmpty()) {
                        deletePositions(toDelete.map { it.id }) {
                            if (toSend.isNotEmpty()) {
                                sendBatch(toSend)
                            } else {
                                read()
                            }
                        }
                    } else if (toSend.isNotEmpty()) {
                        sendBatch(toSend)
                    } else {
                        isWaiting = true
                    }
                } else {
                    isWaiting = true
                }
            } else {
                retry()
            }
        }
    }

    private fun send(position: Position) {
        val request = ProtocolFormatter.formatRequest(serverUrl, position)
        positionSender.sendPosition(request) { success ->
            if (success) {
                listener?.onPositionSent(position)
            } else {
                listener?.onSendFailed(position)
            }
        }
    }

    private fun sendBatch(positions: List<Position>) {
        val requests = positions.map { ProtocolFormatter.formatRequest(serverUrl, it) }
        positionSender.sendPositions(requests) { results ->
            val sentIds = mutableListOf<Long>()
            var hasFailure = false

            positions.forEachIndexed { index, position ->
                if (results[index]) {
                    listener?.onPositionSent(position)
                    sentIds.add(position.id)
                } else {
                    listener?.onSendFailed(position)
                    hasFailure = true
                }
            }

            if (sentIds.isNotEmpty()) {
                deletePositions(sentIds) {
                    if (hasFailure) {
                        retry()
                    } else {
                        read()
                    }
                }
            } else if (hasFailure) {
                retry()
            }
        }
    }

    private fun delete(position: Position) {
        positionStore.deletePosition(position.id) { success ->
            if (success) {
                read()
            } else {
                retry()
            }
        }
    }

    private fun deletePositions(ids: List<Long>, onDone: () -> Unit) {
        positionStore.deletePositions(ids) { success ->
            if (success) {
                onDone()
            } else {
                retry()
            }
        }
    }

    private fun retry() {
        retryScheduler.schedule(RETRY_DELAY_MS) {
            if (!stopped && isOnline) {
                read()
            }
        }
    }
}
