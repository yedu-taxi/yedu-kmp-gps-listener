package io.github.saggeldi.gps

import kotlin.test.Test
import kotlin.test.assertTrue

class DistanceCalculatorTest {
    @Test
    fun testDistance() {
        val dist = DistanceCalculator.distance(40.7128, -74.0060, 34.0522, -118.2437)
        assertTrue(dist > 3_900_000 && dist < 4_000_000)
    }

    @Test
    fun testZeroDistance() {
        val dist = DistanceCalculator.distance(40.0, -74.0, 40.0, -74.0)
        assertTrue(dist < 1.0)
    }
}
