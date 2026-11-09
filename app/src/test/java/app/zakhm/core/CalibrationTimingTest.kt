package app.zakhm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** How long calibration takes in the kinds of driving people actually do. */
class CalibrationTimingTest {
    private fun secondsUntilReady(
        engine: TelemetryEngine,
        sim: DriveSimulator,
        limitSeconds: Double,
        motion: (Double) -> CarMotion,
    ): Double {
        val start = sim.time
        while (sim.time - start < limitSeconds) {
            val offset = sim.time - start
            sim.run(0.5, { t -> motion(offset + t) })
            if (engine.snapshot().calibration == CalibrationState.READY) return sim.time - start
        }
        return Double.POSITIVE_INFINITY
    }

    /** Gentle speed changes, the way the on-screen hint asks for. */
    private fun gentle(t: Double) = CarMotion(accel = 0.6 * sin(2 * PI * t / 20.0) + 0.05)

    /** City driving: pull away, cruise, a turn, then brake to a stop, again and again. */
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

    private fun report(name: String, seconds: Double) = println("calibration time, $name: $seconds s")

    @Test
    fun gentleDrivingCalibratesWithinAMinute() {
        val engine = TelemetryEngine()
        val sim = DriveSimulator(engine)
        sim.run(4.0)
        val seconds = secondsUntilReady(engine, sim, 300.0, ::gentle)
        report("gentle", seconds)
        assertTrue("gentle driving took $seconds s", seconds < 60.0)
    }

    @Test
    fun cityDrivingCalibratesWithinAMinute() {
        val engine = TelemetryEngine()
        val sim = DriveSimulator(engine)
        sim.run(4.0)
        val seconds = secondsUntilReady(engine, sim, 300.0, ::city)
        report("city", seconds)
        assertTrue("city driving took $seconds s", seconds < 60.0)
    }

    @Test
    fun turningAloneCanTeachForward() {
        val engine = TelemetryEngine()
        val sim = DriveSimulator(engine, startSpeed = 10.0)
        sim.run(8.0)
        val seconds = secondsUntilReady(engine, sim, 120.0) { t ->
            val direction = if ((t % 16.0) < 8.0) 1.0 else -1.0
            CarMotion(yawRate = 0.25 * direction)
        }
        report("turns only", seconds)
        assertTrue("turns took $seconds s", seconds < 45.0)
    }

    @Test
    fun forwardLearnedFromTurnsIsCorrect() {
        val engine = TelemetryEngine()
        val sim = DriveSimulator(engine, startSpeed = 10.0)
        sim.run(8.0)
        secondsUntilReady(engine, sim, 120.0) { t ->
            val direction = if ((t % 16.0) < 8.0) 1.0 else -1.0
            CarMotion(yawRate = 0.25 * direction)
        }
        assertEquals(CalibrationState.READY, engine.snapshot().calibration)
        val learned = engine.mountCalibration()!!.forward!!
        val truth = DriveSimulator.MOUNT_TILTED.first
        assertTrue("forward from turns is off, dot=${learned dot truth}", (learned dot truth) > 0.97)
    }

    @Test
    fun aSingleSpeedUpDoesNotFinishCalibration() {
        // On a real drive, forward learned from one speed-up read the braking after it as speeding up.
        val engine = TelemetryEngine()
        val sim = DriveSimulator(engine)
        sim.run(4.0)
        sim.run(12.0, { CarMotion(accel = 1.2) })
        assertTrue(
            "one speed-up mustn't be enough, was ${engine.snapshot().calibration}",
            engine.snapshot().calibration != CalibrationState.READY,
        )
        // Slowing down afterwards gives the other direction.
        val seconds = secondsUntilReady(engine, sim, 60.0) { CarMotion(accel = -1.0) }
        println("calibration time after slowing down: $seconds s")
        assertTrue("slowing down took $seconds s", seconds < 10.0)
        val dot = engine.mountCalibration()!!.forward!! dot DriveSimulator.MOUNT_TILTED.first
        assertTrue("forward off, dot=$dot", dot > 0.98)
    }

    @Test
    fun aConstantErrorDoesNotTurnForward() {
        // The phone's sense of "down" a few degrees off adds a constant push that a single
        // direction can't tell apart from forward. With both, it cancels.
        val engine = TelemetryEngine()
        val tilt = Math.toRadians(4.0)
        val carX = DriveSimulator.MOUNT_TILTED.first
        val carY = DriveSimulator.MOUNT_TILTED.second
        val carZ = DriveSimulator.MOUNT_TILTED.third
        // A mount that sags a little once moving: the phone's axes as seen while driving.
        val sagged = Triple(carX * kotlin.math.cos(tilt) + carZ * kotlin.math.sin(tilt), carY, carZ * kotlin.math.cos(tilt) - carX * kotlin.math.sin(tilt))
        val parked = DriveSimulator(engine)
        parked.run(4.0)
        val moving = DriveSimulator(engine, sagged.first, sagged.second, sagged.third, startTime = parked.time)
        secondsUntilReady(engine, moving, 120.0, ::city)
        assertEquals(CalibrationState.READY, engine.snapshot().calibration)
        val dot = engine.mountCalibration()!!.forward!! dot sagged.first
        assertTrue("forward off, dot=$dot", dot > 0.98)
    }

    @Test
    fun aPhoneBeingHandledIsNotAStoppedCar() {
        val engine = TelemetryEngine()
        val sim = DriveSimulator(engine, idleVibration = 0.0, handling = 0.9)
        sim.run(6.0)
        assertEquals(CalibrationState.WAITING_FOR_STOP, engine.snapshot().calibration)
    }

    @Test
    fun idlingEngineVibrationStillCountsAsStopped() {
        val engine = TelemetryEngine()
        val sim = DriveSimulator(engine, idleVibration = 0.5)
        sim.run(6.0)
        assertEquals(
            "a running engine shakes the phone, but the car is stopped",
            CalibrationState.LEARNING_FORWARD,
            engine.snapshot().calibration,
        )
    }

    @Test
    fun startingTheAppWhileAlreadyDrivingStillFindsDown() {
        val engine = TelemetryEngine()
        val sim = DriveSimulator(engine, startSpeed = 20.0)
        sim.run(15.0)
        assertTrue(
            "should not wait for a stop, was ${engine.snapshot().calibration}",
            engine.snapshot().calibration != CalibrationState.WAITING_FOR_STOP,
        )
    }

    @Test
    fun calibrationFromMovingGivesCorrectGForce() {
        val engine = TelemetryEngine()
        val sim = DriveSimulator(engine, startSpeed = 20.0)
        sim.run(15.0)
        secondsUntilReady(engine, sim, 120.0, ::city)
        assertEquals(CalibrationState.READY, engine.snapshot().calibration)
        var error = 0.0
        sim.run(3.0, { CarMotion(accel = 2.943) }) { truth ->
            error = kotlin.math.abs(engine.snapshot().longitudinalG!! - truth.longitudinalG)
        }
        assertTrue("G error $error", error < 0.05)
    }
}
