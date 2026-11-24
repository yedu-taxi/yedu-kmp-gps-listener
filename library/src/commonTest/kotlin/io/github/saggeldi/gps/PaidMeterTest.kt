package io.github.saggeldi.gps

import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaidMeterTest {
    private fun PaidMeter.at(northM: Double, eastM: Double, sec: Int, acc: Double = 8.0) =
        add(25.0 + northM / 111_195.0, 55.0 + eastM / (111_195.0 * cos(25.0 * PI / 180)), sec * 1000L, acc)

    @Test
    fun aParkedCarIsNotBilled() {
        val m = PaidMeter()
        for (s in 0 until 600) m.at(((s * 37) % 11 - 5).toDouble(), ((s * 53) % 11 - 5).toDouble(), s)
        assertEquals(0.0, m.meters, 30.0)
    }

    @Test
    fun drivingIsBilledFully() {
        val m = PaidMeter()
        for (s in 0..300) m.at(s * 12.0, 0.0, s)
        assertTrue(m.meters in 3540.0..3610.0, "drove 3600 m, billed ${m.meters}")
    }

    @Test
    fun crawlingTrafficIsBilled() {
        val m = PaidMeter()
        for (s in 0..300) m.at(s * 1.5, 0.0, s, acc = 5.0)
        assertTrue(m.meters in 430.0..455.0, "drove 450 m, billed ${m.meters}")
    }

    @Test
    fun aSpikeAndAPoorFixAreNotBilled() {
        val m = PaidMeter()
        m.at(0.0, 0.0, 0)
        m.at(5000.0, 0.0, 1)
        m.at(400.0, 0.0, 30, acc = 300.0)
        m.at(3.0, 0.0, 60)
        assertEquals(0.0, m.meters, 1.0)
    }

    @Test
    fun aRepeatedFixAddsNothing() {
        val m = PaidMeter()
        for (s in 0..20) m.at(0.0, 0.0, s * 30)
        assertEquals(0.0, m.meters)
    }
}
