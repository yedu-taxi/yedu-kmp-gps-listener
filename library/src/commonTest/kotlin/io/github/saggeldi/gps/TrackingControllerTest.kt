package io.github.saggeldi.gps

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ── Fake implementations ────────────────────────────────────────────────

private class FakeLocationProvider : PlatformLocationProvider {
    var onLocation: ((Position) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var startCount = 0
    var stopCount = 0
    var lastConfig: GpsConfig? = null

    override fun startUpdates(config: GpsConfig, onLocation: (Position) -> Unit, onError: (String) -> Unit) {
        startCount++
        lastConfig = config
        this.onLocation = onLocation
        this.onError = onError
    }

    override fun stopUpdates() {
        stopCount++
        onLocation = null
        onError = null
    }

    override fun requestSingleLocation(config: GpsConfig, onLocation: (Position) -> Unit) {
        onLocation(Position(deviceId = config.deviceId, time = 1000L))
    }

    fun emit(position: Position) { onLocation?.invoke(position) }
    fun emitError(error: String) { onError?.invoke(error) }
}

private class FakePositionSender : PositionSender {
    var sendSuccess = true

    /** HTTP status the JSON path answers with; null models a transport failure. */
    var jsonStatus: Int? = 200
    var sentRequests = mutableListOf<String>()
    var sentJsonBodies = mutableListOf<String>()
    var lastToken: String? = null
    val sentTokens = mutableListOf<String?>()

    override fun sendPosition(request: String, onComplete: (Boolean) -> Unit) {
        sentRequests.add(request)
        onComplete(sendSuccess)
    }

    override fun sendPositions(requests: List<String>, onComplete: (List<Boolean>) -> Unit) {
        sentRequests.addAll(requests)
        onComplete(requests.map { sendSuccess })
    }

    override fun sendJsonPost(url: String, jsonBody: String, token: String?, onComplete: (status: Int?, body: String?) -> Unit) {
        sentJsonBodies.add(jsonBody)
        lastToken = token
        sentTokens.add(token)
        onComplete(if (sendSuccess) jsonStatus else null, null)
    }
}

private class FakeNetworkMonitor(private var online: Boolean = true) : NetworkMonitor {
    override val isOnline: Boolean get() = online
    var onNetworkChange: ((Boolean) -> Unit)? = null

    override fun start(onNetworkChange: (Boolean) -> Unit) {
        this.onNetworkChange = onNetworkChange
    }

    override fun stop() {
        onNetworkChange = null
    }

    fun setOnline(value: Boolean) {
        online = value
        onNetworkChange?.invoke(value)
    }
}

private class CollectingControllerListener : TrackingControllerListener {
    val updatedPositions = mutableListOf<Position>()
    val sentPositions = mutableListOf<Position>()
    val failedPositions = mutableListOf<Position>()
    val errors = mutableListOf<String>()
    val statusChanges = mutableListOf<TrackerStatus>()
    var authFailures = 0

    override fun onPositionUpdate(position: Position) { updatedPositions.add(position) }
    override fun onPositionSent(position: Position) { sentPositions.add(position) }
    override fun onSendFailed(position: Position) { failedPositions.add(position) }
    override fun onError(error: String) { errors.add(error) }
    override fun onStatusChange(status: TrackerStatus) { statusChanges.add(status) }
    override fun onAuthFailure() { authFailures++ }
}

private fun epochMs(seconds: Long): Long = seconds * 1000

// ── Tests ───────────────────────────────────────────────────────────────

class TrackingControllerTest {

    private fun createController(
        provider: FakeLocationProvider = FakeLocationProvider(),
        sender: FakePositionSender = FakePositionSender(),
        networkMonitor: FakeNetworkMonitor = FakeNetworkMonitor(online = true),
        serverUrl: String = "https://example.com/api",
        listener: TrackingControllerListener? = null,
        useJsonApi: Boolean = false,
        apiEndpoint: String? = null
    ) = TrackingController(
        locationProvider = provider,
        positionSender = sender,
        networkMonitor = networkMonitor,
        serverUrl = serverUrl,
        listener = listener,
        useJsonApi = useJsonApi,
        apiEndpoint = apiEndpoint
    )

    private val defaultConfig = GpsConfig(deviceId = "test-device", interval = 0)

    // ── Start/Stop ──────────────────────────────────────────────────────

    @Test
    fun startBeginsTracking() {
        val provider = FakeLocationProvider()
        val controller = createController(provider = provider)
        controller.start(defaultConfig)
        assertTrue(controller.isTracking())
    }

    @Test
    fun stopEndsTracking() {
        val provider = FakeLocationProvider()
        val controller = createController(provider = provider)
        controller.start(defaultConfig)
        controller.stop()
        assertFalse(controller.isTracking())
    }

    @Test
    fun currentConfigReturnsConfigAfterStart() {
        val controller = createController()
        controller.start(defaultConfig)
        val cfg = controller.currentConfig()
        assertNotNull(cfg)
        assertEquals("test-device", cfg.deviceId)
    }

    @Test
    fun currentConfigNullBeforeStart() {
        val controller = createController()
        assertNull(controller.currentConfig())
    }

    @Test
    fun statusChangeListenerCalledOnStart() {
        val listener = CollectingControllerListener()
        val controller = createController(listener = listener)
        controller.start(defaultConfig)
        assertTrue(listener.statusChanges.contains(TrackerStatus.STARTED))
    }

    @Test
    fun statusChangeListenerCalledOnStop() {
        val listener = CollectingControllerListener()
        val controller = createController(listener = listener)
        controller.start(defaultConfig)
        controller.stop()
        assertTrue(listener.statusChanges.contains(TrackerStatus.STOPPED))
    }

    // ── Realtime sending when online ────────────────────────────────────

    @Test
    fun sendsPositionDirectlyWhenOnline() {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        val controller = createController(provider = provider, sender = sender)

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertEquals(1, sender.sentRequests.size)
    }

    @Test
    fun doesNotSendPositionWhenOffline() {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        val networkMonitor = FakeNetworkMonitor(online = false)
        val controller = createController(
            provider = provider, sender = sender, networkMonitor = networkMonitor
        )

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertEquals(0, sender.sentRequests.size)
        assertEquals(0, sender.sentJsonBodies.size)
    }

    @Test
    fun listenerReceivesPositionUpdateEvenWhenOffline() {
        val provider = FakeLocationProvider()
        val listener = CollectingControllerListener()
        val networkMonitor = FakeNetworkMonitor(online = false)
        val controller = createController(
            provider = provider, listener = listener, networkMonitor = networkMonitor
        )

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertEquals(1, listener.updatedPositions.size)
    }

    @Test
    fun listenerNotifiedOnSentPosition() {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        sender.sendSuccess = true
        val listener = CollectingControllerListener()
        val controller = createController(
            provider = provider, sender = sender, listener = listener
        )

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertEquals(1, listener.sentPositions.size)
    }

    @Test
    fun sendFailureNotifiesListener() {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        sender.sendSuccess = false
        val listener = CollectingControllerListener()
        val controller = createController(
            provider = provider, sender = sender, listener = listener
        )

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertEquals(1, listener.failedPositions.size)
        assertEquals(0, listener.sentPositions.size)
    }

    // ── Legacy vs JSON sending ──────────────────────────────────────────

    @Test
    fun legacySendUsesUrlFormat() {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        val listener = CollectingControllerListener()
        val controller = createController(
            provider = provider, sender = sender,
            serverUrl = "https://example.com/api", listener = listener
        )

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertEquals(1, sender.sentRequests.size)
        assertTrue(sender.sentRequests[0].startsWith("https://example.com/api"))
        assertEquals(1, listener.sentPositions.size)
    }

    @Test
    fun jsonSendUsesJsonBody() {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        val listener = CollectingControllerListener()
        val controller = createController(
            provider = provider, sender = sender,
            listener = listener,
            useJsonApi = true, apiEndpoint = "https://api.example.com/v1/location"
        )
        controller.tokenProvider = { "my-token" }

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertEquals(1, sender.sentJsonBodies.size)
        assertEquals("my-token", sender.lastToken)
        assertEquals(1, listener.sentPositions.size)
    }

    // ── Error forwarding ────────────────────────────────────────────────

    @Test
    fun errorFromProviderForwardedToListener() {
        val provider = FakeLocationProvider()
        val listener = CollectingControllerListener()
        val controller = createController(provider = provider, listener = listener)

        controller.start(defaultConfig)
        provider.emitError("GPS unavailable")

        assertEquals(1, listener.errors.size)
        assertEquals("GPS unavailable", listener.errors[0])
    }

    // ── Network awareness ───────────────────────────────────────────────

    @Test
    fun sendsWhenComingBackOnline() {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        val networkMonitor = FakeNetworkMonitor(online = false)
        val controller = createController(
            provider = provider, sender = sender, networkMonitor = networkMonitor
        )

        controller.start(defaultConfig)

        // Position while offline - not sent
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))
        assertEquals(0, sender.sentRequests.size)

        // Go online
        networkMonitor.setOnline(true)

        // Next position should be sent
        provider.emit(Position(deviceId = "test-device", time = epochMs(200)))
        assertEquals(1, sender.sentRequests.size)
    }

    // ── Config update ───────────────────────────────────────────────────

    @Test
    fun updateConfigChangesTrackingConfig() {
        val provider = FakeLocationProvider()
        val controller = createController(provider = provider)
        controller.start(defaultConfig)

        val newConfig = GpsConfig(deviceId = "new-device", interval = 60)
        controller.updateConfig(newConfig)

        val cfg = controller.currentConfig()
        assertNotNull(cfg)
        assertEquals("new-device", cfg.deviceId)
        assertEquals(60, cfg.interval)
    }

    // ── Trip integration ────────────────────────────────────────────────

    @Test
    fun tripInfoAttachedToPosition() {
        val provider = FakeLocationProvider()
        val listener = CollectingControllerListener()
        val controller = createController(provider = provider, listener = listener)

        controller.start(defaultConfig)
        controller.startTrip("trip-1", "en_route")
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertEquals(1, listener.updatedPositions.size)
        assertEquals("trip-1", listener.updatedPositions[0].tripId)
        assertEquals("en_route", listener.updatedPositions[0].tripStatus)
    }

    @Test
    fun positionWithoutTripHasNullTripFields() {
        val provider = FakeLocationProvider()
        val listener = CollectingControllerListener()
        val controller = createController(provider = provider, listener = listener)

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertEquals(1, listener.updatedPositions.size)
        assertNull(listener.updatedPositions[0].tripId)
        assertNull(listener.updatedPositions[0].tripStatus)
    }

    @Test
    fun tripStatusUpdateReflectedInPositions() {
        val provider = FakeLocationProvider()
        val listener = CollectingControllerListener()
        val controller = createController(provider = provider, listener = listener)

        controller.start(defaultConfig)
        controller.startTrip("trip-1", "en_route")
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        controller.updateTripStatus("arrived")
        provider.emit(Position(deviceId = "test-device", time = epochMs(200)))

        assertEquals(2, listener.updatedPositions.size)
        assertEquals("en_route", listener.updatedPositions[0].tripStatus)
        assertEquals("arrived", listener.updatedPositions[1].tripStatus)
    }

    @Test
    fun endTripClearsTripFieldsFromPositions() {
        val provider = FakeLocationProvider()
        val listener = CollectingControllerListener()
        val controller = createController(provider = provider, listener = listener)

        controller.start(defaultConfig)
        controller.startTrip("trip-1", "en_route")
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        controller.endTrip()
        provider.emit(Position(deviceId = "test-device", time = epochMs(200)))

        assertEquals(2, listener.updatedPositions.size)
        assertEquals("trip-1", listener.updatedPositions[0].tripId)
        assertNull(listener.updatedPositions[1].tripId)
    }

    @Test
    fun getTripStatsReturnsStats() {
        val controller = createController()
        controller.start(defaultConfig)
        controller.startTrip("trip-1", "en_route")

        val stats = controller.getTripStats()
        assertEquals("trip-1", stats.tripId)
        assertEquals("en_route", stats.status)
    }

    // ── Token management ────────────────────────────────────────────────

    @Test
    fun tokenPassedToSenderInJsonMode() {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        val controller = createController(
            provider = provider, sender = sender,
            useJsonApi = true,
            apiEndpoint = "https://api.example.com/v1/location"
        )

        controller.tokenProvider = { "bearer-123" }
        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertEquals("bearer-123", sender.lastToken)
    }

    @Test
    fun tokenIsReReadOnEverySend() {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        val controller = createController(
            provider = provider, sender = sender,
            useJsonApi = true,
            apiEndpoint = "https://api.example.com/v1/location"
        )

        // What the Ktor client's refresh does to the stored token mid-shift: the
        // tracker must follow it rather than keep posting the one it started with.
        var stored = "token-1"
        controller.tokenProvider = { stored }
        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))
        assertEquals("token-1", sender.lastToken)

        stored = "token-2"
        provider.emit(Position(deviceId = "test-device", time = epochMs(500)))
        assertEquals("token-2", sender.lastToken)
    }

    @Test
    fun nullTokenPassedWhenNotSet() {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        val controller = createController(
            provider = provider, sender = sender,
            useJsonApi = true,
            apiEndpoint = "https://api.example.com/v1/location"
        )

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertNull(sender.lastToken)
    }

    // ── Expired-token recovery ──────────────────────────────────────────
    //
    // These POSTs never touch the Ktor client, so the 401-refresh-retry
    // interceptor does not cover them. The controller has to do it itself, or a
    // driver whose token expires mid-shift keeps posting a dead one and drops
    // out of the geo-index without any sign of it on screen.

    @Test
    fun unauthorizedRefreshesTokenAndResendsThePosition() = runTest {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        val listener = CollectingControllerListener()
        val controller = createController(
            provider = provider, sender = sender, listener = listener,
            useJsonApi = true, apiEndpoint = "https://api.example.com/v1/location"
        )

        var stored = "expired"
        sender.jsonStatus = 401
        controller.tokenProvider = { stored }
        controller.tokenRefresher = {
            stored = "fresh"
            sender.jsonStatus = 200
            true
        }

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        awaitOnDefault { listener.sentPositions.isNotEmpty() }
        assertEquals(listOf<String?>("expired", "fresh"), sender.sentTokens.toList())
        assertEquals(1, listener.sentPositions.size)
        assertEquals(0, listener.authFailures)
    }

    @Test
    fun unauthorizedThatSurvivesRefreshIsReportedToTheApp() = runTest {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        val listener = CollectingControllerListener()
        val controller = createController(
            provider = provider, sender = sender, listener = listener,
            useJsonApi = true, apiEndpoint = "https://api.example.com/v1/location"
        )

        sender.jsonStatus = 401
        controller.tokenProvider = { "expired" }
        controller.tokenRefresher = { false }

        controller.start(defaultConfig)
        repeat(3) { i ->
            provider.emit(Position(deviceId = "test-device", time = epochMs(100L * (i + 1))))
            awaitOnDefault { listener.failedPositions.size > i }
        }

        // Reported once, not once per rejected ping.
        assertEquals(1, listener.authFailures)
    }

    @Test
    fun transportFailureIsNotTreatedAsAnAuthProblem() = runTest {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        val listener = CollectingControllerListener()
        val controller = createController(
            provider = provider, sender = sender, listener = listener,
            useJsonApi = true, apiEndpoint = "https://api.example.com/v1/location"
        )

        var refreshCalls = 0
        sender.sendSuccess = false          // no status at all: the tunnel case
        controller.tokenProvider = { "valid" }
        controller.tokenRefresher = { refreshCalls++; true }

        controller.start(defaultConfig)
        repeat(3) { i ->
            provider.emit(Position(deviceId = "test-device", time = epochMs(100L * (i + 1))))
        }

        assertEquals(3, listener.failedPositions.size)
        assertEquals(0, refreshCalls)
        assertEquals(0, listener.authFailures)
    }

    /**
     * The refresh runs on the controller's own scope, off the test dispatcher,
     * so the assertions have to wait for real work rather than virtual time.
     */
    private suspend fun awaitOnDefault(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout<Unit>(timeoutMs) {
                while (!condition()) delay(5)
            }
        }
    }
}
