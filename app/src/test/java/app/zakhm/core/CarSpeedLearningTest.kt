package app.zakhm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Calibration learned from the car's own speed, which comes in whole km/h several
 * times a second. On a real drive this taught a forward direction far enough off
 * that braking showed mostly as sideways G.
 */
class CarSpeedLearningTest {
    private fun city(t: Double): CarMotion {
        val phase = t % 40.0
        return when {
            phase < 6 -> CarMotion(accel = 1.2)
            phase < 14 -> CarMotion()
            phase < 19 -> CarMotion(yawRate = 0.3)
            phase < 27 -> CarMotion()
            phase < 33 -> CarMotion(accel = -1.2)
            else -> CarMotion()
        }
    }

    /** Gentle, uneven speed changes, where whole-km/h rounding matters most. */
    private fun gentle(t: Double) = CarMotion(accel = 0.35 * sin(2 * PI * t / 9.0) + 0.15 * sin(2 * PI * t / 4.0))

    private fun obdCar(engine: TelemetryEngine, seed: Long = 42) =
        DriveSimulator(engine, startSpeed = 10.0, seed = seed, obd = DriveSimulator.SimulatedObd(rate = 6.5, lag = 0.05))

    @Test
    fun forwardLearnedFromWholeKmhIsAccurate() {
        var worst = 1.0
        for (seed in 1L..5L) {
            val engine = TelemetryEngine()
            val sim = obdCar(engine, seed)
            sim.run(4.0)
            var t = 0.0
            while (engine.snapshot().calibration != CalibrationState.READY && t < 300.0) {
                sim.run(0.5, { tt -> gentle(t + tt) })
                t += 0.5
            }
            assertEquals(CalibrationState.READY, engine.snapshot().calibration)
            val dot = engine.mountCalibration()!!.forward!! dot DriveSimulator.MOUNT_TILTED.first
            println("seed $seed: forward ready after $t s, dot with truth $dot")
            worst = minOf(worst, dot)
        }
        assertTrue("forward off, worst dot=$worst", worst > 0.97)
    }

    @Test
    fun brakingIsShownAsBrakingNotSideways() {
        val engine = TelemetryEngine()
        val sim = obdCar(engine)
        sim.run(4.0)
        sim.run(90.0, ::city)
        assertEquals(CalibrationState.READY, engine.snapshot().calibration)
        sim.run(10.0, { CarMotion(accel = 1.5) })
        var lost = false
        sim.run(5.0, { CarMotion(accel = -2.8) }) { truth ->
            if (engine.snapshot().calibration != CalibrationState.READY) lost = true
        }
        assertFalse("hard braking lost the calibration", lost)
        val s = engine.snapshot()
        assertEquals(-2.8 / STANDARD_GRAVITY, s.longitudinalG!!, 0.04)
        assertEquals(0.0, s.lateralG!!, 0.04)
    }
}
