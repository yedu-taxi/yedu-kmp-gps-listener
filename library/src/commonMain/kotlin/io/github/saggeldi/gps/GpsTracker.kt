package io.github.saggeldi.gps

import kotlin.math.abs

class GpsTracker(
    private val locationProvider: PlatformLocationProvider,
    private val listener: GpsTrackerListener
) {
    private var config: GpsConfig? = null
    private var lastPosition: Position? = null
    private var isRunning = false

    private companion object {
        /**
         * Slack allowed when checking a fix against [GpsConfig.interval].
         *
         * The platform providers are configured with the same interval as their
         * own minimum period, so fixes land right on the boundary and routinely
         * arrive a few milliseconds early. Without this, those fixes are rejected
         * and the next one is a full interval later.
         */
        const val INTERVAL_TOLERANCE_MS = 250L
    }

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

        // Compared in milliseconds, with a tolerance for provider jitter.
        //
        // This used to be `(position.time - last.time) / 1000 >= config.interval`.
        // Integer division truncates, and the platform providers are already
        // rate-limited to the same interval — so a fix arriving 4999 ms after the
        // last one yielded 4, failed `>= 5`, and was dropped. Roughly every other
        // fix died here, halving the effective update rate the passenger sees.
        val elapsedMs = position.time - last.time
        if (elapsedMs + INTERVAL_TOLERANCE_MS >= config.interval * 1000) return true

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
