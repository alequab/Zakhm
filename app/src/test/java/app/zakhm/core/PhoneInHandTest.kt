package app.zakhm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Picking the phone up mid-drive must pause G-force, not record the hand's movements. */
class PhoneInHandTest {
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

    private fun calibratedDrive(): DriveSimulator {
        val sim = DriveSimulator(engine)
        sim.run(4.0)
        sim.run(60.0, ::city)
        assertEquals(CalibrationState.READY, engine.snapshot().calibration)
        return sim
    }

    /** The same car and mount, with a hand moving the phone about as hard as measured on a real drive. */
    private fun inHand(sim: DriveSimulator) =
        DriveSimulator(engine, handling = 3.0, startSpeed = sim.speed, startTime = sim.time, seed = 7)

    @Test
    fun pickingUpThePhonePausesGForceWithinASecond() {
        val sim = calibratedDrive()
        val hand = inHand(sim)
        hand.run(1.0)
        val s = engine.snapshot()
        assertTrue("picking up the phone should be noticed", s.phoneInHand)
        assertNull("G-force should be hidden while the phone is in hand", s.totalG)
        hand.run(5.0)
        assertTrue(engine.snapshot().phoneInHand)
    }

    @Test
    fun theHandsMovementsDoNotBecomePeaks() {
        val sim = calibratedDrive()
        engine.resetPeaks()
        sim.run(5.0)
        val before = engine.currentPeaks()
        inHand(sim).run(6.0)
        val after = engine.currentPeaks()
        assertEquals(before.accel, after.accel, 0.02)
        assertEquals(before.brake, after.brake, 0.02)
        assertEquals(before.left, after.left, 0.02)
        assertEquals(before.right, after.right, 0.02)
    }

    @Test
    fun readingsComeBackWhenThePhoneIsPutBackTheSameWay() {
        val sim = calibratedDrive()
        val hand = inHand(sim)
        hand.run(5.0)
        val back = DriveSimulator(engine, startSpeed = hand.speed, startTime = hand.time, seed = 9)
        back.run(4.0)
        val s = engine.snapshot()
        assertFalse(s.phoneInHand)
        assertEquals("put back the same way, the calibration should be kept", CalibrationState.READY, s.calibration)
        assertTrue(s.totalG != null)
    }

    @Test
    fun aPhonePutBackDifferentlyIsCalibratedAgain() {
        val sim = calibratedDrive()
        val hand = inHand(sim)
        hand.run(4.0)
        val axes = Triple(Vec3(0.0, 0.0, -1.0), Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0))
        val moved = DriveSimulator(engine, axes.first, axes.second, axes.third, startSpeed = hand.speed, startTime = hand.time)
        moved.run(10.0)
        assertNotEquals(CalibrationState.READY, engine.snapshot().calibration)
    }

    @Test
    fun speedStaysRightWhileThePhoneIsInHand() {
        val sim = DriveSimulator(engine, startSpeed = 15.0)
        sim.run(4.0)
        sim.run(60.0, ::city)
        val hand = DriveSimulator(engine, handling = 3.0, startSpeed = sim.speed, startTime = sim.time, seed = 7)
        var worst = 0.0
        hand.run(10.0, { CarMotion(accel = 0.5) }) { truth ->
            engine.snapshot().speedMps?.let { worst = maxOf(worst, kotlin.math.abs(it - truth.speed)) }
        }
        assertTrue("speed was off by $worst m/s", worst < 1.5)
    }

    @Test
    fun hardDrivingOnARoughRoadIsNotMistakenForAHand() {
        val sim = DriveSimulator(engine, mountShake = 3.0, idleVibration = 0.5)
        sim.run(4.0)
        sim.run(60.0, ::city)
        var everInHand = false
        sim.run(120.0, { t ->
            val phase = t % 30.0
            when {
                phase < 5 -> CarMotion(accel = 3.5)
                // Quick changes of direction, like a roundabout or a slalom.
                phase < 12 -> CarMotion(yawRate = 0.5 * sin(2 * PI * t / 3.0))
                phase < 16 -> CarMotion(accel = -6.0)
                phase < 22 -> CarMotion(accel = 1.0, yawRate = -0.45)
                else -> CarMotion(accel = -1.0)
            }
        }) {
            if (engine.snapshot().phoneInHand) everInHand = true
        }
        assertFalse("hard driving must not count as a phone in hand", everInHand)
    }
}
