package io.github.saggeldi.gps

object ProtocolFormatter {

    fun formatRequest(url: String, position: Position, alarm: String? = null): String {
        val sb = StringBuilder(url)
        sb.append(if (url.contains("?")) "&" else "?")
        sb.append("id=").append(encodeParam(position.deviceId))
        sb.append("&timestamp=").append(position.time / 1000)
        sb.append("&lat=").append(position.latitude)
        sb.append("&lon=").append(position.longitude)
        sb.append("&speed=").append(position.speed)
        sb.append("&bearing=").append(position.course)
        sb.append("&altitude=").append(position.altitude)
        sb.append("&accuracy=").append(position.accuracy)
        sb.append("&batt=").append(position.battery.level)
        if (position.battery.charging) {
            sb.append("&charge=true")
        }
        sb.append("&accumulated_km=").append(position.km)
        if (position.mock) {
            sb.append("&mock=true")
        }
        if (alarm != null) {
            sb.append("&alarm=").append(encodeParam(alarm))
        }
        return sb.toString()
    }

    /**
     * Format position as JSON body matching DtoDriverLocationUpdateReqDto:
     * {
     *   "accuracy": 5.0,
     *   "altitude": 10.0,
     *   "battery": "85",
     *   "course": 180.0,
     *   "device_id": "device-123",
     *   "latitude": 40.7128,
     *   "longitude": -74.0060,
     *   "mock": false,
     *   "speed": 0.0,
     *   "time": "1706345600000"
     * }
     */
    fun formatJsonBody(position: Position, totalKm: Double = 0.0): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"accuracy\":").append(position.accuracy)
        sb.append(",\"altitude\":").append(position.altitude)
        sb.append(",\"battery\":\"").append(position.battery.level.toInt()).append("\"")
        sb.append(",\"course\":").append(position.course)
        sb.append(",\"device_id\":\"").append(escapeJson(position.deviceId)).append("\"")
        sb.append(",\"latitude\":").append(position.latitude)
        sb.append(",\"longitude\":").append(position.longitude)
        sb.append(",\"mock\":").append(position.mock)
        sb.append(",\"speed\":").append(position.speed)
        sb.append(",\"time\":\"").append(position.time).append("\"")
        sb.append(",\"accumulated_km\":").append(totalKm)
        sb.append("}")
        return sb.toString()
    }

    private fun escapeJson(value: String): String {
        val sb = StringBuilder()
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun encodeParam(value: String): String {
        val sb = StringBuilder()
        for (c in value) {
            when {
                c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~' -> sb.append(c)
                else -> {
                    val bytes = c.toString().encodeToByteArray()
                    for (b in bytes) {
                        sb.append('%')
                        sb.append(HEX_CHARS[(b.toInt() shr 4) and 0xF])
                        sb.append(HEX_CHARS[b.toInt() and 0xF])
                    }
                }
            }
        }
        return sb.toString()
    }

    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()
}
