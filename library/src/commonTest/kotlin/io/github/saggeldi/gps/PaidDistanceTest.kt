package io.github.saggeldi.gps

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the `accumulated_km` accumulator in [TrackingController]: it must stay equal to
 * [TrackingController.getTripDistanceKm], which sums the persisted rows, while only
 * reading them once per trip instead of once per position.
 */
class PaidDistanceTest {

    private class InMemoryDao : PositionDao {
        val rows = mutableListOf<PositionEntity>()

        override suspend fun insert(position: PositionEntity): Long {
            rows.add(position.copy(id = rows.size + 1L))
            return rows.size.toLong()
        }

        override suspend fun getAll(): List<PositionEntity> = rows.sortedByDescending { it.time }

        override suspend fun getByTrip(tripId: String): List<PositionEntity> =
            rows.filter { it.tripId == tripId }.sortedBy { it.time }

        override suspend fun getByTripAndStatus(tripId: String, status: String): List<PositionEntity> =
            rows.filter { it.tripId == tripId && it.tripStatus == status }.sortedBy { it.time }

        override suspend fun deleteByTrip(tripId: String) {
            rows.removeAll { it.tripId == tripId }
        }

        override suspend fun deleteAll() = rows.clear()

        override suspend fun deleteUntaggedBefore(before: Long) {
            rows.removeAll { it.tripId == null && it.time < before }
        }
    }

    private class RecordingSender : PositionSender {
        val bodies = mutableListOf<String>()
        override fun sendPosition(request: String, onComplete: (Boolean) -> Unit) = onComplete(true)
        override fun sendPositions(requests: List<String>, onComplete: (List<Boolean>) -> Unit) =
            onComplete(requests.map { true })

        override fun sendJsonPost(
            url: String,
            jsonBody: String,
            token: String?,
            onComplete: (status: Int?, body: String?) -> Unit
        ) {
            bodies.add(jsonBody)
            onComplete(200, null)
        }
    }

    private class ManualProvider : PlatformLocationProvider {
        private var onLocation: ((Position) -> Unit)? = null
        override fun startUpdates(
            config: GpsConfig,
            onLocation: (Position) -> Unit,
            onError: (String) -> Unit
        ) {
            this.onLocation = onLocation
        }

        override fun stopUpdates() { onLocation = null }
        override fun requestSingleLocation(config: GpsConfig, onLocation: (Position) -> Unit) {}
        fun emit(position: Position) = onLocation?.invoke(position)
    }

    private class AlwaysOnline : NetworkMonitor {
        override val isOnline = true
        override fun start(onNetworkChange: (Boolean) -> Unit) {}
        override fun stop() {}
    }

    /**
     * A monitor that only learns its state in [start] and reports no-change events —
     * the shape of the real platform monitors. If the controller samples [isOnline]
     * before starting it, every position is silently dropped.
     */
    private class OnlineOnlyAfterStart : NetworkMonitor {
        private var started = false
        override val isOnline: Boolean get() = started
        override fun start(onNetworkChange: (Boolean) -> Unit) { started = true }
        override fun stop() { started = false }
    }

    /** A short north-bound track; consecutive points are ~110 m apart. */
    private fun track(count: Int, startSecond: Long = 0): List<Position> = (0 until count).map { i ->
        Position(
            deviceId = "d",
            time = (startSecond + i) * 4000L, // ~28 m/s: the meter drops impossible speeds
            latitude = 37.0 + i * 0.001,
            longitude = 58.0
        )
    }

    private fun accumulatedKm(jsonBody: String): Double =
        Regex(""""accumulated_km":([0-9.eE-]+)""").find(jsonBody)
            ?.groupValues?.get(1)?.toDouble()
            ?: error("no accumulated_km in $jsonBody")

    /** dbScope is a real background dispatcher, so wait for it rather than for virtual time. */
    private suspend fun awaitBodies(sender: RecordingSender, count: Int) =
        withContext(Dispatchers.Default) {
            repeat(200) {
                if (sender.bodies.size >= count) return@withContext
                delay(10)
            }
            error("only ${sender.bodies.size} of $count bodies were sent")
        }

    @Test
    fun accumulatedKmMatchesStoredRowsAndOnlyCountsPaidSegment() = runTest {
        val dao = InMemoryDao()
        val sender = RecordingSender()
        val provider = ManualProvider()
        val controller = TrackingController(
            locationProvider = provider,
            positionSender = sender,
            networkMonitor = AlwaysOnline(),
            serverUrl = "https://example.com",
            useJsonApi = true,
            apiEndpoint = "https://example.com/trips/driver-location",
            positionDao = dao
        )
        controller.start(GpsConfig(deviceId = "d", interval = 0))
        controller.startTrip("t1", "on_the_way")

        // Driving to pickup is unpaid: nothing accumulates.
        track(count = 3).forEach { provider.emit(it) }
        awaitBodies(sender, 3)
        assertTrue(sender.bodies.all { accumulatedKm(it) == 0.0 }, "unpaid legs must not accumulate")

        controller.updateTripStatus("on_the_trip")
        track(count = 5, startSecond = 10).forEach { provider.emit(it) }
        awaitBodies(sender, 8)

        val reported = accumulatedKm(sender.bodies.last())
        assertEquals(controller.getTripDistanceKm("t1"), reported, absoluteTolerance = 1e-9)
        assertTrue(reported > 0.4, "5 legs of ~110 m should exceed 0.4 km, was $reported")

        // Strictly increasing across the paid segment.
        val paid = sender.bodies.drop(3).map { accumulatedKm(it) }
        assertEquals(paid.sorted(), paid)
        assertEquals(0.0, paid.first(), "the first paid fix has no leg behind it yet")
    }

    @Test
    fun paidDistanceResumesFromStoredRowsAfterRestart() = runTest {
        val dao = InMemoryDao()
        val first = RecordingSender()
        val provider = ManualProvider()
        val original = TrackingController(
            locationProvider = provider,
            positionSender = first,
            networkMonitor = AlwaysOnline(),
            serverUrl = "https://example.com",
            useJsonApi = true,
            apiEndpoint = "https://example.com/trips/driver-location",
            positionDao = dao
        )
        original.start(GpsConfig(deviceId = "d", interval = 0))
        original.startTrip("t1", "on_the_trip")
        track(count = 4).forEach { provider.emit(it) }
        awaitBodies(first, 4)
        val before = accumulatedKm(first.bodies.last())
        assertTrue(before > 0.0)
        original.stop()

        // Same trip, same rows, brand new controller — as after the service is recreated.
        val second = RecordingSender()
        val resumedProvider = ManualProvider()
        val resumed = TrackingController(
            locationProvider = resumedProvider,
            positionSender = second,
            networkMonitor = AlwaysOnline(),
            serverUrl = "https://example.com",
            useJsonApi = true,
            apiEndpoint = "https://example.com/trips/driver-location",
            positionDao = dao
        )
        resumed.start(GpsConfig(deviceId = "d", interval = 0))
        resumed.startTrip("t1", "on_the_trip")
        track(count = 1, startSecond = 20).forEach { resumedProvider.emit(it) }
        awaitBodies(second, 1)

        val after = accumulatedKm(second.bodies.last())
        assertTrue(after > before, "resumed trip restarted from $before instead of carrying on")
        assertEquals(resumed.getTripDistanceKm("t1"), after, absoluteTolerance = 1e-9)
    }

    @Test
    fun positionsAreSentWhenTheMonitorOnlyResolvesInStart() = runTest {
        val sender = RecordingSender()
        val provider = ManualProvider()
        val controller = TrackingController(
            locationProvider = provider,
            positionSender = sender,
            networkMonitor = OnlineOnlyAfterStart(),
            serverUrl = "https://example.com",
            useJsonApi = true,
            apiEndpoint = "https://example.com/trips/driver-location",
            positionDao = InMemoryDao()
        )
        controller.start(GpsConfig(deviceId = "d", interval = 0))
        controller.startTrip("t1", "on_the_trip")
        track(count = 2).forEach { provider.emit(it) }

        awaitBodies(sender, 2)
    }

    @Test
    fun endTripClearsTheAccumulator() = runTest {
        val dao = InMemoryDao()
        val sender = RecordingSender()
        val provider = ManualProvider()
        val controller = TrackingController(
            locationProvider = provider,
            positionSender = sender,
            networkMonitor = AlwaysOnline(),
            serverUrl = "https://example.com",
            useJsonApi = true,
            apiEndpoint = "https://example.com/trips/driver-location",
            positionDao = dao
        )
        controller.start(GpsConfig(deviceId = "d", interval = 0))
        controller.startTrip("t1", "on_the_trip")
        track(count = 4).forEach { provider.emit(it) }
        awaitBodies(sender, 4)
        assertTrue(accumulatedKm(sender.bodies.last()) > 0.0)

        controller.endTrip()

        // endTrip() purges the trip's rows, so the same id must start from zero again.
        controller.startTrip("t1", "on_the_trip")
        provider.emit(track(count = 1, startSecond = 40).first())
        awaitBodies(sender, 5)
        assertEquals(0.0, accumulatedKm(sender.bodies.last()))
    }

    @Test
    fun stopThenStartKeepsPersisting() = runTest {
        val dao = InMemoryDao()
        val sender = RecordingSender()
        val provider = ManualProvider()
        val controller = TrackingController(
            locationProvider = provider,
            positionSender = sender,
            networkMonitor = AlwaysOnline(),
            serverUrl = "https://example.com",
            useJsonApi = true,
            apiEndpoint = "https://example.com/trips/driver-location",
            positionDao = dao
        )
        controller.start(GpsConfig(deviceId = "d", interval = 0))
        controller.startTrip("t1", "on_the_trip")
        provider.emit(track(count = 1).first())
        awaitBodies(sender, 1)

        controller.stop()
        controller.start(GpsConfig(deviceId = "d", interval = 0))
        track(count = 2, startSecond = 30).forEach { provider.emit(it) }
        awaitBodies(sender, 3)

        assertEquals(3, dao.rows.size, "inserts stopped after a stop()/start() cycle")
    }
}
