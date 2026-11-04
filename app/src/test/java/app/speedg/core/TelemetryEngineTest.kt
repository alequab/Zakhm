package app.speedg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import kotlin.math.abs
import kotlin.math.sqrt

private const val KMH = 3.6

class TelemetryEngineTest {
    private val engine = TelemetryEngine()
    private val sim = DriveSimulator(engine)

    /** Parks, then does some straight-line driving so the engine knows the mount. */
    private fun calibrate() {
        sim.run(4.0)
        sim.run(8.0, { CarMotion(accel = 2.5) })
        sim.run(5.0, { CarMotion(accel = -2.0) })
        sim.run(8.0, { CarMotion(accel = 2.0) })
        sim.run(5.0, { CarMotion() })
        assertEquals(CalibrationState.READY, engine.snapshot().calibration)
    }

    private class Rms {
        private var sum = 0.0
        private var n = 0
        fun add(error: Double) {
            sum += error * error
            n++
        }
        val value get() = sqrt(sum / n)
    }

    @Test
    fun parkedCarReadsExactlyZeroAndFindsDown() {
        sim.run(4.0)
        val s = engine.snapshot()
        assertEquals(0.0, s.speedMps!!, 0.0)
        assertEquals(SpeedSource.STOPPED, s.speedSource)
        assertEquals(CalibrationState.LEARNING_FORWARD, s.calibration)
        assertEquals(0.0, s.totalG!!, 0.03)
    }

    @Test
    fun learnsForwardFromDrivingWithPhoneAtAnAngle() {
        sim.run(4.0)
        assertEquals(CalibrationState.LEARNING_FORWARD, engine.snapshot().calibration)
        calibrate()

        var longError = 0.0
        sim.run(3.0, { CarMotion(accel = 2.943) }) { truth ->
            longError = abs(engine.snapshot().longitudinalG!! - truth.longitudinalG)
        }
        assertTrue("forward axis is off: G error $longError", longError < 0.05)
    }

    @Test
    fun speedTracksHardAccelerationBetterThanOneKmh() {
        calibrate()
        val rms = Rms()
        var skip = 0
        sim.run(10.0, { CarMotion(accel = 3.0) }) { truth ->
            if (skip++ > 100) rms.add(engine.snapshot().speedMps!! - truth.speed)
        }
        assertTrue("speed RMS error ${rms.value * KMH} km/h", rms.value * KMH < 1.0)
    }

    @Test
    fun fusedSpeedHasLessLagThanRawGps() {
        calibrate()
        val fused = Rms()
        val raw = Rms()
        val delayed = ArrayDeque<Double>()
        var skip = 0
        sim.run(8.0, { CarMotion(accel = 3.0) }) { truth ->
            // Raw GPS at 1 Hz with 0.3 s latency is on average 0.8 s behind.
            delayed.addLast(truth.speed)
            if (delayed.size > 160) {
                val old = delayed.removeFirst()
                if (skip++ > 100) {
                    raw.add(old - truth.speed)
                    fused.add(engine.snapshot().speedMps!! - truth.speed)
                }
            }
        }
        assertTrue("fused ${fused.value} vs raw ${raw.value}", fused.value < raw.value / 3)
    }

    @Test
    fun corneringShowsTheCorrectSideAndSize() {
        calibrate()
        // Hold a constant 15 m/s left turn at 0.3 rad/s: 4.5 m/s^2, or 0.46 G.
        val speedNow = sim.speed
        val settle = (15.0 - speedNow) / 2.0
        sim.run(abs(settle) + 0.01, { CarMotion(accel = if (settle > 0) 2.0 else -2.0) })
        sim.run(3.0)
        val rms = Rms()
        var skip = 0
        sim.run(6.0, { CarMotion(yawRate = 0.3) }) { truth ->
            if (skip++ > 200) rms.add(engine.snapshot().lateralG!! - truth.lateralG)
        }
        assertTrue("lateral RMS error ${rms.value} G", rms.value < 0.04)
        assertTrue("left turn should be positive", engine.snapshot().lateralG!! > 0.3)

        sim.run(6.0, { CarMotion(yawRate = -0.3) })
        assertTrue("right turn should be negative", engine.snapshot().lateralG!! < -0.3)
    }

    @Test
    fun steadyClimbDoesNotLookLikeAcceleration() {
        calibrate()
        val grade = 0.10
        // Ease onto a 10% grade over 5 s at constant speed, then hold it.
        sim.run(5.0, { t -> CarMotion(pitch = grade * t / 5.0) })
        val rms = Rms()
        sim.run(20.0, { CarMotion(pitch = grade) }) { truth ->
            rms.add(engine.snapshot().longitudinalG!! - truth.longitudinalG)
        }
        // Uncorrected, a 10% grade reads as about 0.1 G.
        assertTrue("hill error ${rms.value} G", rms.value < 0.02)
    }

    @Test
    fun shortGpsLossIsFlaggedAndStaysClose() {
        calibrate()
        sim.gpsEnabled = false
        var worst = 0.0
        sim.run(8.0, { CarMotion(accel = 0.5) }) { truth ->
            val s = engine.snapshot()
            if (s.speedMps != null) worst = maxOf(worst, abs(s.speedMps!! - truth.speed))
        }
        assertEquals(SpeedSource.ESTIMATED, engine.snapshot().speedSource)
        assertTrue("error during outage ${worst * KMH} km/h", worst * KMH < 5.0)
    }

    @Test
    fun longGpsLossStopsShowingAnyNumber() {
        calibrate()
        sim.gpsEnabled = false
        sim.run(16.0)
        val s = engine.snapshot()
        assertEquals(SpeedSource.NO_SIGNAL, s.speedSource)
        assertNull(s.speedMps)
    }

    @Test
    fun noNumberBeforeTheFirstSpeedFix() {
        sim.gpsEnabled = false
        sim.run(5.0)
        val s = engine.snapshot()
        assertEquals(SpeedSource.ACQUIRING, s.speedSource)
        assertNull(s.speedMps)
    }

    @Test
    fun wildGpsSpikeIsIgnored() {
        calibrate()
        val speedBefore = engine.snapshot().speedMps!!
        engine.onSpeedFix(SpeedFix(sim.time, speedBefore + 40.0, 0.3, 4.0))
        assertEquals(speedBefore, engine.snapshot().speedMps!!, 0.5)
    }

    @Test
    fun rejectedSpikeDoesNotCountAsFreshGps() {
        calibrate()
        sim.gpsEnabled = false
        sim.run(4.0)
        assertEquals(SpeedSource.ESTIMATED, engine.snapshot().speedSource)
        engine.onSpeedFix(SpeedFix(sim.time, sim.speed + 40.0, 0.3, 4.0))
        assertEquals(SpeedSource.ESTIMATED, engine.snapshot().speedSource)
    }

    @Test
    fun fixStampedOnADifferentClockStillWorks() {
        sim.gpsEnabled = false
        sim.run(3.0)
        engine.onSpeedFix(SpeedFix(sim.time + 5000.0, 10.0, 0.3, 4.0))
        sim.run(0.3)
        val s = engine.snapshot()
        assertEquals(SpeedSource.LIVE, s.speedSource)
        assertEquals(10.0, s.speedMps!!, 0.01)
    }

    @Test
    fun unreliableSpeedFixIsNotUsed() {
        sim.gpsEnabled = false
        sim.run(3.0)
        engine.onSpeedFix(SpeedFix(sim.time, 20.0, 8.0, 40.0))
        assertEquals(SpeedSource.ACQUIRING, engine.snapshot().speedSource)
    }

    @Test
    fun stoppingAtALightReturnsToExactlyZero() {
        calibrate()
        while (sim.speed > 0.0) sim.run(0.5, { CarMotion(accel = -3.0) })
        sim.run(3.0)
        val s = engine.snapshot()
        assertEquals(SpeedSource.STOPPED, s.speedSource)
        assertEquals(0.0, s.speedMps!!, 0.0)
    }

    @Test
    fun peaksRecordBrakingAndReset() {
        calibrate()
        engine.resetPeaks()
        sim.run(2.0, { CarMotion(accel = -5.0) })
        assertEquals(0.51, engine.snapshot().peaks.brake, 0.06)
        engine.resetPeaks()
        assertEquals(0.0, engine.snapshot().peaks.brake, 0.0)
    }

    @Test
    fun movingThePhoneInItsMountTriggersRecalibration() {
        calibrate()
        val second = DriveSimulator(
            engine,
            carX = Vec3(1.0, 0.0, 0.0),
            carY = Vec3(0.0, 1.0, 0.0),
            carZ = Vec3(0.0, 0.0, 1.0),
        )
        // The car has stopped and the phone now sits flat instead of tilted.
        while (sim.speed > 0.0) sim.run(0.5, { CarMotion(accel = -3.0) })
        sim.run(3.0)
        val offset = sim.time - second.time
        second.run(offset + 4.0)
        assertEquals(CalibrationState.LEARNING_FORWARD, engine.snapshot().calibration)
    }

    @Test
    fun calibrateButtonStartsOver() {
        calibrate()
        while (sim.speed > 0.0) sim.run(0.5, { CarMotion(accel = -3.0) })
        sim.run(3.0)
        engine.requestCalibration()
        assertEquals(CalibrationState.WAITING_FOR_STOP, engine.snapshot().calibration)
        sim.run(3.0)
        assertEquals(CalibrationState.LEARNING_FORWARD, engine.snapshot().calibration)
        assertNotNull(engine.snapshot().totalG)
    }

    @Test
    fun driveLogFormatRoundTrips() {
        val text = listOf(
            DriveLog.HEADER,
            DriveLog.accel(1_000_000_000L, 0f, 0f, 9.8f),
            DriveLog.gyro(1_005_000_000L, 0.01f, 0f, 0f),
            DriveLog.gps(1_010_000_000L, 12.5f, null, 3f),
        ).joinToString("\n")
        val times = mutableListOf<Double>()
        DriveLog.replay(BufferedReader(StringReader(text)), engine) { times += it }
        assertEquals(listOf(1.0, 1.005, 1.01), times)
        assertEquals(3.0, engine.snapshot().gpsHorizontalAccuracyM!!, 0.0)
    }

    @Test
    fun carSpeedReadingsDriveTheSpeedAndReplay() {
        val text = listOf(
            DriveLog.accel(1_000_000_000L, 0f, 0f, 9.8f),
            DriveLog.car(1_010_000_000L, 16.7f, 0.3f),
        ).joinToString("\n")
        DriveLog.replay(BufferedReader(StringReader(text)), engine)
        val s = engine.snapshot()
        assertEquals(SpeedSource.LIVE, s.speedSource)
        assertEquals(16.7, s.speedMps!!, 0.01)
    }
}
