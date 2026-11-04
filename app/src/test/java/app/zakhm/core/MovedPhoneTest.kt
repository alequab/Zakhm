package app.zakhm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Phones get moved mid-drive, and sensors occasionally report nonsense. */
class MovedPhoneTest {
    /** The same phone after someone stood it up differently, about 60 degrees from [DriveSimulator.MOUNT_TILTED]. */
    private val movedAxes = Triple(Vec3(0.0, 0.0, -1.0), Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0))

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

    private val engine = TelemetryEngine()

    /** Drives until calibrated, then returns a simulator continuing with the phone sitting differently. */
    private fun driveThenMoveThePhone(): DriveSimulator {
        val sim = DriveSimulator(engine)
        sim.run(4.0)
        sim.run(60.0, ::city)
        assertEquals(CalibrationState.READY, engine.snapshot().calibration)
        return DriveSimulator(
            engine,
            movedAxes.first,
            movedAxes.second,
            movedAxes.third,
            startSpeed = 17.0,
            startTime = sim.time,
        )
    }

    @Test
    fun phoneMovedWhileDrivingIsNoticedAndCalibrationRestarts() {
        val moved = driveThenMoveThePhone()
        val peaksBefore = engine.currentPeaks()
        moved.run(8.0)
        assertNotEquals(
            "a phone sitting differently must not keep the old calibration",
            CalibrationState.READY,
            engine.snapshot().calibration,
        )
        assertEquals("peaks from the wrong calibration shouldn't be kept", peaksBefore.accel, engine.currentPeaks().accel, 0.05)
    }

    @Test
    fun speedStaysRightAfterThePhoneMoves() {
        val moved = driveThenMoveThePhone()

        moved.run(15.0)
        val shown = engine.snapshot().speedMps!!
        assertEquals(17.0, shown, 1.0)
    }

    @Test
    fun hardDrivingIsNotMistakenForAMovedPhone() {
        val sim = DriveSimulator(engine)

        sim.run(4.0)
        sim.run(60.0, ::city)
        assertEquals(CalibrationState.READY, engine.snapshot().calibration)
        var everLost = false
        sim.run(120.0, { t ->
            val phase = t % 30.0
            when {
                phase < 5 -> CarMotion(accel = 3.5)
                phase < 10 -> CarMotion(yawRate = 0.25 * sin(2 * PI * t / 5.0))
                phase < 14 -> CarMotion(accel = -4.5)
                phase < 22 -> CarMotion(accel = 1.0, yawRate = -0.2)
                else -> CarMotion(accel = -1.0)
            }
        }) {
            if (engine.snapshot().calibration != CalibrationState.READY) everLost = true
        }
        assertFalse("hard acceleration, braking and turns must keep the calibration", everLost)
    }

    @Test
    fun drivingUpASteepHillIsNotMistakenForAMovedPhone() {
        val sim = DriveSimulator(engine)

        sim.run(4.0)
        sim.run(60.0, ::city)
        sim.run(10.0, { CarMotion(accel = 1.0) })
        var everLost = false
        val hill = Math.toRadians(12.0)
        sim.run(40.0, { t -> CarMotion(pitch = hill * (t / 5.0).coerceAtMost(1.0)) }) {
            if (engine.snapshot().calibration != CalibrationState.READY) everLost = true
        }
        assertFalse("a 12 degree hill must keep the calibration", everLost)
    }

    @Test
    fun theSpeedStatusDoesNotFlickerWhenThePhoneMoves() {
        val moved = driveThenMoveThePhone()

        moved.run(8.0)
        var changes = 0
        var last = engine.snapshot().speedSource
        moved.run(30.0, { CarMotion() }) {
            val source = engine.snapshot().speedSource
            if (source != last) changes++
            last = source
        }
        assertEquals("speed status should settle once the move is handled", 0, changes)
    }

    @Test
    fun stoppedDoesNotFlickerNearTheLimit() {
        // Hand-sized movement right around the limit for a stop without GPS.
        val sim = DriveSimulator(engine, idleVibration = 0.0, handling = 0.09, gyroNoise = 0.003)
        sim.gpsEnabled = false
        sim.run(5.0)
        var changes = 0
        var last = engine.snapshot().stopped
        sim.run(30.0, { CarMotion() }) {
            val now = engine.snapshot().stopped
            if (now != last) changes++
            last = now
        }
        assertTrue("stopped flipped $changes times in 30 s", changes <= 2)
    }

    @Test
    fun aShakyMountDoesNotCreateFalsePeaks() {
        val sim = DriveSimulator(engine, mountShake = 3.0)
        sim.run(4.0)
        sim.run(60.0, ::city)
        assertEquals(CalibrationState.READY, engine.snapshot().calibration)
        engine.resetPeaks()
        // Steady driving, with the mount shaking at about 0.3 G.
        sim.run(20.0)
        val p = engine.currentPeaks()
        assertTrue("shake made a peak of $p", maxOf(p.accel, p.brake, p.left, p.right) < 0.1)
    }

    @Test
    fun realHardBrakingStillShowsAsAPeak() {
        val sim = DriveSimulator(engine, startSpeed = 25.0)
        sim.run(10.0)
        sim.run(60.0, ::city)
        assertEquals(CalibrationState.READY, engine.snapshot().calibration)
        sim.run(10.0, { CarMotion(accel = 1.5) })
        engine.resetPeaks()
        // Half a second of real 0.5 G braking.
        sim.run(0.5, { CarMotion(accel = -0.5 * STANDARD_GRAVITY) })
        assertTrue("braking peak was ${engine.currentPeaks().brake}", engine.currentPeaks().brake > 0.35)
    }

    @Test
    fun nonsenseReadingsAreIgnored() {
        val sim = DriveSimulator(engine)
        sim.run(4.0)
        sim.run(60.0, ::city)
        val before = engine.snapshot()
        val nan = Double.NaN
        repeat(50) { i ->
            engine.onAccel(1_000.0 + i, Vec3(nan, 0.0, 0.0))
            engine.onAccel(nan, Vec3(0.0, 0.0, 9.8))
            engine.onGyro(1_000.0 + i, Vec3(0.0, Double.POSITIVE_INFINITY, 0.0))
            engine.onSpeedFix(SpeedFix(1_000.0 + i, nan, 0.3, 4.0))
            engine.onSpeedFix(SpeedFix(nan, 10.0, nan, nan))
        }
        val after = engine.snapshot()
        assertTrue(after.speedMps == null || after.speedMps!!.isFinite())
        assertTrue(after.speedSigmaMps == null || after.speedSigmaMps!!.isFinite())
        assertTrue(after.totalG == null || after.totalG!!.isFinite())
        assertTrue(abs((after.longitudinalG ?: 0.0) - (before.longitudinalG ?: 0.0)) < 1.0)
    }

    @Test
    fun aPhoneInFreeFallDoesNotBreakAnything() {
        val sim = DriveSimulator(engine)
        sim.run(4.0)
        sim.run(60.0, ::city)
        var time = sim.time
        repeat(400) {
            time += 0.005
            engine.onGyro(time, Vec3.ZERO)
            engine.onAccel(time, Vec3.ZERO)
        }
        val s = engine.snapshot()
        assertTrue(s.totalG == null || s.totalG!!.isFinite())
        assertTrue(s.speedMps == null || s.speedMps!!.isFinite())
    }
}
