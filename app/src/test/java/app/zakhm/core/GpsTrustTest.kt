package app.zakhm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class GpsTrustTest {
    @Test
    fun beforeLearningTheReportedAccuracyIsTakenAsTooModest() {
        val trust = GpsTrust()
        assertFalse(trust.learned)
        assertEquals(0.4, trust.scale, 1e-9)
        assertEquals(0.88, trust.sigma(2.2), 1e-9)
        // A phone that already claims to be good isn't trusted beyond it, or beyond 0.5 m/s.
        assertEquals(0.3, trust.sigma(0.3), 1e-9)
        assertEquals(0.5, trust.sigma(0.9), 1e-9)
    }

    /** The owner's phone: within 0.37 m/s of the car, while claiming about 2.2. */
    @Test
    fun learnsHowModestThePhoneIs() {
        val trust = GpsTrust()
        val random = Random(7)
        repeat(600) {
            val car = 15.0 + random.nextDouble() * 10
            trust.add(car + random.nextGaussian() * 0.37, car, 2.2 + random.nextGaussian() * 0.3)
        }
        assertTrue(trust.learned)
        assertEquals(0.37 / 2.2, trust.scale, 0.02)
    }

    @Test
    fun anHonestPhoneIsNeverTrustedMoreThanItSays() {
        val trust = GpsTrust()
        val random = Random(3)
        repeat(600) {
            val car = 20.0
            trust.add(car + random.nextGaussian() * 2.0, car, 0.5)
        }
        assertEquals(1.0, trust.scale, 1e-9)
    }

    @Test
    fun slowSpeedsAndMissingFiguresTeachNothing() {
        val trust = GpsTrust()
        repeat(600) {
            trust.add(2.0, 2.5, 2.0)
            trust.add(20.0, 20.0, null)
            trust.add(20.0, 20.0, Double.NaN)
        }
        assertEquals(0, trust.sampleCount)
    }

    @Test
    fun whatWasLearnedIsKept() {
        val trust = GpsTrust()
        repeat(200) { trust.add(20.3, 20.0, 2.0) }
        val again = GpsTrust(trust.saved())
        assertEquals(trust.scale, again.scale, 1e-12)
        assertTrue(again.learned)
        // Nonsense from storage is ignored.
        assertFalse(GpsTrust(GpsTrust.Saved(Double.NaN, 1.0, 500)).learned)
    }

    /**
     * A GPS-only drive like the one on 12 Nov: the phone says its speed may be 2.2 m/s off
     * while it is really within 0.37. Believing the phone made the shown speed trail GPS;
     * with the measured figure it follows the car closely.
     */
    @Test
    fun aModestPhoneNoLongerHoldsTheShownSpeedBack() {
        fun drive(trust: GpsTrust.Saved?): Double {
            val engine = TelemetryEngine()
            engine.loadGpsTrust(trust)
            val sim = DriveSimulator(
                engine,
                gpsNoise = 0.37,
                gpsReportedAccuracy = 2.2,
                // A forward axis a little off, as after the phone shifts in its mount.
                accelBias = Vec3(0.25, -0.03, 0.05),
                roadRumble = 0.3,
            )
            sim.run(4.0)
            var errors = 0.0
            var count = 0
            sim.run(300.0, { t ->
                val phase = t % 40.0
                when {
                    phase < 6 -> CarMotion(accel = 1.5)
                    phase < 14 -> CarMotion()
                    phase < 19 -> CarMotion(yawRate = 0.3)
                    phase < 27 -> CarMotion()
                    phase < 33 -> CarMotion(accel = -1.5)
                    else -> CarMotion()
                }
            }) { truth ->
                val shown = engine.snapshot().speedMps
                if (truth.time > 120 && shown != null) {
                    errors += abs(shown - truth.speed)
                    count++
                }
            }
            return errors / count
        }
        // Believing the phone exactly: scale 1.
        val believed = drive(GpsTrust.Saved(errorSquares = 4.84 * 200, reportedSquares = 4.84 * 200, samples = 200))
        val measured = drive(GpsTrust.Saved(errorSquares = 0.137 * 200, reportedSquares = 4.84 * 200, samples = 200))
        val guessed = drive(null)
        println("mean speed error: believing the phone $believed m/s, measured $measured m/s, before learning $guessed m/s")
        assertTrue("measured $measured vs believed $believed", measured < believed)
        assertTrue("before learning $guessed vs believed $believed", guessed < believed)
    }
}
