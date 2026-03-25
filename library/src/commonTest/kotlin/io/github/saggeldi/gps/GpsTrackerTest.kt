package io.github.saggeldi.gps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun epochMs(seconds: Long): Long = seconds * 1000

/**
 * Additional GpsTracker tests beyond the existing filter tests.
 * Covers: updateConfig, requestSingleLocation, isTracking, error forwarding,
 * status change callbacks, and edge cases.
 */
class GpsTrackerTest {

    private class TestListener : GpsTrackerListener {
        val positions = mutableListOf<Position>()
        val errors = mutableListOf<String>()
        val statusChanges = mutableListOf<TrackerStatus>()

        override fun onPositionUpdate(position: Position) { positions.add(position) }
        override fun onError(error: String) { errors.add(error) }
        override fun onStatusChange(status: TrackerStatus) { statusChanges.add(status) }
    }

    private class TestLocationProvider : PlatformLocationProvider {
        var onLocation: ((Position) -> Unit)? = null
        var onError: ((String) -> Unit)? = null
        var startCount = 0
        var stopCount = 0
        var lastConfig: GpsConfig? = null
        var singleLocationResult: Position? = null

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
            singleLocationResult?.let { onLocation(it) }
        }

        fun emit(position: Position) { onLocation?.invoke(position) }
        fun emitError(msg: String) { onError?.invoke(msg) }
    }

    // ── isTracking ──────────────────────────────────────────────────────

    @Test
    fun isTrackingFalseBeforeStart() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        assertFalse(tracker.isTracking())
    }

    @Test
    fun isTrackingTrueAfterStart() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        tracker.start(GpsConfig(deviceId = "test", interval = 300))
        assertTrue(tracker.isTracking())
    }

    @Test
    fun isTrackingFalseAfterStop() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        tracker.start(GpsConfig(deviceId = "test", interval = 300))
        tracker.stop()
        assertFalse(tracker.isTracking())
    }

    // ── currentConfig ───────────────────────────────────────────────────

    @Test
    fun currentConfigNullBeforeStart() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        assertNull(tracker.currentConfig())
    }

    @Test
    fun currentConfigReturnsConfigAfterStart() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        val config = GpsConfig(deviceId = "dev-1", interval = 60, distance = 50.0)
        tracker.start(config)
        val returned = tracker.currentConfig()
        assertNotNull(returned)
        assertEquals("dev-1", returned.deviceId)
        assertEquals(60, returned.interval)
        assertEquals(50.0, returned.distance)
    }

    // ── Status change callbacks ─────────────────────────────────────────

    @Test
    fun startEmitsStartedStatus() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        tracker.start(GpsConfig(deviceId = "test", interval = 300))
        assertEquals(1, listener.statusChanges.size)
        assertEquals(TrackerStatus.STARTED, listener.statusChanges[0])
    }

    @Test
    fun stopEmitsStoppedStatus() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        tracker.start(GpsConfig(deviceId = "test", interval = 300))
        tracker.stop()
        assertEquals(2, listener.statusChanges.size)
        assertEquals(TrackerStatus.STOPPED, listener.statusChanges[1])
    }

    @Test
    fun stopWhenNotRunningDoesNotEmitStatus() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        tracker.stop()
        assertEquals(0, listener.statusChanges.size)
    }

    @Test
    fun doubleStopOnlyEmitsOneStoppedStatus() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        tracker.start(GpsConfig(deviceId = "test", interval = 300))
        tracker.stop()
        tracker.stop()
        // STARTED + STOPPED (only once)
        assertEquals(2, listener.statusChanges.size)
    }

    @Test
    fun restartEmitsStoppedThenStarted() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        tracker.start(GpsConfig(deviceId = "test", interval = 300))
        tracker.start(GpsConfig(deviceId = "test2", interval = 60))

        // First start: STARTED, then restart: STOPPED + STARTED
        assertEquals(3, listener.statusChanges.size)
        assertEquals(TrackerStatus.STARTED, listener.statusChanges[0])
        assertEquals(TrackerStatus.STOPPED, listener.statusChanges[1])
        assertEquals(TrackerStatus.STARTED, listener.statusChanges[2])
    }

    // ── Error forwarding ────────────────────────────────────────────────

    @Test
    fun errorFromProviderForwardedToListener() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        tracker.start(GpsConfig(deviceId = "test", interval = 300))

        provider.emitError("GPS signal lost")
        assertEquals(1, listener.errors.size)
        assertEquals("GPS signal lost", listener.errors[0])
    }

    @Test
    fun multipleErrorsForwarded() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        tracker.start(GpsConfig(deviceId = "test", interval = 300))

        provider.emitError("Error 1")
        provider.emitError("Error 2")
        assertEquals(2, listener.errors.size)
    }

    // ── updateConfig ────────────────────────────────────────────────────

    @Test
    fun updateConfigWhileRunningRestartsProvider() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        tracker.start(GpsConfig(deviceId = "test", interval = 300))

        assertEquals(1, provider.startCount)
        assertEquals(0, provider.stopCount)

        tracker.updateConfig(GpsConfig(deviceId = "test", interval = 60))

        // Should stop then start again
        assertEquals(1, provider.stopCount)
        assertEquals(2, provider.startCount)
    }

    @Test
    fun updateConfigWhileStoppedDoesNotRestartProvider() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)

        tracker.updateConfig(GpsConfig(deviceId = "test", interval = 60))

        assertEquals(0, provider.startCount)
        assertEquals(0, provider.stopCount)
    }

    @Test
    fun updateConfigUpdatesCurrentConfig() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        tracker.start(GpsConfig(deviceId = "test", interval = 300))

        tracker.updateConfig(GpsConfig(deviceId = "updated", interval = 120, distance = 200.0))

        val cfg = tracker.currentConfig()
        assertNotNull(cfg)
        assertEquals("updated", cfg.deviceId)
        assertEquals(120, cfg.interval)
        assertEquals(200.0, cfg.distance)
    }

    @Test
    fun updateConfigUsesNewConfigForFiltering() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)

        // Start with long interval (99999s) - only first position accepted
        tracker.start(GpsConfig(deviceId = "test", interval = 99999))
        provider.emit(Position(deviceId = "test", time = epochMs(1000)))
        provider.emit(Position(deviceId = "test", time = epochMs(1010)))
        assertEquals(1, listener.positions.size)

        // Update to short interval (0s) - all positions accepted
        tracker.updateConfig(GpsConfig(deviceId = "test", interval = 0))
        provider.emit(Position(deviceId = "test", time = epochMs(1020)))
        provider.emit(Position(deviceId = "test", time = epochMs(1030)))
        assertEquals(3, listener.positions.size)
    }

    // ── requestSingleLocation ───────────────────────────────────────────

    @Test
    fun requestSingleLocationCallsListenerWithResult() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        val config = GpsConfig(deviceId = "test", interval = 300)
        tracker.start(config)

        provider.singleLocationResult = Position(
            deviceId = "test", time = epochMs(500),
            latitude = 40.0, longitude = -74.0
        )
        tracker.requestSingleLocation()

        // Single location bypasses filtering and goes directly to listener
        assertTrue(listener.positions.any { it.latitude == 40.0 })
    }

    @Test
    fun requestSingleLocationWithoutStartDoesNothing() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)

        provider.singleLocationResult = Position(
            deviceId = "test", time = epochMs(500)
        )
        tracker.requestSingleLocation()

        // No config set, so requestSingleLocation should return early
        assertEquals(0, listener.positions.size)
    }

    // ── Provider interaction ────────────────────────────────────────────

    @Test
    fun startCallsProviderWithConfig() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        val config = GpsConfig(
            deviceId = "my-device",
            interval = 120,
            distance = 50.0,
            angle = 15.0,
            accuracy = LocationAccuracy.HIGH
        )

        tracker.start(config)

        assertNotNull(provider.lastConfig)
        assertEquals("my-device", provider.lastConfig!!.deviceId)
        assertEquals(120, provider.lastConfig!!.interval)
        assertEquals(50.0, provider.lastConfig!!.distance)
        assertEquals(15.0, provider.lastConfig!!.angle)
        assertEquals(LocationAccuracy.HIGH, provider.lastConfig!!.accuracy)
    }

    @Test
    fun stopCallsProviderStopUpdates() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        tracker.start(GpsConfig(deviceId = "test", interval = 300))
        tracker.stop()
        assertEquals(1, provider.stopCount)
    }

    // ── Filtering edge cases ────────────────────────────────────────────

    @Test
    fun positionAfterStopNotDelivered() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        tracker.start(GpsConfig(deviceId = "test", interval = 0))

        provider.emit(Position(deviceId = "test", time = epochMs(100)))
        tracker.stop()

        // Provider callback is nulled after stop, so this should be safe
        assertEquals(1, listener.positions.size)
    }

    @Test
    fun positionFilteringWithCombinedDistanceAndAngle() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)

        tracker.start(GpsConfig(
            deviceId = "test", interval = 99999,
            distance = 100.0, angle = 30.0
        ))

        // First position always accepted
        provider.emit(Position(
            deviceId = "test", time = epochMs(1000),
            latitude = 40.0, longitude = -74.0, course = 0.0
        ))
        assertEquals(1, listener.positions.size)

        // Second: same location, small angle change -> filtered
        provider.emit(Position(
            deviceId = "test", time = epochMs(1005),
            latitude = 40.0, longitude = -74.0, course = 10.0
        ))
        assertEquals(1, listener.positions.size)

        // Third: same location, large angle change -> accepted by angle
        provider.emit(Position(
            deviceId = "test", time = epochMs(1010),
            latitude = 40.0, longitude = -74.0, course = 45.0
        ))
        assertEquals(2, listener.positions.size)
    }

    @Test
    fun zeroIntervalAcceptsAllPositions() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)
        tracker.start(GpsConfig(deviceId = "test", interval = 0))

        provider.emit(Position(deviceId = "test", time = epochMs(100)))
        provider.emit(Position(deviceId = "test", time = epochMs(100)))
        provider.emit(Position(deviceId = "test", time = epochMs(100)))

        assertEquals(3, listener.positions.size)
    }

    @Test
    fun startClearsLastPositionSoFirstNewPositionAccepted() {
        val provider = TestLocationProvider()
        val listener = TestListener()
        val tracker = GpsTracker(provider, listener)

        // First session
        tracker.start(GpsConfig(deviceId = "test", interval = 99999))
        provider.emit(Position(deviceId = "test", time = epochMs(1000)))
        assertEquals(1, listener.positions.size)

        // Restart - should clear lastPosition
        tracker.start(GpsConfig(deviceId = "test", interval = 99999))
        provider.emit(Position(deviceId = "test", time = epochMs(1001)))
        assertEquals(2, listener.positions.size)
    }
}
