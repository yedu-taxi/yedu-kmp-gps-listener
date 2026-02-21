package io.github.saggeldi.gps

object ProtocolFormatter {

    fun formatRequest(url: String, position: Position, alarm: String? = null): String {
        val sb = StringBuilder(url)
        sb.append(if (url.contains("?")) "&" else "?")
        sb.append("id=").append(encodeParam(position.deviceId))
        sb.append("&timestamp=").append(position.time.epochSeconds)
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
        if (position.mock) {
            sb.append("&mock=true")
        }
        if (alarm != null) {
            sb.append("&alarm=").append(encodeParam(alarm))
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
