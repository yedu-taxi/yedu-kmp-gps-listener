package io.github.saggeldi.gps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripTrackerTest {

    private fun pos(
        lat: Double = 0.0,
        lon: Double = 0.0,
        time: Long = 1000000L
    ) = Position(deviceId = "test", time = time, latitude = lat, longitude = lon)

    // ── Trip lifecycle ──────────────────────────────────────────────────

    @Test
    fun initialStateIsInactive() {
        val tracker = TripTracker()
        assertFalse(tracker.isActive)
        assertNull(tracker.tripId)
        assertNull(tracker.status)
    }

    @Test
    fun startTripSetsActiveState() {
        val tracker = TripTracker()
        tracker.startTrip("trip-1", "en_route")
        assertTrue(tracker.isActive)
        assertEquals("trip-1", tracker.tripId)
        assertEquals("en_route", tracker.status)
    }

    @Test
    fun endTripClearsState() {
        val tracker = TripTracker()
        tracker.startTrip("trip-1", "en_route")
        tracker.endTrip()
        assertFalse(tracker.isActive)
        assertNull(tracker.tripId)
        assertNull(tracker.status)
    }

    @Test
    fun updateStatusChangesStatus() {
        val tracker = TripTracker()
        tracker.startTrip("trip-1", "en_route")
        assertEquals("en_route", tracker.status)
        tracker.updateStatus("arrived")
        assertEquals("arrived", tracker.status)
        assertEquals("trip-1", tracker.tripId)
    }

    @Test
    fun updateStatusMultipleTimes() {
        val tracker = TripTracker()
        tracker.startTrip("trip-1", "pickup")
        tracker.updateStatus("en_route")
        tracker.updateStatus("arrived")
        tracker.updateStatus("completed")
        assertEquals("completed", tracker.status)
    }

    // ── Distance tracking ───────────────────────────────────────────────

    @Test
    fun updatePositionIgnoredWhenNoActiveTrip() {
        val tracker = TripTracker()
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0))
        val stats = tracker.getTripStats()
        assertEquals(0.0, stats.totalDistanceKm)
    }

    @Test
    fun singlePositionAccumulatesZeroDistance() {
        val tracker = TripTracker()
        tracker.startTrip("trip-1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0))
        val stats = tracker.getTripStats()
        assertEquals(0.0, stats.totalDistanceKm)
    }

    @Test
    fun twoPositionsAccumulateDistance() {
        val tracker = TripTracker()
        tracker.startTrip("trip-1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0))
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0))

        val stats = tracker.getTripStats()
        // ~111 km between these two points (1 degree of latitude)
        assertTrue(stats.totalDistanceKm > 100.0)
        assertTrue(stats.totalDistanceKm < 120.0)
    }

    @Test
    fun multiplePositionsAccumulateTotalDistance() {
        val tracker = TripTracker()
        tracker.startTrip("trip-1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0))
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0))
        tracker.updatePosition(pos(lat = 42.0, lon = -74.0))

        val stats = tracker.getTripStats()
        // ~222 km total (2 segments of ~111 km)
        assertTrue(stats.totalDistanceKm > 200.0)
        assertTrue(stats.totalDistanceKm < 240.0)
    }

    @Test
    fun samePositionRepeatedAccumulatesZero() {
        val tracker = TripTracker()
        tracker.startTrip("trip-1", "en_route")
        repeat(5) {
            tracker.updatePosition(pos(lat = 40.0, lon = -74.0))
        }
        val stats = tracker.getTripStats()
        assertTrue(stats.totalDistanceKm < 0.001)
    }

    // ── Per-status distance tracking ────────────────────────────────────

    @Test
    fun statusDistanceAccumulatedSeparately() {
        val tracker = TripTracker()
        tracker.startTrip("trip-1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0))
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0))

        val stats = tracker.getTripStats()
        // Status distance should equal total distance (same status)
        assertTrue(stats.statusDistanceKm > 100.0)
        assertTrue(stats.statusDistanceKm < 120.0)
    }

    @Test
    fun statusChangeResetsStatusDistance() {
        val tracker = TripTracker()
        tracker.startTrip("trip-1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0))
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0))

        tracker.updateStatus("arrived")

        tracker.updatePosition(pos(lat = 41.0, lon = -74.0))
        tracker.updatePosition(pos(lat = 41.5, lon = -74.0))

        val stats = tracker.getTripStats()
        // Total should include all distance
        assertTrue(stats.totalDistanceKm > 150.0)
        // Status distance should only include post-status-change distance (~55 km)
        assertTrue(stats.statusDistanceKm > 50.0)
        assertTrue(stats.statusDistanceKm < 70.0)
    }

    @Test
    fun statusChangeDoesNotResetTotalDistance() {
        val tracker = TripTracker()
        tracker.startTrip("trip-1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0))
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0))

        val distBeforeStatusChange = tracker.getTripStats().totalDistanceKm

        tracker.updateStatus("arrived")
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0))
        tracker.updatePosition(pos(lat = 42.0, lon = -74.0))

        val distAfter = tracker.getTripStats().totalDistanceKm
        assertTrue(distAfter > distBeforeStatusChange)
    }

    // ── TripStats fields ────────────────────────────────────────────────

    @Test
    fun getTripStatsReturnsTripIdAndStatus() {
        val tracker = TripTracker()
        tracker.startTrip("trip-42", "pickup")
        val stats = tracker.getTripStats()
        assertEquals("trip-42", stats.tripId)
        assertEquals("pickup", stats.status)
    }

    @Test
    fun getTripStatsTimeIsNonNegative() {
        val tracker = TripTracker()
        tracker.startTrip("trip-1", "en_route")
        val stats = tracker.getTripStats()
        assertTrue(stats.totalTimeSeconds >= 0)
        assertTrue(stats.statusTimeSeconds >= 0)
    }

    @Test
    fun getTripStatsBeforeTripStartReturnsZeroes() {
        val tracker = TripTracker()
        val stats = tracker.getTripStats()
        assertNull(stats.tripId)
        assertNull(stats.status)
        assertEquals(0.0, stats.totalDistanceKm)
        assertEquals(0.0, stats.statusDistanceKm)
    }

    // ── Listener callbacks ──────────────────────────────────────────────

    @Test
    fun listenerCalledOnPositionUpdate() {
        val tracker = TripTracker()
        var callCount = 0
        var lastStats: TripStats? = null
        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) {
                callCount++
                lastStats = stats
            }
            override fun onTripEnded(stats: TripStats) {}
        })

        tracker.startTrip("trip-1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0))
        assertEquals(1, callCount)
        assertNotNull(lastStats)
        assertEquals("trip-1", lastStats!!.tripId)
    }

    @Test
    fun listenerCalledOnEachPositionUpdate() {
        val tracker = TripTracker()
        var callCount = 0
        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) { callCount++ }
            override fun onTripEnded(stats: TripStats) {}
        })

        tracker.startTrip("trip-1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0))
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0))
        tracker.updatePosition(pos(lat = 42.0, lon = -74.0))
        assertEquals(3, callCount)
    }

    @Test
    fun listenerCalledOnEndTrip() {
        val tracker = TripTracker()
        var endedStats: TripStats? = null
        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) {}
            override fun onTripEnded(stats: TripStats) { endedStats = stats }
        })

        tracker.startTrip("trip-1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0))
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0))
        tracker.endTrip()

        assertNotNull(endedStats)
        assertEquals("trip-1", endedStats!!.tripId)
        assertTrue(endedStats!!.totalDistanceKm > 100.0)
    }

    @Test
    fun listenerNotCalledWhenPositionUpdateWithoutTrip() {
        val tracker = TripTracker()
        var callCount = 0
        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) { callCount++ }
            override fun onTripEnded(stats: TripStats) {}
        })

        tracker.updatePosition(pos(lat = 40.0, lon = -74.0))
        assertEquals(0, callCount)
    }

    @Test
    fun setListenerNullRemovesListener() {
        val tracker = TripTracker()
        var callCount = 0
        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) { callCount++ }
            override fun onTripEnded(stats: TripStats) {}
        })

        tracker.startTrip("trip-1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0))
        assertEquals(1, callCount)

        tracker.setListener(null)
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0))
        assertEquals(1, callCount) // no new call
    }

    // ── Trip restart ────────────────────────────────────────────────────

    @Test
    fun startNewTripAfterEndResetsDistance() {
        val tracker = TripTracker()
        tracker.startTrip("trip-1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0))
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0))
        tracker.endTrip()

        tracker.startTrip("trip-2", "en_route")
        val stats = tracker.getTripStats()
        assertEquals("trip-2", stats.tripId)
        assertEquals(0.0, stats.totalDistanceKm)
        assertEquals(0.0, stats.statusDistanceKm)
    }

    @Test
    fun startTripWhileActiveOverridesPrevious() {
        val tracker = TripTracker()
        tracker.startTrip("trip-1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0))
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0))

        tracker.startTrip("trip-2", "pickup")
        assertEquals("trip-2", tracker.tripId)
        assertEquals("pickup", tracker.status)
        assertEquals(0.0, tracker.getTripStats().totalDistanceKm)
    }

    // ══════════════════════════════════════════════════════════════════════
    // NEW TESTS: Per-status history
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun multiStatusAccumulation() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })

        fakeTime = 10_000L
        tracker.startTrip("t1", "accepted")

        // Drive ~111 km in "accepted" over 60 seconds
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = fakeTime))
        fakeTime = 70_000L
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0, time = fakeTime))

        // Switch to "arrived"
        fakeTime = 70_000L
        tracker.updateStatus("arrived")

        // Drive ~55 km in "arrived" over 30 seconds
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0, time = fakeTime))
        fakeTime = 100_000L
        tracker.updatePosition(pos(lat = 41.5, lon = -74.0, time = fakeTime))

        val stats = tracker.getTripStats()
        val accepted = stats.statsForStatus("accepted")
        val arrived = stats.statsForStatus("arrived")

        assertNotNull(accepted)
        assertNotNull(arrived)
        assertTrue(accepted!!.distanceKm > 100.0)
        assertTrue(accepted.distanceKm < 120.0)
        assertEquals(60L, accepted.timeSeconds)

        assertTrue(arrived!!.distanceKm > 50.0)
        assertTrue(arrived.distanceKm < 70.0)
    }

    @Test
    fun revisitedStatusAccumulates() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })

        fakeTime = 0L
        tracker.startTrip("t1", "driving")

        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = fakeTime))
        fakeTime = 10_000L
        tracker.updatePosition(pos(lat = 40.001, lon = -74.0, time = fakeTime))

        // Switch away
        fakeTime = 10_000L
        tracker.updateStatus("waiting")
        fakeTime = 20_000L
        tracker.updateStatus("driving") // back to driving

        tracker.updatePosition(pos(lat = 40.001, lon = -74.0, time = fakeTime))
        fakeTime = 30_000L
        tracker.updatePosition(pos(lat = 40.002, lon = -74.0, time = fakeTime))

        val stats = tracker.getTripStats()
        val driving = stats.statsForStatus("driving")
        assertNotNull(driving)
        // Should accumulate both segments' time: 10s + 10s = 20s
        assertEquals(20L, driving!!.timeSeconds)
        // Distance should be sum of both driving segments
        assertTrue(driving.distanceMeters > 0.0)
    }

    @Test
    fun liveStatusMergedIntoHistory() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })

        fakeTime = 0L
        tracker.startTrip("t1", "active")

        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = fakeTime))
        fakeTime = 5_000L
        tracker.updatePosition(pos(lat = 40.001, lon = -74.0, time = fakeTime))

        // Don't change status - "active" is still live
        val stats = tracker.getTripStats()
        val active = stats.statsForStatus("active")
        assertNotNull(active)
        assertEquals(5L, active!!.timeSeconds)
        assertTrue(active.distanceMeters > 0.0)
    }

    @Test
    fun endTripFinalizesCurrentStatusInHistory() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })
        var endedStats: TripStats? = null
        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) {}
            override fun onTripEnded(stats: TripStats) { endedStats = stats }
        })

        fakeTime = 0L
        tracker.startTrip("t1", "in_progress")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = fakeTime))
        fakeTime = 30_000L
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0, time = fakeTime))

        fakeTime = 30_000L
        tracker.endTrip()

        assertNotNull(endedStats)
        val inProgress = endedStats!!.statsForStatus("in_progress")
        assertNotNull(inProgress)
        assertTrue(inProgress!!.distanceKm > 100.0)
        assertEquals(30L, inProgress.timeSeconds)
    }

    @Test
    fun statusHistoryEmptyBeforeAnyStatusChange() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })

        fakeTime = 0L
        tracker.startTrip("t1", "initial")
        fakeTime = 5_000L
        val stats = tracker.getTripStats()
        // The live status "initial" should be in history
        val initial = stats.statsForStatus("initial")
        assertNotNull(initial)
        assertEquals(5L, initial!!.timeSeconds)
    }

    // ══════════════════════════════════════════════════════════════════════
    // NEW TESTS: Enhanced total fields
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun totalDistanceMetersMatchesKmTimes1000() {
        val tracker = TripTracker()
        tracker.startTrip("t1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0))
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0))

        val stats = tracker.getTripStats()
        val diff = kotlin.math.abs(stats.totalDistanceMeters - stats.totalDistanceKm * 1000.0)
        assertTrue(diff < 0.001, "totalDistanceMeters should equal totalDistanceKm * 1000")
    }

    @Test
    fun totalTimeMinutesMatchesSecondsDividedBy60() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })

        fakeTime = 0L
        tracker.startTrip("t1", "en_route")
        fakeTime = 120_000L // 2 minutes

        val stats = tracker.getTripStats()
        assertEquals(120L, stats.totalTimeSeconds)
        assertEquals(2.0, stats.totalTimeMinutes)
    }

    @Test
    fun statsForStatusReturnsNullForUnknown() {
        val tracker = TripTracker()
        tracker.startTrip("t1", "en_route")
        val stats = tracker.getTripStats()
        assertNull(stats.statsForStatus("nonexistent"))
    }

    // ══════════════════════════════════════════════════════════════════════
    // NEW TESTS: GPS pause detection
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun pauseTriggeredAfterThreshold() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })
        tracker.pauseTimeThresholdSeconds = 10L
        tracker.pauseDistanceThresholdMeters = 2.0

        var paused = false
        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) {}
            override fun onTripEnded(stats: TripStats) {}
            override fun onDriverPaused() { paused = true }
        })

        fakeTime = 0L
        tracker.startTrip("t1", "en_route")
        // First position sets anchor
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 0L))
        assertFalse(paused)

        // Same position 5 seconds later - not yet paused
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 5_000L))
        assertFalse(paused)

        // Same position 10 seconds after anchor - triggers pause
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 10_000L))
        assertTrue(paused)
    }

    @Test
    fun noPauseBeforeThreshold() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })
        tracker.pauseTimeThresholdSeconds = 20L

        var paused = false
        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) {}
            override fun onTripEnded(stats: TripStats) {}
            override fun onDriverPaused() { paused = true }
        })

        fakeTime = 0L
        tracker.startTrip("t1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 0L))
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 15_000L))
        assertFalse(paused, "Should not pause before threshold")
    }

    @Test
    fun resumeOnMovement() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })
        tracker.pauseTimeThresholdSeconds = 5L
        tracker.pauseDistanceThresholdMeters = 2.0

        var paused = false
        var resumed = false
        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) {}
            override fun onTripEnded(stats: TripStats) {}
            override fun onDriverPaused() { paused = true }
            override fun onDriverResumed() { resumed = true }
        })

        fakeTime = 0L
        tracker.startTrip("t1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 0L))
        // Trigger pause
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 6_000L))
        assertTrue(paused)
        assertFalse(resumed)

        // Move significantly -> resume
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0, time = 10_000L))
        assertTrue(resumed)
    }

    @Test
    fun multiplePauseResumeCycles() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })
        tracker.pauseTimeThresholdSeconds = 5L
        tracker.pauseDistanceThresholdMeters = 2.0

        var pauseCount = 0
        var resumeCount = 0
        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) {}
            override fun onTripEnded(stats: TripStats) {}
            override fun onDriverPaused() { pauseCount++ }
            override fun onDriverResumed() { resumeCount++ }
        })

        fakeTime = 0L
        tracker.startTrip("t1", "en_route")
        // Cycle 1: move -> stop -> pause -> move -> resume
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 0L))
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 6_000L))
        assertEquals(1, pauseCount)
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0, time = 10_000L))
        assertEquals(1, resumeCount)

        // Cycle 2: stop again -> pause -> move -> resume
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0, time = 16_000L))
        assertEquals(2, pauseCount)
        tracker.updatePosition(pos(lat = 42.0, lon = -74.0, time = 20_000L))
        assertEquals(2, resumeCount)
    }

    @Test
    fun totalStoppingSecondsAccumulates() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })
        tracker.pauseTimeThresholdSeconds = 5L
        tracker.pauseDistanceThresholdMeters = 2.0

        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) {}
            override fun onTripEnded(stats: TripStats) {}
        })

        fakeTime = 0L
        tracker.startTrip("t1", "en_route")

        // Stop for a period
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 0L))
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 6_000L)) // pause at t=6s
        // Resume after 4 more seconds
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0, time = 10_000L)) // resume at t=10s

        // Stopping was from pause (t=6s) to resume (t=10s) = 4 seconds
        fakeTime = 10_000L
        val stats = tracker.getTripStats()
        assertEquals(4L, stats.totalStoppingSeconds)
    }

    @Test
    fun isPausedFlagInStats() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })
        tracker.pauseTimeThresholdSeconds = 5L

        fakeTime = 0L
        tracker.startTrip("t1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 0L))

        fakeTime = 3_000L
        assertFalse(tracker.getTripStats().isPaused)

        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 6_000L))
        fakeTime = 6_000L
        assertTrue(tracker.getTripStats().isPaused)
    }

    @Test
    fun configurableThresholds() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })
        tracker.pauseTimeThresholdSeconds = 30L
        tracker.pauseDistanceThresholdMeters = 50.0

        var paused = false
        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) {}
            override fun onTripEnded(stats: TripStats) {}
            override fun onDriverPaused() { paused = true }
        })

        fakeTime = 0L
        tracker.startTrip("t1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 0L))
        // 20 seconds: below 30s threshold
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 20_000L))
        assertFalse(paused)

        // 31 seconds: triggers pause
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 31_000L))
        assertTrue(paused)
    }

    @Test
    fun smallSubThresholdMovementsDoNotResetAnchor() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })
        tracker.pauseTimeThresholdSeconds = 10L
        tracker.pauseDistanceThresholdMeters = 100.0 // 100m threshold

        var paused = false
        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) {}
            override fun onTripEnded(stats: TripStats) {}
            override fun onDriverPaused() { paused = true }
        })

        fakeTime = 0L
        tracker.startTrip("t1", "en_route")
        // Initial position
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 0L))
        // Small movement (< 100m) at 5s
        tracker.updatePosition(pos(lat = 40.00005, lon = -74.0, time = 5_000L))
        assertFalse(paused)
        // Another small movement at 11s — should trigger pause since anchor is still the original point
        tracker.updatePosition(pos(lat = 40.00005, lon = -74.0, time = 11_000L))
        assertTrue(paused, "Should pause because total dwell from anchor exceeds threshold")
    }

    // ══════════════════════════════════════════════════════════════════════
    // NEW TESTS: Edge cases
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun noMovementFromStart() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })
        tracker.pauseTimeThresholdSeconds = 5L

        var paused = false
        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) {}
            override fun onTripEnded(stats: TripStats) {}
            override fun onDriverPaused() { paused = true }
        })

        fakeTime = 0L
        tracker.startTrip("t1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 0L))
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 6_000L))
        assertTrue(paused)

        fakeTime = 6_000L
        val stats = tracker.getTripStats()
        assertEquals(0.0, stats.totalDistanceKm)
        assertTrue(stats.isPaused)
    }

    @Test
    fun instantStatusChangePreservesHistory() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })

        fakeTime = 0L
        tracker.startTrip("t1", "a")
        tracker.updateStatus("b")
        tracker.updateStatus("c")

        fakeTime = 0L
        val stats = tracker.getTripStats()
        // All three statuses should be in history with 0 time and 0 distance
        assertNotNull(stats.statsForStatus("a"))
        assertNotNull(stats.statsForStatus("b"))
        assertNotNull(stats.statsForStatus("c"))
        assertEquals(0L, stats.statsForStatus("a")!!.timeSeconds)
        assertEquals(0L, stats.statsForStatus("b")!!.timeSeconds)
    }

    @Test
    fun endTripWhilePausedFinalizesStoppingTime() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })
        tracker.pauseTimeThresholdSeconds = 5L

        var endedStats: TripStats? = null
        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) {}
            override fun onTripEnded(stats: TripStats) { endedStats = stats }
        })

        fakeTime = 0L
        tracker.startTrip("t1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 0L))
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 6_000L)) // pauses at t=6s

        fakeTime = 16_000L // 10 more seconds pass
        tracker.endTrip()

        assertNotNull(endedStats)
        // Stopping: from pause (t=6s) to end (t=16s) = 10 seconds
        assertEquals(10L, endedStats!!.totalStoppingSeconds)
    }

    @Test
    fun restartResetsAllNewState() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })
        tracker.pauseTimeThresholdSeconds = 5L

        fakeTime = 0L
        tracker.startTrip("t1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 0L))
        fakeTime = 10_000L
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 10_000L))
        // Should be paused now

        // Start new trip
        fakeTime = 20_000L
        tracker.startTrip("t2", "pickup")
        val stats = tracker.getTripStats()

        assertFalse(stats.isPaused)
        assertEquals(0L, stats.totalStoppingSeconds)
        assertTrue(stats.statusHistory.isEmpty() || stats.statusHistory.all { it.value.distanceMeters == 0.0 })
        assertEquals(0.0, stats.totalDistanceMeters)
    }

    @Test
    fun positionsWithoutActiveTripDoNotAffectPauseState() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })
        tracker.pauseTimeThresholdSeconds = 5L

        var paused = false
        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) {}
            override fun onTripEnded(stats: TripStats) {}
            override fun onDriverPaused() { paused = true }
        })

        // Send positions without starting trip
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 0L))
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 10_000L))
        assertFalse(paused, "Should not trigger pause without active trip")
    }

    // ══════════════════════════════════════════════════════════════════════
    // NEW TESTS: Listener verification
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun pauseCallbackFiredExactlyOnce() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })
        tracker.pauseTimeThresholdSeconds = 5L

        var pauseCount = 0
        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) {}
            override fun onTripEnded(stats: TripStats) {}
            override fun onDriverPaused() { pauseCount++ }
        })

        fakeTime = 0L
        tracker.startTrip("t1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 0L))
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 6_000L)) // triggers pause
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 12_000L)) // still paused
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 18_000L)) // still paused

        assertEquals(1, pauseCount, "onDriverPaused should fire exactly once per pause period")
    }

    @Test
    fun statsContainHistoryDuringUpdates() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })

        var lastStats: TripStats? = null
        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) { lastStats = stats }
            override fun onTripEnded(stats: TripStats) {}
        })

        fakeTime = 0L
        tracker.startTrip("t1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = fakeTime))
        fakeTime = 60_000L
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0, time = fakeTime))

        fakeTime = 60_000L
        tracker.updateStatus("arrived")

        fakeTime = 60_000L
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0, time = fakeTime))

        assertNotNull(lastStats)
        assertNotNull(lastStats!!.statsForStatus("en_route"))
        assertNotNull(lastStats!!.statsForStatus("arrived"))
    }

    @Test
    fun nullListenerSafetyForPauseResume() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })
        tracker.pauseTimeThresholdSeconds = 5L
        // No listener set

        fakeTime = 0L
        tracker.startTrip("t1", "en_route")
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 0L))
        tracker.updatePosition(pos(lat = 40.0, lon = -74.0, time = 6_000L)) // pause
        tracker.updatePosition(pos(lat = 41.0, lon = -74.0, time = 10_000L)) // resume
        // Should not throw
        fakeTime = 10_000L
        val stats = tracker.getTripStats()
        assertFalse(stats.isPaused)
    }

    @Test
    fun endTripStatsContainFullHistory() {
        var fakeTime = 0L
        val tracker = TripTracker(timeProvider = { fakeTime })

        var endedStats: TripStats? = null
        tracker.setListener(object : TripTrackerListener {
            override fun onTripStatsUpdated(stats: TripStats) {}
            override fun onTripEnded(stats: TripStats) { endedStats = stats }
        })

        fakeTime = 0L
        tracker.startTrip("t1", "accepted")
        fakeTime = 10_000L
        tracker.updateStatus("en_route")
        fakeTime = 20_000L
        tracker.updateStatus("arrived")
        fakeTime = 30_000L
        tracker.endTrip()

        assertNotNull(endedStats)
        assertEquals(3, endedStats!!.statusHistory.size)
        assertNotNull(endedStats!!.statsForStatus("accepted"))
        assertNotNull(endedStats!!.statsForStatus("en_route"))
        assertNotNull(endedStats!!.statsForStatus("arrived"))
        assertEquals(10L, endedStats!!.statsForStatus("accepted")!!.timeSeconds)
        assertEquals(10L, endedStats!!.statsForStatus("en_route")!!.timeSeconds)
        assertEquals(10L, endedStats!!.statsForStatus("arrived")!!.timeSeconds)
    }

    // ══════════════════════════════════════════════════════════════════════
    // NEW TESTS: Backward compatibility
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun existingTripStatsConstructionStillWorks() {
        // This mirrors the existing DataModelTest usage: TripStats("t", "s", 1.0, 10, 0.5, 5)
        val stats = TripStats("t", "s", 1.0, 10, 0.5, 5)
        assertEquals("t", stats.tripId)
        assertEquals("s", stats.status)
        assertEquals(1.0, stats.totalDistanceKm)
        assertEquals(10L, stats.totalTimeSeconds)
        assertEquals(0.5, stats.statusDistanceKm)
        assertEquals(5L, stats.statusTimeSeconds)
        // New fields have defaults
        assertEquals(0.0, stats.totalDistanceMeters)
        assertEquals(0.0, stats.totalTimeMinutes)
        assertTrue(stats.statusHistory.isEmpty())
        assertEquals(0L, stats.totalStoppingSeconds)
        assertFalse(stats.isPaused)
    }

    @Test
    fun zeroArgTripTrackerConstructorStillWorks() {
        val tracker = TripTracker()
        assertFalse(tracker.isActive)
        tracker.startTrip("t1", "s1")
        assertTrue(tracker.isActive)
    }
}
