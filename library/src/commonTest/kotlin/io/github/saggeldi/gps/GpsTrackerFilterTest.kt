package io.github.saggeldi.gps

import kotlin.test.Test
import kotlin.test.assertEquals

private fun epochSeconds(seconds: Long): Long = seconds * 1000

class GpsTrackerFilterTest {

    private class CollectingListener : GpsTrackerListener {
        val positions = mutableListOf<Position>()
        override fun onPositionUpdate(position: Position) { positions.add(position) }
        override fun onError(error: String) {}
        override fun onStatusChange(status: TrackerStatus) {}
    }

    private class FakeLocationProvider : PlatformLocationProvider {
        var onLocation: ((Position) -> Unit)? = null
        override fun startUpdates(config: GpsConfig, onLocation: (Position) -> Unit, onError: (String) -> Unit) {
            this.onLocation = onLocation
        }
        override fun stopUpdates() { onLocation = null }
        override fun requestSingleLocation(config: GpsConfig, onLocation: (Position) -> Unit) {}

        fun emit(position: Position) { onLocation?.invoke(position) }
    }

    @Test
    fun testFirstPositionAlwaysAccepted() {
        val provider = FakeLocationProvider()
        val listener = CollectingListener()
        val tracker = GpsTracker(provider, listener)

        tracker.start(GpsConfig(deviceId = "test", interval = 300))

        provider.emit(Position(deviceId = "test", time = epochSeconds(1000)))
        assertEquals(1, listener.positions.size)
    }

    @Test
    fun testPositionFilteredByInterval() {
        val provider = FakeLocationProvider()
        val listener = CollectingListener()
        val tracker = GpsTracker(provider, listener)

        tracker.start(GpsConfig(deviceId = "test", interval = 300))

        provider.emit(Position(deviceId = "test", time = epochSeconds(1000)))
        provider.emit(Position(deviceId = "test", time = epochSeconds(1010)))
        provider.emit(Position(deviceId = "test", time = epochSeconds(1300)))

        assertEquals(2, listener.positions.size)
    }

    /**
     * The platform providers are configured with the same period as
     * [GpsConfig.interval], so fixes land right on the boundary and routinely
     * arrive a few milliseconds early. Those must still be accepted — rejecting
     * them pushed the next accepted fix a full interval out, halving the update
     * rate the passenger's map actually receives.
     */
    @Test
    fun testPositionArrivingFractionallyEarlyIsAccepted() {
        val provider = FakeLocationProvider()
        val listener = CollectingListener()
        val tracker = GpsTracker(provider, listener)

        tracker.start(GpsConfig(deviceId = "test", interval = 5))

        provider.emit(Position(deviceId = "test", time = 1_000_000))
        provider.emit(Position(deviceId = "test", time = 1_004_999))
        provider.emit(Position(deviceId = "test", time = 1_009_998))

        assertEquals(3, listener.positions.size)
    }

    /** A fix genuinely inside the interval is still filtered out. */
    @Test
    fun testPositionWellInsideIntervalIsRejected() {
        val provider = FakeLocationProvider()
        val listener = CollectingListener()
        val tracker = GpsTracker(provider, listener)

        tracker.start(GpsConfig(deviceId = "test", interval = 5))

        provider.emit(Position(deviceId = "test", time = 1_000_000))
        provider.emit(Position(deviceId = "test", time = 1_003_000))

        assertEquals(1, listener.positions.size)
    }

    @Test
    fun testPositionAcceptedByDistance() {
        val provider = FakeLocationProvider()
        val listener = CollectingListener()
        val tracker = GpsTracker(provider, listener)

        tracker.start(GpsConfig(deviceId = "test", interval = 99999, distance = 100.0))

        provider.emit(Position(deviceId = "test", time = epochSeconds(1000),
            latitude = 40.0, longitude = -74.0))
        provider.emit(Position(deviceId = "test", time = epochSeconds(1005),
            latitude = 40.0, longitude = -74.0))
        provider.emit(Position(deviceId = "test", time = epochSeconds(1010),
            latitude = 41.0, longitude = -74.0))

        assertEquals(2, listener.positions.size)
    }

    @Test
    fun testPositionAcceptedByAngle() {
        val provider = FakeLocationProvider()
        val listener = CollectingListener()
        val tracker = GpsTracker(provider, listener)

        tracker.start(GpsConfig(deviceId = "test", interval = 99999, angle = 30.0))

        provider.emit(Position(deviceId = "test", time = epochSeconds(1000), course = 0.0))
        provider.emit(Position(deviceId = "test", time = epochSeconds(1005), course = 10.0))
        provider.emit(Position(deviceId = "test", time = epochSeconds(1010), course = 45.0))

        assertEquals(2, listener.positions.size)
    }

    @Test
    fun testStopPreventsUpdates() {
        val provider = FakeLocationProvider()
        val listener = CollectingListener()
        val tracker = GpsTracker(provider, listener)

        tracker.start(GpsConfig(deviceId = "test", interval = 0))

        provider.emit(Position(deviceId = "test", time = epochSeconds(1000)))
        tracker.stop()

        assertEquals(1, listener.positions.size)
    }
}
