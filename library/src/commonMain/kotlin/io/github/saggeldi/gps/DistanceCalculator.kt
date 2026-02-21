package io.github.saggeldi.gps

import kotlin.math.*

internal object DistanceCalculator {
    private const val EQUATORIAL_EARTH_RADIUS = 6378.1370
    private val DEG_TO_RAD = PI / 180.0

    fun distance(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double
    ): Double {
        val dLat = (toLat - fromLat) * DEG_TO_RAD
        val dLon = (toLon - fromLon) * DEG_TO_RAD
        val a = sin(dLat / 2).pow(2) +
                cos(fromLat * DEG_TO_RAD) * cos(toLat * DEG_TO_RAD) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EQUATORIAL_EARTH_RADIUS * c * 1000.0
    }
}
