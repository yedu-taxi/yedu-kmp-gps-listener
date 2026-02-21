package io.github.saggeldi.gps

/**
 * Full tracking pipeline that combines GPS listening, position caching,
 * network monitoring, and HTTP sending.
 *
 * State machine: write -> read -> send -> delete -> read
 * With retry on failure: read -> send -> retry -> read -> send
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
        positionStore.selectFirstPosition { success, position ->
            if (success) {
                if (position != null) {
                    val currentDeviceId = gpsTracker.currentConfig()?.deviceId
                    if (currentDeviceId == null || position.deviceId == currentDeviceId) {
                        send(position)
                    } else {
                        delete(position)
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
                if (buffer) {
                    delete(position)
                }
            } else {
                listener?.onSendFailed(position)
                if (buffer) {
                    retry()
                }
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

    private fun retry() {
        retryScheduler.schedule(RETRY_DELAY_MS) {
            if (!stopped && isOnline) {
                read()
            }
        }
    }
}
