package io.github.saggeldi.gps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ProtocolFormatterTest {

    private fun makePosition(
        deviceId: String = "device-123",
        time: Long = 1706345600000,
        latitude: Double = 40.7128,
        longitude: Double = -74.0060,
        altitude: Double = 10.0,
        speed: Double = 5.0,
        course: Double = 180.0,
        accuracy: Double = 5.0,
        battery: BatteryStatus = BatteryStatus(level = 85.0, charging = false),
        mock: Boolean = false
    ) = Position(
        id = 1,
        deviceId = deviceId,
        time = time,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        speed = speed,
        course = course,
        accuracy = accuracy,
        battery = battery,
        mock = mock
    )

    // ── formatRequest (legacy URL) ──────────────────────────────────────

    @Test
    fun formatRequestContainsAllRequiredParams() {
        val pos = makePosition()
        val url = ProtocolFormatter.formatRequest("https://example.com/api", pos)

        assertContains(url, "id=device-123")
        assertContains(url, "timestamp=1706345600")
        assertContains(url, "lat=40.7128")
        assertContains(url, "lon=-74.006")
        assertContains(url, "speed=5.0")
        assertContains(url, "bearing=180.0")
        assertContains(url, "altitude=10.0")
        assertContains(url, "accuracy=5.0")
        assertContains(url, "batt=85.0")
    }

    @Test
    fun formatRequestStartsWithBaseUrl() {
        val pos = makePosition()
        val url = ProtocolFormatter.formatRequest("https://example.com/api", pos)
        assertTrue(url.startsWith("https://example.com/api?"))
    }

    @Test
    fun formatRequestAppendsWithAmpersandWhenUrlHasQueryParams() {
        val pos = makePosition()
        val url = ProtocolFormatter.formatRequest("https://example.com/api?key=val", pos)
        assertTrue(url.startsWith("https://example.com/api?key=val&"))
    }

    @Test
    fun formatRequestTimestampIsInSeconds() {
        val pos = makePosition(time = 1706345600000)
        val url = ProtocolFormatter.formatRequest("https://example.com", pos)
        assertContains(url, "timestamp=1706345600")
    }

    @Test
    fun formatRequestIncludesChargeTrueWhenCharging() {
        val pos = makePosition(battery = BatteryStatus(level = 50.0, charging = true))
        val url = ProtocolFormatter.formatRequest("https://example.com", pos)
        assertContains(url, "charge=true")
    }

    @Test
    fun formatRequestOmitsChargeWhenNotCharging() {
        val pos = makePosition(battery = BatteryStatus(level = 50.0, charging = false))
        val url = ProtocolFormatter.formatRequest("https://example.com", pos)
        assertTrue(!url.contains("charge="))
    }

    @Test
    fun formatRequestIncludesMockTrueWhenMocked() {
        val pos = makePosition(mock = true)
        val url = ProtocolFormatter.formatRequest("https://example.com", pos)
        assertContains(url, "mock=true")
    }

    @Test
    fun formatRequestOmitsMockWhenNotMocked() {
        val pos = makePosition(mock = false)
        val url = ProtocolFormatter.formatRequest("https://example.com", pos)
        assertTrue(!url.contains("mock="))
    }

    @Test
    fun formatRequestIncludesAlarmWhenProvided() {
        val pos = makePosition()
        val url = ProtocolFormatter.formatRequest("https://example.com", pos, alarm = "sos")
        assertContains(url, "alarm=sos")
    }

    @Test
    fun formatRequestOmitsAlarmWhenNull() {
        val pos = makePosition()
        val url = ProtocolFormatter.formatRequest("https://example.com", pos)
        assertTrue(!url.contains("alarm="))
    }

    @Test
    fun formatRequestUrlEncodesDeviceIdWithSpaces() {
        val pos = makePosition(deviceId = "my device")
        val url = ProtocolFormatter.formatRequest("https://example.com", pos)
        assertContains(url, "id=my%20device")
    }

    @Test
    fun formatRequestUrlEncodesSpecialCharacters() {
        val pos = makePosition(deviceId = "dev+id&foo=bar")
        val url = ProtocolFormatter.formatRequest("https://example.com", pos)
        assertTrue(!url.contains("id=dev+id&foo=bar"))
        assertContains(url, "id=dev%2Bid%26foo%3Dbar")
    }

    @Test
    fun formatRequestUrlEncodesAlarmWithSpecialChars() {
        val pos = makePosition()
        val url = ProtocolFormatter.formatRequest("https://example.com", pos, alarm = "test alarm!")
        assertContains(url, "alarm=test%20alarm%21")
    }

    @Test
    fun formatRequestHandlesZeroValues() {
        val pos = makePosition(
            latitude = 0.0,
            longitude = 0.0,
            altitude = 0.0,
            speed = 0.0,
            course = 0.0,
            accuracy = 0.0
        )
        val url = ProtocolFormatter.formatRequest("https://example.com", pos)
        assertContains(url, "lat=0.0")
        assertContains(url, "lon=0.0")
        assertContains(url, "speed=0.0")
    }

    @Test
    fun formatRequestHandlesNegativeCoordinates() {
        val pos = makePosition(latitude = -33.8688, longitude = 151.2093)
        val url = ProtocolFormatter.formatRequest("https://example.com", pos)
        assertContains(url, "lat=-33.8688")
        assertContains(url, "lon=151.2093")
    }

    // ── formatJsonBody ──────────────────────────────────────────────────

    @Test
    fun formatJsonBodyContainsAllFields() {
        val pos = makePosition()
        val json = ProtocolFormatter.formatJsonBody(pos)

        assertContains(json, "\"accuracy\":5.0")
        assertContains(json, "\"altitude\":10.0")
        assertContains(json, "\"battery\":\"85\"")
        assertContains(json, "\"course\":180.0")
        assertContains(json, "\"device_id\":\"device-123\"")
        assertContains(json, "\"latitude\":40.7128")
        assertContains(json, "\"longitude\":-74.006")
        assertContains(json, "\"mock\":false")
        assertContains(json, "\"speed\":5.0")
        assertContains(json, "\"time\":\"1706345600000\"")
    }

    @Test
    fun formatJsonBodyIsValidJsonStructure() {
        val pos = makePosition()
        val json = ProtocolFormatter.formatJsonBody(pos)
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
    }

    @Test
    fun formatJsonBodyBatteryIsIntegerString() {
        val pos = makePosition(battery = BatteryStatus(level = 85.7, charging = false))
        val json = ProtocolFormatter.formatJsonBody(pos)
        assertContains(json, "\"battery\":\"85\"")
    }

    @Test
    fun formatJsonBodyTimeIsStringNotNumber() {
        val pos = makePosition(time = 1706345600000)
        val json = ProtocolFormatter.formatJsonBody(pos)
        assertContains(json, "\"time\":\"1706345600000\"")
    }

    @Test
    fun formatJsonBodyMockTrue() {
        val pos = makePosition(mock = true)
        val json = ProtocolFormatter.formatJsonBody(pos)
        assertContains(json, "\"mock\":true")
    }

    @Test
    fun formatJsonBodyMockFalse() {
        val pos = makePosition(mock = false)
        val json = ProtocolFormatter.formatJsonBody(pos)
        assertContains(json, "\"mock\":false")
    }

    @Test
    fun formatJsonBodyEscapesQuotesInDeviceId() {
        val pos = makePosition(deviceId = "dev\"ice")
        val json = ProtocolFormatter.formatJsonBody(pos)
        assertContains(json, "\"device_id\":\"dev\\\"ice\"")
    }

    @Test
    fun formatJsonBodyEscapesBackslashInDeviceId() {
        val pos = makePosition(deviceId = "dev\\ice")
        val json = ProtocolFormatter.formatJsonBody(pos)
        assertContains(json, "\"device_id\":\"dev\\\\ice\"")
    }

    @Test
    fun formatJsonBodyEscapesNewlineInDeviceId() {
        val pos = makePosition(deviceId = "dev\nice")
        val json = ProtocolFormatter.formatJsonBody(pos)
        assertContains(json, "\"device_id\":\"dev\\nice\"")
    }

    @Test
    fun formatJsonBodyEscapesTabInDeviceId() {
        val pos = makePosition(deviceId = "dev\tice")
        val json = ProtocolFormatter.formatJsonBody(pos)
        assertContains(json, "\"device_id\":\"dev\\tice\"")
    }

    @Test
    fun formatJsonBodyHandlesZeroBattery() {
        val pos = makePosition(battery = BatteryStatus(level = 0.0, charging = false))
        val json = ProtocolFormatter.formatJsonBody(pos)
        assertContains(json, "\"battery\":\"0\"")
    }

    @Test
    fun formatJsonBodyHandlesFullBattery() {
        val pos = makePosition(battery = BatteryStatus(level = 100.0, charging = true))
        val json = ProtocolFormatter.formatJsonBody(pos)
        assertContains(json, "\"battery\":\"100\"")
    }

    @Test
    fun formatJsonBodyHandlesNegativeCoordinates() {
        val pos = makePosition(latitude = -33.8688, longitude = 151.2093)
        val json = ProtocolFormatter.formatJsonBody(pos)
        assertContains(json, "\"latitude\":-33.8688")
        assertContains(json, "\"longitude\":151.2093")
    }
}
