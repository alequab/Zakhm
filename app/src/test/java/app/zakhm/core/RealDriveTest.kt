package app.zakhm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Situations seen on a real drive, recreated so they stay fixed. */
class RealDriveTest {
    private val engine = TelemetryEngine()

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

    private fun calibrated(sim: DriveSimulator) {
        sim.run(4.0)
        sim.run(60.0, ::city)
        assertEquals(CalibrationState.READY, engine.snapshot().calibration)
    }

    /** Brakes to a standstill from whatever speed the car has. */
    private fun brakeToAStop(sim: DriveSimulator) {
        while (sim.speed > 0.0) sim.run(0.1, { CarMotion(accel = -1.5) })
    }

    @Test
    fun downIsFoundWhenStraightStretchesKeepGettingInterrupted() {
        val sim = DriveSimulator(engine, startSpeed = 16.0)
        // Never five steady seconds in a row: real roads have small bends and speed changes all the time.
        var seconds = Double.POSITIVE_INFINITY
        val start = sim.time
        sim.run(60.0, { t ->
            val phase = t % 7.0
            when {
                phase < 4 -> CarMotion()
                phase < 5.5 -> CarMotion(yawRate = 0.15)
                else -> CarMotion(yawRate = -0.15)
            }
        }) {
            if (seconds.isInfinite() && engine.snapshot().calibration != CalibrationState.WAITING_FOR_STOP) {
                seconds = sim.time - start
            }
        }
        println("down found after $seconds s of interrupted cruising")
        assertTrue("took $seconds s", seconds < 30.0)
        val found = engine.mountCalibration()!!.up
        assertTrue("down is off, dot=${found dot DriveSimulator.MOUNT_TILTED.third}", (found dot DriveSimulator.MOUNT_TILTED.third) > 0.995)
    }

    @Test
    fun aPhoneMovedHalfwayThroughLearningDownDoesNotMixBothPositions() {
        val first = DriveSimulator(engine, startSpeed = 16.0)
        first.run(3.5)
        assertEquals(CalibrationState.WAITING_FOR_STOP, engine.snapshot().calibration)
        val axes = Triple(Vec3(0.0, 0.0, -1.0), Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0))
        val moved = DriveSimulator(engine, axes.first, axes.second, axes.third, startSpeed = 16.0, startTime = first.time)
        moved.run(12.0)
        assertNotEquals(CalibrationState.WAITING_FOR_STOP, engine.snapshot().calibration)
        assertTrue((engine.mountCalibration()!!.up dot axes.third) > 0.995)
    }

    @Test
    fun speedFollowsTheCarWhenPullingAwayFromAStop() {
        val sim = DriveSimulator(engine)
        calibrated(sim)
        brakeToAStop(sim)
        sim.run(30.0)
        var worst = 0.0
        sim.run(10.0, { CarMotion(accel = 1.0) }) { truth ->
            if (truth.time > sim.time - 0.01) worst = maxOf(worst, kotlin.math.abs(engine.snapshot().speedMps!! - truth.speed))
        }
        var gapAfter = 0.0
        sim.run(5.0, { CarMotion() }) { truth -> gapAfter = kotlin.math.abs(engine.snapshot().speedMps!! - truth.speed) }
        println("speed gap pulling away: worst $worst m/s, afterwards $gapAfter m/s")
        assertTrue("speed was $gapAfter m/s off after pulling away", gapAfter < 1.0)
    }
}
