package app.zakhm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.random.Random

/** The meters must not blink or jump with road bumps, yet follow a real push quickly. */
class SteadyPushTest {
    private val steady = SteadyPush()
    private var time = 0.0

    /** Feeds [seconds] of readings at the screen's 20 a second; returns how often the shown place changed. */
    private fun feed(seconds: Double, reading: (Double) -> Pair<Double, Double>): Int {
        var changes = 0
        var before = steady.shown to steady.placeAngle
        val end = time + seconds
        while (time < end) {
            time += 0.05
            val (long, lat) = reading(time)
            steady.update(time, long, lat, 1.5, 3)
            val now = steady.shown to steady.placeAngle
            if (now != before && (now.first || before.first)) changes++
            before = now
        }
        return changes
    }

    @Test
    fun roadBumpsAroundNothingStayDark() {
        val random = Random(1)
        // Like coasting on the 24 Nov drive: about half the time under 0.05 G, flipping sign constantly.
        val changes = feed(60.0) { random.nextDouble(-0.06, 0.06) to random.nextDouble(-0.04, 0.04) }
        assertTrue("lit or moved $changes times in a minute of bumps", changes <= 2)
    }

    @Test
    fun aRealBrakeLightsTheBrakeSideAtOnce() {
        feed(1.0) { 0.0 to 0.0 }
        val start = time
        var litAt = Double.NaN
        feed(2.0) { t -> if (litAt.isNaN() && steady.shown) litAt = t; -0.3 to 0.0 }
        assertTrue("lit after ${litAt - start} s", litAt - start <= 0.15)
        assertEquals(-PI / 2, steady.placeAngle, 1e-9)
        assertEquals(1, steady.lit)
    }

    @Test
    fun brakingThenSpeedingUpMovesTheLitSideQuickly() {
        feed(2.0) { -0.3 to 0.0 }
        feed(0.8) { 0.25 to 0.0 }
        assertTrue(steady.shown)
        assertEquals(PI / 2, steady.placeAngle, 1e-9)
    }

    @Test
    fun aFadedPushGoesDark() {
        feed(2.0) { 0.0 to 0.3 }
        feed(1.0) { 0.0 to 0.02 }
        assertFalse(steady.shown)
        assertEquals(0, steady.lit)
    }

    @Test
    fun aPushNearABandStepDoesNotFlickIt() {
        // 1/3 of the 1.5 G range is 0.5 G: wobble around it.
        val random = Random(2)
        var flicks = 0
        var before = -1
        feed(10.0) { 0.0 to 0.5 + random.nextDouble(-0.03, 0.03) }
        val end = time + 10.0
        while (time < end) {
            time += 0.05
            steady.update(time, 0.0, 0.5 + random.nextDouble(-0.03, 0.03), 1.5, 3)
            if (before != -1 && steady.lit != before) flicks++
            before = steady.lit
        }
        assertEquals(0, flicks)
    }
}
