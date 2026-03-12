package io.github.saggeldi.gps

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

private class FakePositionStore : PositionStore {
    val positions = mutableListOf<Position>()
    private var nextId = 1L
    var insertFailNext = false
    var selectFailNext = false
    var deleteFailNext = false

    override fun insertPosition(position: Position, onComplete: (Boolean) -> Unit) {
        if (insertFailNext) {
            insertFailNext = false
            onComplete(false)
            return
        }
        positions.add(position.copy(id = nextId++))
        onComplete(true)
    }

    override fun selectFirstPosition(onComplete: (success: Boolean, position: Position?) -> Unit) {
        if (selectFailNext) {
            selectFailNext = false
            onComplete(false, null)
            return
        }
        onComplete(true, positions.firstOrNull())
    }

    override fun selectAllPositions(onComplete: (success: Boolean, positions: List<Position>) -> Unit) {
        if (selectFailNext) {
            selectFailNext = false
            onComplete(false, emptyList())
            return
        }
        onComplete(true, positions.toList())
    }

    override fun deletePosition(id: Long, onComplete: (Boolean) -> Unit) {
        if (deleteFailNext) {
            deleteFailNext = false
            onComplete(false)
            return
        }
        positions.removeAll { it.id == id }
        onComplete(true)
    }

    override fun deletePositions(ids: List<Long>, onComplete: (Boolean) -> Unit) {
        if (deleteFailNext) {
            deleteFailNext = false
            onComplete(false)
            return
        }
        positions.removeAll { it.id in ids }
        onComplete(true)
    }
}

private class FakePositionSender : PositionSender {
    var sendSuccess = true
    var sentRequests = mutableListOf<String>()
    var sentJsonBodies = mutableListOf<String>()
    var lastToken: String? = null

    override fun sendPosition(request: String, onComplete: (Boolean) -> Unit) {
        sentRequests.add(request)
        onComplete(sendSuccess)
    }

    override fun sendPositions(requests: List<String>, onComplete: (List<Boolean>) -> Unit) {
        sentRequests.addAll(requests)
        onComplete(requests.map { sendSuccess })
    }

    override fun sendJsonPost(url: String, jsonBody: String, token: String?, onComplete: (Boolean) -> Unit) {
        sentJsonBodies.add(jsonBody)
        lastToken = token
        onComplete(sendSuccess)
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

private class FakeRetryScheduler : RetryScheduler {
    val scheduledActions = mutableListOf<() -> Unit>()

    override fun schedule(delayMs: Long, action: () -> Unit) {
        scheduledActions.add(action)
    }

    fun executeAll() {
        val actions = scheduledActions.toList()
        scheduledActions.clear()
        actions.forEach { it() }
    }
}

private class CollectingControllerListener : TrackingControllerListener {
    val updatedPositions = mutableListOf<Position>()
    val sentPositions = mutableListOf<Position>()
    val failedPositions = mutableListOf<Position>()
    val errors = mutableListOf<String>()
    val statusChanges = mutableListOf<TrackerStatus>()

    override fun onPositionUpdate(position: Position) { updatedPositions.add(position) }
    override fun onPositionSent(position: Position) { sentPositions.add(position) }
    override fun onSendFailed(position: Position) { failedPositions.add(position) }
    override fun onError(error: String) { errors.add(error) }
    override fun onStatusChange(status: TrackerStatus) { statusChanges.add(status) }
}

private fun epochMs(seconds: Long): Long = seconds * 1000

// ── Tests ───────────────────────────────────────────────────────────────

class TrackingControllerTest {

    private fun createController(
        provider: FakeLocationProvider = FakeLocationProvider(),
        store: FakePositionStore = FakePositionStore(),
        sender: FakePositionSender = FakePositionSender(),
        networkMonitor: FakeNetworkMonitor = FakeNetworkMonitor(online = true),
        retryScheduler: FakeRetryScheduler = FakeRetryScheduler(),
        serverUrl: String = "https://example.com/api",
        buffer: Boolean = true,
        listener: TrackingControllerListener? = null,
        useJsonApi: Boolean = false,
        apiEndpoint: String? = null
    ) = TrackingController(
        locationProvider = provider,
        positionStore = store,
        positionSender = sender,
        networkMonitor = networkMonitor,
        retryScheduler = retryScheduler,
        serverUrl = serverUrl,
        buffer = buffer,
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

    // ── Buffered mode (buffer = true) ───────────────────────────────────

    @Test
    fun bufferedModeStoresPositionOnUpdate() {
        val provider = FakeLocationProvider()
        val store = FakePositionStore()
        val networkMonitor = FakeNetworkMonitor(online = false)
        val controller = createController(
            provider = provider, store = store,
            networkMonitor = networkMonitor, buffer = true
        )

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        // Offline, so position stays in store
        assertEquals(1, store.positions.size)
    }

    @Test
    fun bufferedModeSendsStoredPositionsWhenOnline() {
        val provider = FakeLocationProvider()
        val store = FakePositionStore()
        val sender = FakePositionSender()
        val controller = createController(
            provider = provider, store = store, sender = sender,
            buffer = true
        )

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        // Positions should be stored and then sent
        assertTrue(sender.sentRequests.isNotEmpty() || store.positions.isEmpty())
    }

    @Test
    fun bufferedModeDeletesPositionAfterSuccessfulSend() {
        val provider = FakeLocationProvider()
        val store = FakePositionStore()
        val sender = FakePositionSender()
        sender.sendSuccess = true
        val controller = createController(
            provider = provider, store = store, sender = sender,
            buffer = true
        )

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        // After successful send, positions should be deleted from store
        assertTrue(store.positions.isEmpty())
    }

    @Test
    fun bufferedModeListenerReceivesPositionUpdate() {
        val provider = FakeLocationProvider()
        val listener = CollectingControllerListener()
        val controller = createController(
            provider = provider, listener = listener, buffer = true
        )

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertEquals(1, listener.updatedPositions.size)
    }

    @Test
    fun bufferedModeListenerNotifiedOnSentPosition() {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        sender.sendSuccess = true
        val listener = CollectingControllerListener()
        val controller = createController(
            provider = provider, sender = sender,
            listener = listener, buffer = true
        )

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertEquals(1, listener.sentPositions.size)
    }

    // ── Non-buffered mode (buffer = false) ──────────────────────────────

    @Test
    fun nonBufferedModeSendsDirectly() {
        val provider = FakeLocationProvider()
        val store = FakePositionStore()
        val sender = FakePositionSender()
        val controller = createController(
            provider = provider, store = store, sender = sender,
            buffer = false
        )

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        // Should send directly without storing
        assertEquals(0, store.positions.size)
        assertEquals(1, sender.sentRequests.size)
    }

    @Test
    fun nonBufferedModeLegacySend() {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        val listener = CollectingControllerListener()
        val controller = createController(
            provider = provider, sender = sender,
            serverUrl = "https://example.com/api",
            buffer = false, listener = listener
        )

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertEquals(1, sender.sentRequests.size)
        assertTrue(sender.sentRequests[0].startsWith("https://example.com/api"))
        assertEquals(1, listener.sentPositions.size)
    }

    @Test
    fun nonBufferedModeJsonSend() {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        val listener = CollectingControllerListener()
        val controller = createController(
            provider = provider, sender = sender,
            buffer = false, listener = listener,
            useJsonApi = true, apiEndpoint = "https://api.example.com/v1/location"
        )
        controller.token = "my-token"

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertEquals(1, sender.sentJsonBodies.size)
        assertEquals("my-token", sender.lastToken)
        assertEquals(1, listener.sentPositions.size)
    }

    @Test
    fun nonBufferedModeSendFailureNotifiesListener() {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        sender.sendSuccess = false
        val listener = CollectingControllerListener()
        val controller = createController(
            provider = provider, sender = sender,
            buffer = false, listener = listener
        )

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertEquals(1, listener.failedPositions.size)
        assertEquals(0, listener.sentPositions.size)
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
    fun retryScheduledOnSendFailure() {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        sender.sendSuccess = false
        val retryScheduler = FakeRetryScheduler()
        val controller = createController(
            provider = provider, sender = sender,
            retryScheduler = retryScheduler, buffer = true
        )

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertTrue(retryScheduler.scheduledActions.isNotEmpty())
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
        val controller = createController(
            provider = provider, listener = listener, buffer = true
        )

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
        val controller = createController(
            provider = provider, listener = listener, buffer = true
        )

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
        val controller = createController(
            provider = provider, listener = listener, buffer = true
        )

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
        val controller = createController(
            provider = provider, listener = listener, buffer = true
        )

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
            buffer = false, useJsonApi = true,
            apiEndpoint = "https://api.example.com/v1/location"
        )

        controller.token = "bearer-123"
        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertEquals("bearer-123", sender.lastToken)
    }

    @Test
    fun tokenCanBeChangedAtRuntime() {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        val controller = createController(
            provider = provider, sender = sender,
            buffer = false, useJsonApi = true,
            apiEndpoint = "https://api.example.com/v1/location"
        )

        controller.token = "token-1"
        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))
        assertEquals("token-1", sender.lastToken)

        controller.token = "token-2"
        provider.emit(Position(deviceId = "test-device", time = epochMs(500)))
        assertEquals("token-2", sender.lastToken)
    }

    @Test
    fun nullTokenPassedWhenNotSet() {
        val provider = FakeLocationProvider()
        val sender = FakePositionSender()
        val controller = createController(
            provider = provider, sender = sender,
            buffer = false, useJsonApi = true,
            apiEndpoint = "https://api.example.com/v1/location"
        )

        controller.start(defaultConfig)
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        assertNull(sender.lastToken)
    }

    // ── Batch sending (buffered + legacy) ───────────────────────────────

    @Test
    fun batchLegacySendsAllStoredPositions() {
        val provider = FakeLocationProvider()
        val store = FakePositionStore()
        val sender = FakePositionSender()
        val networkMonitor = FakeNetworkMonitor(online = false)
        val listener = CollectingControllerListener()
        val controller = createController(
            provider = provider, store = store, sender = sender,
            networkMonitor = networkMonitor, buffer = true,
            listener = listener
        )

        controller.start(defaultConfig)

        // Emit positions while offline
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))
        provider.emit(Position(deviceId = "test-device", time = epochMs(200)))

        assertEquals(2, store.positions.size)
        assertEquals(0, sender.sentRequests.size)

        // Go online triggers batch send
        networkMonitor.setOnline(true)

        // After going online, positions should be sent
        assertTrue(sender.sentRequests.size >= 2)
    }

    @Test
    fun batchJsonSendsAllStoredPositions() {
        val provider = FakeLocationProvider()
        val store = FakePositionStore()
        val sender = FakePositionSender()
        val networkMonitor = FakeNetworkMonitor(online = false)
        val listener = CollectingControllerListener()
        val controller = createController(
            provider = provider, store = store, sender = sender,
            networkMonitor = networkMonitor, buffer = true,
            listener = listener, useJsonApi = true,
            apiEndpoint = "https://api.example.com/v1/location"
        )
        controller.token = "test-token"

        controller.start(defaultConfig)

        // Emit positions while offline
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))
        provider.emit(Position(deviceId = "test-device", time = epochMs(200)))

        assertEquals(2, store.positions.size)
        assertEquals(0, sender.sentJsonBodies.size)

        // Go online triggers batch send
        networkMonitor.setOnline(true)

        assertTrue(sender.sentJsonBodies.size >= 2)
    }

    // ── Device ID filtering in read ─────────────────────────────────────

    @Test
    fun positionsFromDifferentDeviceDeletedNotSent() {
        val provider = FakeLocationProvider()
        val store = FakePositionStore()
        val sender = FakePositionSender()
        val networkMonitor = FakeNetworkMonitor(online = false)
        val controller = createController(
            provider = provider, store = store, sender = sender,
            networkMonitor = networkMonitor, buffer = true
        )

        controller.start(defaultConfig)

        // Manually insert a position from a different device
        store.positions.add(Position(id = 99, deviceId = "other-device", time = epochMs(50)))
        // Emit position from current device
        provider.emit(Position(deviceId = "test-device", time = epochMs(100)))

        // Go online
        networkMonitor.setOnline(true)

        // "other-device" position should be deleted, not sent
        val remainingDeviceIds = store.positions.map { it.deviceId }
        assertFalse(remainingDeviceIds.contains("other-device"))
    }

    // ── Retry on store failure ──────────────────────────────────────────

    @Test
    fun retryScheduledOnSelectFailure() {
        val provider = FakeLocationProvider()
        val store = FakePositionStore()
        val retryScheduler = FakeRetryScheduler()
        val networkMonitor = FakeNetworkMonitor(online = true)
        val controller = createController(
            provider = provider, store = store,
            retryScheduler = retryScheduler,
            networkMonitor = networkMonitor, buffer = true
        )

        // Make the first select fail
        store.selectFailNext = true
        controller.start(defaultConfig)

        // A retry should be scheduled
        assertTrue(retryScheduler.scheduledActions.isNotEmpty())
    }
}
