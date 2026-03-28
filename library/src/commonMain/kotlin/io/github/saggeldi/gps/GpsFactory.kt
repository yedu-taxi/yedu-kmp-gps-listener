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
    fun createPositionSender(): PositionSender
    fun createNetworkMonitor(): NetworkMonitor
    fun createLocationPermissionHelper(): LocationPermissionHelper
    fun createPositionDatabase(): PositionDatabase
}

fun GpsFactory.createGpsTracker(listener: GpsTrackerListener): GpsTracker {
    return GpsTracker(createLocationProvider(), listener)
}

fun GpsFactory.createTrackingController(
    serverUrl: String,
    listener: TrackingControllerListener? = null,
    enableLocalStorage: Boolean = false
): TrackingController {
    val dao = if (enableLocalStorage) createPositionDatabase().positionDao() else null
    return TrackingController(
        locationProvider = createLocationProvider(),
        positionSender = createPositionSender(),
        networkMonitor = createNetworkMonitor(),
        serverUrl = serverUrl,
        listener = listener,
        positionDao = dao
    )
}

/**
 * Create a TrackingController that sends positions as JSON POST
 * to the Yedu backend API (trips/driver-location endpoint).
 *
 * @param baseUrl The base URL of the API (e.g. "https://api.example.com/api/v1")
 * @param token Initial Bearer token (can be changed at runtime via controller.token)
 * @param listener Optional listener for position events
 * @param enableLocalStorage Whether to persist each position to the local Room database
 */
fun GpsFactory.createApiTrackingController(
    baseUrl: String,
    token: String? = null,
    listener: TrackingControllerListener? = null,
    enableLocalStorage: Boolean = false
): TrackingController {
    val apiEndpoint = "${baseUrl.trimEnd('/')}/trips/driver-location"
    val dao = if (enableLocalStorage) createPositionDatabase().positionDao() else null
    val controller = TrackingController(
        locationProvider = createLocationProvider(),
        positionSender = createPositionSender(),
        networkMonitor = createNetworkMonitor(),
        serverUrl = baseUrl,
        listener = listener,
        useJsonApi = true,
        apiEndpoint = apiEndpoint,
        positionDao = dao
    )
    controller.token = token
    return controller
}
