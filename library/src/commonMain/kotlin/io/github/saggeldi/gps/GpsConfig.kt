package io.github.saggeldi.gps

data class GpsConfig(
    val deviceId: String,
    val interval: Long = 300,
    val distance: Double = 0.0,
    val angle: Double = 0.0,
    val accuracy: LocationAccuracy = LocationAccuracy.MEDIUM
)
