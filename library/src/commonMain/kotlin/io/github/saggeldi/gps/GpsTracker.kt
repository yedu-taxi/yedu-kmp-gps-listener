package io.github.saggeldi.gps

import kotlin.math.abs

class GpsTracker(
    private val locationProvider: PlatformLocationProvider,
    private val listener: GpsTrackerListener
) {
    private var config: GpsConfig? = null
    private var lastPosition: Position? = null
    private var isRunning = false

    fun start(config: GpsConfig) {
        if (isRunning) stop()

        this.config = config
        this.lastPosition = null
        this.isRunning = true

        locationProvider.startUpdates(
            config = config,
            onLocation = { position ->
                handleRawPosition(position)
            },
            onError = { error ->
                listener.onError(error)
            }
        )

        listener.onStatusChange(TrackerStatus.STARTED)
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        locationProvider.stopUpdates()
        listener.onStatusChange(TrackerStatus.STOPPED)
    }

    fun updateConfig(newConfig: GpsConfig) {
        val wasRunning = isRunning
        if (wasRunning) {
            locationProvider.stopUpdates()
        }
        this.config = newConfig
        if (wasRunning) {
            locationProvider.startUpdates(
                config = newConfig,
                onLocation = { position -> handleRawPosition(position) },
                onError = { error -> listener.onError(error) }
            )
        }
    }

    fun requestSingleLocation() {
        val cfg = config ?: return
        locationProvider.requestSingleLocation(cfg) { position ->
            listener.onPositionUpdate(position)
        }
    }

    fun isTracking(): Boolean = isRunning

    fun currentConfig(): GpsConfig? = config

    private fun handleRawPosition(position: Position) {
        val cfg = config ?: return
        if (!isRunning) return

        if (shouldAcceptPosition(position, cfg)) {
            lastPosition = position
            listener.onPositionUpdate(position)
        }
    }

    private fun shouldAcceptPosition(position: Position, config: GpsConfig): Boolean {
        val last = lastPosition ?: return true

        val elapsedSeconds = (position.time - last.time) / 1000
        if (elapsedSeconds >= config.interval) return true

        if (config.distance > 0) {
            val dist = DistanceCalculator.distance(
                last.latitude, last.longitude,
                position.latitude, position.longitude
            )
            if (dist >= config.distance) return true
        }

        if (config.angle > 0) {
            if (abs(position.course - last.course) >= config.angle) return true
        }

        return false
    }
}
