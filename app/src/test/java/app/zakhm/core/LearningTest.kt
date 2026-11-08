package app.zakhm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** What the app learns about each car: source delays, the speedometer's error, and its vibration. */
class LearningTest {
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

    /** Busier driving on faster roads: speeds between about 40 and 80 km/h. */
    private fun mainRoad(t: Double): CarMotion {
        val phase = t % 30.0
        return when {
            phase < 5 -> CarMotion(accel = 0.8)
            phase < 15 -> CarMotion()
            phase < 20 -> CarMotion(accel = -0.8)
            else -> CarMotion()
        }
    }

    @Test
    fun theDelayOfTheCarsSpeedIsMeasured() {
        val sim = DriveSimulator(engine, obd = DriveSimulator.SimulatedObd(rate = 5.0, lag = 0.6))
        sim.run(4.0)
        sim.run(300.0, ::city)
        val lag = engine.learnedCar().carLag
        println("measured OBD delay: $lag s (true 0.6)")
        assertNotNull(lag)
        assertEquals(0.6, lag!!, 0.1)
    }

    @Test
    fun theDelayOfGpsSpeedIsMeasured() {
        val sim = DriveSimulator(engine, gpsSpeedLag = 0.8)
        sim.run(4.0)
        sim.run(400.0, ::city)
        val lag = engine.learnedCar().gpsLag
        println("measured GPS delay: $lag s (true 0.8)")
        assertNotNull(lag)
        assertEquals(0.8, lag!!, 0.15)
    }

    @Test
    fun aSpeedometerReadingHighIsCorrectedFromGps() {
        val sim = DriveSimulator(engine, startSpeed = 15.0, obd = DriveSimulator.SimulatedObd(lag = 0.3, scale = 1.04))
        sim.run(4.0)
        sim.run(600.0, ::mainRoad)
        val scale = engine.learnedCar().carScale
        println("learned correction: $scale (true 1/1.04 = ${1 / 1.04})")
        assertNotNull(scale)
        assertEquals(1 / 1.04, scale!!, 0.01)
        var worst = 0.0
        sim.run(30.0, ::mainRoad) { truth -> worst = maxOf(worst, abs(engine.snapshot().speedMps!! - truth.speed)) }
        assertTrue("speed off by $worst m/s after correction", worst < 0.8)
    }

    @Test
    fun correctedTimingMakesCarSpeedMoreAccurate() {
        fun worstError(learn: Boolean): Double {
            val e = TelemetryEngine()
            val sim = DriveSimulator(e, obd = DriveSimulator.SimulatedObd(rate = 5.0, lag = 0.8))
            sim.run(4.0)
            sim.run(if (learn) 300.0 else 60.0, ::city)
            var worst = 0.0
            sim.run(40.0, ::city) { truth -> worst = maxOf(worst, abs(e.snapshot().speedMps!! - truth.speed)) }
            return worst
        }
        val before = worstError(learn = false)
        val after = worstError(learn = true)
        println("worst speed error with OBD 0.8 s late: before learning $before m/s, after $after m/s")
        assertTrue("after $after, before $before", after < before)
    }

    /** Drives a car with real road rumble until the app has learned its parked and rolling vibration. */
    private fun learnedVibration(): DriveSimulator {
        val sim = DriveSimulator(engine, roadRumble = 0.4)
        sim.run(4.0)
        repeat(6) {
            sim.run(40.0, ::city)
            // A stop of 10 s, as at traffic lights.
            while (sim.speed > 0.0) sim.run(0.1, { CarMotion(accel = -1.5) })
            sim.run(10.0)
            // Away slowly through the speeds where GPS is clear enough to learn from.
            sim.run(5.0, { CarMotion(accel = 1.0) })
            sim.run(5.0)
        }
        assertTrue(engine.learningSummary(), engine.learningSummary().contains("ready=true"))
        return sim
    }

    @Test
    fun aParkedCarIsStoppedEvenWhenGpsReportsSomeSpeed() {
        val sim = learnedVibration()
        while (sim.speed > 0.0) sim.run(0.1, { CarMotion(accel = -1.5) })
        sim.run(5.0)
        // Measured on a real drive: GPS reported up to 9 km/h while the car stood still.
        sim.gpsError = 2.5
        sim.run(10.0)
        val s = engine.snapshot()
        assertTrue("a still car should count as stopped", s.stopped)
        assertEquals(0.0, s.speedMps!!, 0.01)
    }

    @Test
    fun aSmoothPullAwayIsNotTakenForParked() {
        val sim = learnedVibration()
        while (sim.speed > 0.0) sim.run(0.1, { CarMotion(accel = -1.5) })
        sim.run(10.0)
        var stoppedAt = 0.0
        sim.run(6.0, { CarMotion(accel = 0.5) }) { truth -> if (engine.snapshot().stopped) stoppedAt = truth.speed }
        assertTrue("still counted as stopped at ${stoppedAt * 3.6} km/h", stoppedAt < 1.0)
        assertEquals(sim.speed, engine.snapshot().speedMps!!, 0.8)
    }

    @Test
    fun withoutEnoughDifferenceTheVibrationIsNotUsed() {
        // No road rumble: parked and rolling feel the same, so the model must stay out of it.
        val sim = DriveSimulator(engine)
        sim.run(4.0)
        repeat(4) {
            sim.run(40.0, ::city)
            while (sim.speed > 0.0) sim.run(0.1, { CarMotion(accel = -1.5) })
            sim.run(10.0)
        }
        assertTrue(engine.learningSummary().contains("ready=false"))
        sim.gpsError = 2.5
        sim.run(10.0)
        assertFalse("GPS should still decide when vibration can't tell", engine.snapshot().stopped)
    }

    @Test
    fun whatWasLearnedIsSavedWithTheCar() {
        val learned = LearnedCar(
            stillness = StillnessStats(RunningStats(30.0, -3.2, 2.0), RunningStats(30.0, -6.9, 1.5), RunningStats(25.0, -1.3, 3.0), RunningStats(25.0, -5.2, 2.0)),
            carLag = 0.55,
            gpsLag = 0.8,
            carScale = 0.97,
        )
        val car = CarProfile("a", "Picanto", learned = learned)
        val back = CarProfileJson.decode(CarProfileJson.encode(listOf(car), "a"))!!.cars.single()
        assertEquals(learned, back.learned)

        // Older saved cars have none of this, which must still load.
        val old = """{"version":1,"activeId":"a","cars":[{"id":"a","name":"Old","input":"PHONE"}]}"""
        assertEquals(LearnedCar(), CarProfileJson.decode(old)!!.cars.single().learned)
    }

    @Test
    fun aSavedDelayIsUsedFromTheStartOfTheNextDrive() {
        engine.loadCar(null, Peaks(), LearnedCar(carLag = 0.5, carScale = 0.98))
        assertEquals(0.5, engine.learnedCar().carLag!!, 1e-9)
        assertEquals(0.98, engine.learnedCar().carScale!!, 1e-9)
    }
}
