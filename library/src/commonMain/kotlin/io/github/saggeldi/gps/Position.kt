package io.github.saggeldi.gps

data class Position(
    val id: Long = 0,
    val deviceId: String,
    val time: Long,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val speed: Double = 0.0,
    val course: Double = 0.0,
    val accuracy: Double = 0.0,
    val battery: BatteryStatus = BatteryStatus(),
    val mock: Boolean = false,
    val tripId: String? = null,
    val tripStatus: String? = null
)
