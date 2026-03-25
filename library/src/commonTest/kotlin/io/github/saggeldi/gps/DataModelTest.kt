package io.github.saggeldi.gps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PositionTest {

    @Test
    fun defaultValuesAreCorrect() {
        val pos = Position(deviceId = "dev", time = 1000L)
        assertEquals(0L, pos.id)
        assertEquals("dev", pos.deviceId)
        assertEquals(1000L, pos.time)
        assertEquals(0.0, pos.latitude)
        assertEquals(0.0, pos.longitude)
        assertEquals(0.0, pos.altitude)
        assertEquals(0.0, pos.speed)
        assertEquals(0.0, pos.course)
        assertEquals(0.0, pos.accuracy)
        assertEquals(BatteryStatus(), pos.battery)
        assertFalse(pos.mock)
        assertNull(pos.tripId)
        assertNull(pos.tripStatus)
    }

    @Test
    fun copyModifiesSpecifiedFields() {
        val original = Position(deviceId = "dev", time = 1000L, latitude = 40.0)
        val copied = original.copy(latitude = 50.0, tripId = "trip-1")
        assertEquals(50.0, copied.latitude)
        assertEquals("trip-1", copied.tripId)
        assertEquals("dev", copied.deviceId)
        assertEquals(1000L, copied.time)
    }

    @Test
    fun equalityBasedOnAllFields() {
        val a = Position(deviceId = "dev", time = 1000L)
        val b = Position(deviceId = "dev", time = 1000L)
        assertEquals(a, b)
    }

    @Test
    fun inequalityOnDifferentFields() {
        val a = Position(deviceId = "dev", time = 1000L)
        val b = Position(deviceId = "dev", time = 2000L)
        assertNotEquals(a, b)
    }

    @Test
    fun positionWithAllFieldsSet() {
        val pos = Position(
            id = 42,
            deviceId = "device-xyz",
            time = 1706345600000,
            latitude = 40.7128,
            longitude = -74.0060,
            altitude = 100.5,
            speed = 25.3,
            course = 180.0,
            accuracy = 3.5,
            battery = BatteryStatus(level = 85.0, charging = true),
            mock = true,
            tripId = "trip-99",
            tripStatus = "en_route"
        )
        assertEquals(42L, pos.id)
        assertEquals("device-xyz", pos.deviceId)
        assertEquals(40.7128, pos.latitude)
        assertEquals(-74.0060, pos.longitude)
        assertEquals(100.5, pos.altitude)
        assertEquals(25.3, pos.speed)
        assertEquals(180.0, pos.course)
        assertEquals(3.5, pos.accuracy)
        assertEquals(85.0, pos.battery.level)
        assertTrue(pos.battery.charging)
        assertTrue(pos.mock)
        assertEquals("trip-99", pos.tripId)
        assertEquals("en_route", pos.tripStatus)
    }
}

class BatteryStatusTest {

    @Test
    fun defaultValues() {
        val battery = BatteryStatus()
        assertEquals(0.0, battery.level)
        assertFalse(battery.charging)
    }

    @Test
    fun customValues() {
        val battery = BatteryStatus(level = 75.5, charging = true)
        assertEquals(75.5, battery.level)
        assertTrue(battery.charging)
    }

    @Test
    fun equality() {
        val a = BatteryStatus(level = 50.0, charging = false)
        val b = BatteryStatus(level = 50.0, charging = false)
        assertEquals(a, b)
    }

    @Test
    fun inequality() {
        val a = BatteryStatus(level = 50.0, charging = false)
        val b = BatteryStatus(level = 50.0, charging = true)
        assertNotEquals(a, b)
    }

    @Test
    fun copyModifiesField() {
        val original = BatteryStatus(level = 80.0, charging = false)
        val copied = original.copy(charging = true)
        assertTrue(copied.charging)
        assertEquals(80.0, copied.level)
    }
}

class GpsConfigTest {

    @Test
    fun defaultValues() {
        val config = GpsConfig(deviceId = "my-device")
        assertEquals("my-device", config.deviceId)
        assertEquals(300L, config.interval)
        assertEquals(0.0, config.distance)
        assertEquals(0.0, config.angle)
        assertEquals(LocationAccuracy.MEDIUM, config.accuracy)
    }

    @Test
    fun customValues() {
        val config = GpsConfig(
            deviceId = "custom",
            interval = 60,
            distance = 100.0,
            angle = 30.0,
            accuracy = LocationAccuracy.HIGH
        )
        assertEquals("custom", config.deviceId)
        assertEquals(60L, config.interval)
        assertEquals(100.0, config.distance)
        assertEquals(30.0, config.angle)
        assertEquals(LocationAccuracy.HIGH, config.accuracy)
    }

    @Test
    fun equality() {
        val a = GpsConfig(deviceId = "dev", interval = 60)
        val b = GpsConfig(deviceId = "dev", interval = 60)
        assertEquals(a, b)
    }

    @Test
    fun inequalityOnDeviceId() {
        val a = GpsConfig(deviceId = "dev1")
        val b = GpsConfig(deviceId = "dev2")
        assertNotEquals(a, b)
    }

    @Test
    fun inequalityOnInterval() {
        val a = GpsConfig(deviceId = "dev", interval = 60)
        val b = GpsConfig(deviceId = "dev", interval = 120)
        assertNotEquals(a, b)
    }

    @Test
    fun copyModifiesField() {
        val original = GpsConfig(deviceId = "dev", interval = 300)
        val copied = original.copy(interval = 60, accuracy = LocationAccuracy.LOW)
        assertEquals("dev", copied.deviceId)
        assertEquals(60L, copied.interval)
        assertEquals(LocationAccuracy.LOW, copied.accuracy)
    }
}

class LocationAccuracyTest {

    @Test
    fun allValuesExist() {
        val values = LocationAccuracy.entries
        assertEquals(3, values.size)
        assertTrue(values.contains(LocationAccuracy.HIGH))
        assertTrue(values.contains(LocationAccuracy.MEDIUM))
        assertTrue(values.contains(LocationAccuracy.LOW))
    }
}

class PermissionStatusTest {

    @Test
    fun allValuesExist() {
        val values = PermissionStatus.entries
        assertEquals(4, values.size)
        assertTrue(values.contains(PermissionStatus.GRANTED))
        assertTrue(values.contains(PermissionStatus.DENIED))
        assertTrue(values.contains(PermissionStatus.NOT_DETERMINED))
        assertTrue(values.contains(PermissionStatus.RESTRICTED))
    }
}

class TrackerStatusTest {

    @Test
    fun allValuesExist() {
        val values = TrackerStatus.entries
        assertEquals(2, values.size)
        assertTrue(values.contains(TrackerStatus.STARTED))
        assertTrue(values.contains(TrackerStatus.STOPPED))
    }
}

class TripStatsTest {

    @Test
    fun dataClassFields() {
        val stats = TripStats(
            tripId = "trip-1",
            status = "en_route",
            totalDistanceKm = 5.5,
            totalTimeSeconds = 600,
            statusDistanceKm = 2.3,
            statusTimeSeconds = 300
        )
        assertEquals("trip-1", stats.tripId)
        assertEquals("en_route", stats.status)
        assertEquals(5.5, stats.totalDistanceKm)
        assertEquals(600L, stats.totalTimeSeconds)
        assertEquals(2.3, stats.statusDistanceKm)
        assertEquals(300L, stats.statusTimeSeconds)
    }

    @Test
    fun nullableFields() {
        val stats = TripStats(
            tripId = null,
            status = null,
            totalDistanceKm = 0.0,
            totalTimeSeconds = 0,
            statusDistanceKm = 0.0,
            statusTimeSeconds = 0
        )
        assertNull(stats.tripId)
        assertNull(stats.status)
    }

    @Test
    fun equality() {
        val a = TripStats("t", "s", 1.0, 10, 0.5, 5)
        val b = TripStats("t", "s", 1.0, 10, 0.5, 5)
        assertEquals(a, b)
    }
}
