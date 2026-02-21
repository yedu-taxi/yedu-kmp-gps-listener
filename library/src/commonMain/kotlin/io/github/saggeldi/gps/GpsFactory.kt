package io.github.saggeldi.gps

/**
 * Platform factory for creating GPS components.
 *
 * Common one-place initialization:
 * ```
 * // Android: call once in Application.onCreate() or Service.onCreate()
 * GpsFactory.initialize(context)
 *
 * // Then from common code:
 * val tracker = GpsFactory.createGpsTracker(listener)
 * val controller = GpsFactory.createTrackingController(serverUrl = "...", listener = listener)
 * ```
 *
 * Or use platform-specific constructors directly for full control.
 */
expect object GpsFactory {
    fun createLocationProvider(): PlatformLocationProvider
    fun createBatteryProvider(): PlatformBatteryProvider
    fun createPositionStore(): PositionStore
    fun createPositionSender(): PositionSender
    fun createNetworkMonitor(): NetworkMonitor
    fun createRetryScheduler(): RetryScheduler
}

fun GpsFactory.createGpsTracker(listener: GpsTrackerListener): GpsTracker {
    return GpsTracker(createLocationProvider(), listener)
}

fun GpsFactory.createTrackingController(
    serverUrl: String,
    buffer: Boolean = true,
    listener: TrackingControllerListener? = null
): TrackingController {
    return TrackingController(
        locationProvider = createLocationProvider(),
        positionStore = createPositionStore(),
        positionSender = createPositionSender(),
        networkMonitor = createNetworkMonitor(),
        retryScheduler = createRetryScheduler(),
        serverUrl = serverUrl,
        buffer = buffer,
        listener = listener
    )
}
