package app.zakhm.core

import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/** What the car is doing at one moment. Axes: x forward, y left, z up. */
data class CarMotion(
    val accel: Double = 0.0,
    val yawRate: Double = 0.0,
    /** Nose-up angle of the road, radians. */
    val pitch: Double = 0.0,
)

data class Truth(
    val time: Double,
    val speed: Double,
    val longitudinalG: Double,
    val lateralG: Double,
)

/**
 * Generates the sensor readings a phone would produce in a car with known motion,
 * so the engine's outputs can be checked against the exact right answer.
 */
class DriveSimulator(
    private val engine: TelemetryEngine,
    /** Rotates a car-frame vector into the phone's frame. Axes are rows: car x, y, z in phone coordinates. */
    private val carX: Vec3 = MOUNT_TILTED.first,
    private val carY: Vec3 = MOUNT_TILTED.second,
    private val carZ: Vec3 = MOUNT_TILTED.third,
    private val accelBias: Vec3 = Vec3(0.04, -0.03, 0.05),
    private val gyroBias: Vec3 = Vec3(0.002, -0.001, 0.0015),
    private val accelNoise: Double = 0.08,
    private val gyroNoise: Double = 0.003,
    private val gpsNoise: Double = 0.15,
    private val gpsLatency: Double = 0.3,
    /** Strength of a running engine's 25 Hz shake, in m/s^2. */
    private val idleVibration: Double = 0.0,
    /** Strength of someone slowly moving the phone around by hand, in m/s^2. */
    private val handling: Double = 0.0,
    /** Strength of a loose mount shaking at 8 Hz, in m/s^2. */
    private val mountShake: Double = 0.0,
    startSpeed: Double = 0.0,
    seed: Long = 42,
    startTime: Double = 100.0,
    /** How late the GPS chip's speed is: it reports the speed of this many seconds earlier. */
    private val gpsSpeedLag: Double = 0.0,
    /** An OBD adapter, or null for none. */
    private val obd: SimulatedObd? = null,
    /** Strength of the road's shake while rolling, in m/s^2. A parked car has none. */
    private val roadRumble: Double = 0.0,
) {
    /** Reports [scale] times the speed of [lag] seconds earlier, [rate] times a second, in whole km/h. */
    data class SimulatedObd(val rate: Double = 5.0, val lag: Double = 0.6, val scale: Double = 1.0)

    /** Added to every GPS speed, for a receiver reporting speed the car doesn't have. */
    var gpsError = 0.0

    private val pastSpeeds = ArrayDeque<Pair<Double, Double>>()
    private var nextObdTime = startTime

    private fun speedAgo(seconds: Double): Double {
        val target = time - seconds
        return pastSpeeds.lastOrNull { it.first <= target }?.second ?: pastSpeeds.firstOrNull()?.second ?: speed
    }
    private val random = Random(seed)
    private val imuRate = 200
    private val dt = 1.0 / imuRate

    var time = startTime
        private set
    var speed = startSpeed
        private set
    var pitch = 0.0
        private set
    var gpsEnabled = true

    private var nextGpsTime = time
    private val pendingFixes = ArrayDeque<SpeedFix>()

    /** Runs [seconds] of motion, calling [observe] with the truth after each IMU sample. */
    fun run(seconds: Double, motion: (Double) -> CarMotion = { CarMotion() }, observe: (Truth) -> Unit = {}) {
        val start = time
        repeat((seconds * imuRate).toInt()) {
            val m = motion(time - start)
            step(m)
            observe(
                Truth(
                    time = time,
                    speed = speed,
                    longitudinalG = m.accel / STANDARD_GRAVITY,
                    lateralG = speed * m.yawRate / STANDARD_GRAVITY,
                ),
            )
        }
    }

    private fun step(m: CarMotion) {
        time += dt
        val previousPitch = pitch
        pitch = m.pitch
        speed = (speed + m.accel * dt).coerceAtLeast(0.0)
        val moving = speed > 0.0

        // Nose-up rotation is negative about the car's left-pointing y axis.
        val pitchRate = (pitch - previousPitch) / dt
        val gyroCar = Vec3(0.0, -pitchRate, m.yawRate)
        val forceCar = Vec3(
            m.accel + STANDARD_GRAVITY * sin(pitch),
            speed * m.yawRate,
            STANDARD_GRAVITY * cos(pitch),
        )
        val vibration = if (moving) accelNoise else accelNoise / 2
        val shake = sin(2 * Math.PI * 25.0 * time)
        val sway = sin(2 * Math.PI * 0.7 * time)
        val wobble = mountShake * sin(2 * Math.PI * 8.0 * time)
        // Road surface: slow, uneven bumps that only a rolling car feels.
        val rumble = if (speed > 0.05) roadRumble * (sin(2 * Math.PI * 1.3 * time) + 0.7 * sin(2 * Math.PI * 1.9 * time + 1.0)) else 0.0
        val shakeForce = Vec3(idleVibration * shake + handling * sway + wobble, idleVibration * shake * 0.5 + wobble, idleVibration * shake + rumble)
        val shakeGyro = Vec3(0.06 * shake * idleVibration + 0.4 * sway * handling + 0.02 * rumble, 0.01 * rumble, 0.03 * shake * idleVibration)
        engine.onGyro(time, toPhone(gyroCar + shakeGyro) + gyroBias + noise(gyroNoise))
        engine.onAccel(time, toPhone(forceCar + shakeForce) + accelBias + noise(vibration))

        pastSpeeds.addLast(time to speed)
        while (pastSpeeds.size > 2 && pastSpeeds.first().first < time - 5.0) pastSpeeds.removeFirst()

        if (time >= nextGpsTime) {
            nextGpsTime += 1.0
            if (gpsEnabled) {
                val measured = (speedAgo(gpsSpeedLag) + gpsError + random.nextGaussian() * gpsNoise).coerceAtLeast(0.0)
                pendingFixes.addLast(SpeedFix(time, measured, 0.3, 4.0))
            }
        }
        if (obd != null && time >= nextObdTime) {
            nextObdTime += 1.0 / obd.rate
            // Whole km/h, as the standard request gives.
            val kmh = Math.round(speedAgo(obd.lag) * obd.scale * 3.6).toDouble()
            engine.onSpeedFix(SpeedFix(time, kmh / 3.6, 0.3, null, FixSource.CAR))
        }
        while (pendingFixes.isNotEmpty() && time >= pendingFixes.first().time + gpsLatency) {
            engine.onSpeedFix(pendingFixes.removeFirst())
        }
    }

    private fun toPhone(v: Vec3) = carX * v.x + carY * v.y + carZ * v.z

    private fun noise(sigma: Double) = Vec3(
        random.nextGaussian() * sigma,
        random.nextGaussian() * sigma,
        random.nextGaussian() * sigma,
    )

    companion object {
        /** A phone leaning back about 30 degrees and rotated about 20 degrees on its mount. */
        val MOUNT_TILTED: Triple<Vec3, Vec3, Vec3> = run {
            val pitch = Math.toRadians(30.0)
            val roll = Math.toRadians(20.0)
            val x = Vec3(cos(roll), sin(roll), 0.0)
            val y0 = Vec3(-sin(roll), cos(roll), 0.0)
            val z0 = Vec3(0.0, 0.0, 1.0)
            val y = y0 * cos(pitch) + z0 * sin(pitch)
            val z = z0 * cos(pitch) - y0 * sin(pitch)
            Triple(x, y, z)
        }
    }
}
