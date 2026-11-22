package app.zakhm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sin

/**
 * How fast and how exactly the phone's angle in the car is found while driving, with an
 * OBD adapter reading whole km/h five times a second, as in the owner's cars.
 */
class AngleAccuracyTest {
    private val obd = DriveSimulator.SimulatedObd()
    private val truthUp = DriveSimulator.MOUNT_TILTED.third

    private fun degreesOff(a: Vec3, b: Vec3) = Math.toDegrees(acos((a dot b).coerceIn(-1.0, 1.0)))

    /** Seconds of driving until "down" is known, or infinity. */
    private fun secondsUntilDown(engine: TelemetryEngine, sim: DriveSimulator, limit: Double, motion: (Double) -> CarMotion): Double {
        val start = sim.time
        while (sim.time - start < limit) {
            val offset = sim.time - start
            sim.run(0.25, { t -> motion(offset + t) })
            if (engine.snapshot().calibration != CalibrationState.WAITING_FOR_STOP) return sim.time - start
        }
        return Double.POSITIVE_INFINITY
    }

    @Test
    fun findsDownQuicklyWhileDrivingStraightAtAChangingSpeed() {
        // Speed never steady for long, never stopping: the stop and the steady cruise both wait.
        val engine = TelemetryEngine()
        val sim = DriveSimulator(engine, startSpeed = 15.0, obd = obd)
        val seconds = secondsUntilDown(engine, sim, 120.0) { t -> CarMotion(accel = 0.5 * sin(2 * PI * t / 12.0)) }
        val up = engine.mountCalibration()?.up
        val off = up?.let { degreesOff(it, truthUp) }
        println("down while driving: $seconds s, off by $off deg")
        assertTrue("finding down took $seconds s", seconds < 15.0)
        assertTrue("down off by $off deg", off != null && off < 2.0)
    }

    @Test
    fun findsDownQuicklyInCityTrafficWithoutStopping() {
        val engine = TelemetryEngine()
        val sim = DriveSimulator(engine, startSpeed = 12.0, obd = obd)
        val seconds = secondsUntilDown(engine, sim, 120.0) { t ->
            val phase = t % 20.0
            when {
                phase < 4 -> CarMotion(accel = 1.0)
                phase < 8 -> CarMotion(yawRate = 0.2)
                phase < 12 -> CarMotion(accel = -1.0)
                else -> CarMotion(accel = 0.3 * sin(2 * PI * t / 5.0))
            }
        }
        val off = engine.mountCalibration()?.up?.let { degreesOff(it, truthUp) }
        println("down in city traffic: $seconds s, off by $off deg")
        assertTrue("finding down took $seconds s", seconds < 20.0)
        assertTrue("down off by $off deg", off != null && off < 2.0)
    }

    @Test
    fun steadyDrivingBeforeForwardIsKnownKeepsTheAngle() {
        // A long steady stretch teaches nothing about forward, so the gyroscope alone carries
        // gravity. Its small bias must not add up to a false "phone was moved".
        val engine = TelemetryEngine()
        val sim = DriveSimulator(engine, startSpeed = 20.0, obd = obd)
        sim.run(240.0)
        println("losses after 4 min steady: ${engine.calibrationLosses} (${engine.lastCalibrationLoss})")
        assertEquals("calibration lost: ${engine.lastCalibrationLoss}", 0, engine.calibrationLosses)
        assertTrue("down should be known", engine.snapshot().calibration != CalibrationState.WAITING_FOR_STOP)
        // And once forward is learned, the readings are right straight away.
        var error = Double.NaN
        val start = sim.time
        while (sim.time - start < 120.0 && engine.snapshot().calibration != CalibrationState.READY) {
            val offset = sim.time - start
            sim.run(0.5, { t -> CarMotion(accel = 1.0 * sin(2 * PI * (offset + t) / 10.0)) })
        }
        assertEquals(CalibrationState.READY, engine.snapshot().calibration)
        sim.run(3.0, { CarMotion(accel = 2.0) }) { truth ->
            error = abs(engine.snapshot().longitudinalG!! - truth.longitudinalG)
        }
        println("G error after steady stretch: $error")
        assertTrue("G error $error", error < 0.05)
    }

    @Test
    fun forwardAndGForceAreExactWithTheCarsOwnSpeed() {
        val engine = TelemetryEngine()
        val sim = DriveSimulator(engine, obd = obd)
        sim.run(3.0)
        val start = sim.time
        while (sim.time - start < 120.0 && engine.snapshot().calibration != CalibrationState.READY) {
            val offset = sim.time - start
            sim.run(0.5, { t -> CarMotion(accel = 1.0 * sin(2 * PI * (offset + t) / 12.0) + 0.1) })
        }
        assertEquals(CalibrationState.READY, engine.snapshot().calibration)
        val off = degreesOff(engine.mountCalibration()!!.forward!!, DriveSimulator.MOUNT_TILTED.first)
        // A little more driving, as on any trip, so the adapter's delay is learned too.
        sim.run(20.0, { t -> CarMotion(accel = 0.8 * sin(2 * PI * t / 10.0)) })
        var accelError = 0.0
        sim.run(3.0, { CarMotion(accel = 2.0) }) { truth -> accelError = abs(engine.snapshot().longitudinalG!! - truth.longitudinalG) }
        var turnError = 0.0
        sim.run(4.0, { CarMotion(yawRate = 0.3) }) { truth -> turnError = abs(engine.snapshot().lateralG!! - truth.lateralG) }
        println("forward off by $off deg after ${sim.time - start} s; G error accel $accelError, turn $turnError")
        assertTrue("forward off by $off deg", off < 3.0)
        assertTrue("G error accel $accelError", accelError < 0.03)
        assertTrue("G error turn $turnError", turnError < 0.03)
    }

    /**
     * A saved car, with the phone put back in its mount 12 degrees off. That's under the
     * 20 degrees that mean "moved", so the saved axes are kept, but they must settle on
     * the new angle at the first stop rather than stay off for the rest of the drive.
     */
    @Test
    fun aKnownCarWithThePhoneSlightlyTiltedSettlesOnTheNewAngle() {
        val engine = TelemetryEngine()
        val saved = DriveSimulator.MOUNT_TILTED
        engine.loadCar(MountCalibration(saved.third, saved.first), Peaks())
        // The same mount tipped back 12 degrees about the car's left axis.
        val tilt = Math.toRadians(12.0)
        val c = kotlin.math.cos(tilt)
        val s = kotlin.math.sin(tilt)
        val x = saved.first * c + saved.third * s
        val z = saved.third * c - saved.first * s
        val sim = DriveSimulator(engine, x, saved.second, z, obd = obd)
        // Waiting at a light.
        sim.run(10.0)
        sim.run(30.0, { t -> CarMotion(accel = 0.8 * sin(2 * PI * t / 10.0)) })
        assertEquals("a small tilt keeps the saved car", 0, engine.calibrationLosses)
        val upOff = degreesOff(engine.mountCalibration()!!.up, z)
        val forwardOff = degreesOff(engine.mountCalibration()!!.forward!!, x)
        var accelError = 0.0
        sim.run(3.0, { CarMotion(accel = 2.0) }) { truth -> accelError = abs(engine.snapshot().longitudinalG!! - truth.longitudinalG) }
        println("known car tilted 12 deg: up off $upOff, forward off $forwardOff, G error $accelError")
        assertTrue("up off by $upOff deg", upOff < 2.0)
        assertTrue("forward off by $forwardOff deg", forwardOff < 3.0)
        assertTrue("G error $accelError", accelError < 0.03)
    }

    @Test
    fun aParkedStartFindsDownExactly() {
        val engine = TelemetryEngine()
        val sim = DriveSimulator(engine, obd = obd)
        sim.run(3.0)
        val off = engine.mountCalibration()?.up?.let { degreesOff(it, truthUp) }
        println("down from a stop: off by $off deg")
        assertTrue("down off by $off deg", off != null && off < 1.0)
    }
}
