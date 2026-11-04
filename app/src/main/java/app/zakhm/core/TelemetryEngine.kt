package app.zakhm.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

const val STANDARD_GRAVITY = 9.80665

/** Where the displayed speed comes from. */
enum class SpeedSource { ACQUIRING, LIVE, STOPPED, ESTIMATED, NO_SIGNAL }

enum class CalibrationState { WAITING_FOR_STOP, LEARNING_FORWARD, READY }

/** A speed reading from GPS or the car. [time] is when it was measured, in seconds on the IMU clock. */
data class SpeedFix(
    val time: Double,
    val speed: Double?,
    val speedAccuracy: Double?,
    val horizontalAccuracy: Double?,
)

data class Peaks(
    val accel: Double = 0.0,
    val brake: Double = 0.0,
    val left: Double = 0.0,
    val right: Double = 0.0,
)

data class TelemetrySnapshot(
    val speedMps: Double? = null,
    val speedSource: SpeedSource = SpeedSource.ACQUIRING,
    val speedSigmaMps: Double? = null,
    val calibration: CalibrationState = CalibrationState.WAITING_FOR_STOP,
    val learningProgress: Double = 0.0,
    /** Positive = speeding up, negative = braking. Null until forward is known. */
    val longitudinalG: Double? = null,
    /** Positive = accelerating toward the car's left. Null until forward is known. */
    val lateralG: Double? = null,
    /** Size of the horizontal G-force. Null until "down" is known. */
    val totalG: Double? = null,
    val peaks: Peaks = Peaks(),
    val stopped: Boolean = false,
    val gpsSpeedAccuracyMps: Double? = null,
    val gpsHorizontalAccuracyM: Double? = null,
)

/**
 * Turns raw accelerometer, gyroscope and GPS readings into speed and G-force.
 *
 * All vectors are in the phone's sensor frame. Three estimates feed each other:
 *  - `mountUp` and `forward` give the car's axes, fixed for as long as the mount doesn't move.
 *  - `gravityUp` is the current direction of gravity. It differs from `mountUp`
 *    on hills and banked roads. The gyroscope carries it from moment to moment,
 *    and GPS-derived motion corrects its drift.
 *  - [SpeedFilter] integrates forward acceleration and is corrected by GPS.
 *
 * Not thread safe: call everything from one thread.
 */
class TelemetryEngine {
    private val history = SampleHistory()
    private val speedFilter = SpeedFilter()
    private val learner = ForwardLearner()

    private var now = 0.0
    private var lastAccelTime = Double.NaN
    private var lastGyroTime = Double.NaN
    private var lastStopCheck = Double.NEGATIVE_INFINITY

    private var rawGyro = Vec3.ZERO
    private var gyroBias = Vec3.ZERO
    private var gravityMagnitude = STANDARD_GRAVITY

    private var mountUp: Vec3? = null
    private var forward: Vec3? = null
    private var gravityUp: Vec3? = null

    private var stopped = false

    private var lastUsableFix: SpeedFix? = null
    private var anchorTime = Double.NaN
    private var anchorSpeed = 0.0
    private var gpsSpeedAccuracy: Double? = null
    private var gpsHorizontalAccuracy: Double? = null

    private var smoothing1 = Vec3.ZERO
    private var smoothing2 = Vec3.ZERO
    private var smoothedAccel: Vec3? = null
    private var peaks = Peaks()

    fun onGyro(time: Double, gyro: Vec3) {
        now = max(now, time)
        rawGyro = gyro
        val previous = lastGyroTime
        lastGyroTime = time
        val up = gravityUp ?: return
        if (previous.isNaN()) return
        val dt = time - previous
        if (dt <= 0.0 || dt > MAX_SENSOR_GAP) return
        // World "up" is fixed, so in the rotating phone frame it turns the opposite way.
        gravityUp = (up + (up cross (gyro - gyroBias)) * dt).normalized()
    }

    fun onAccel(time: Double, force: Vec3) {
        now = max(now, time)
        val previous = lastAccelTime
        lastAccelTime = time
        val dt = if (previous.isNaN()) 0.0 else time - previous
        val validDt = dt > 0.0 && dt <= MAX_SENSOR_GAP

        val up = gravityUp
        val fwd = forward
        if (validDt) {
            val motion = up?.let { force - it * gravityMagnitude }
            speedFilter.predict(dt, if (fwd != null && motion != null) motion dot fwd else null)
            if (motion != null) smoothAndTrackPeaks(motion, dt)
        }

        history.add(time, force, rawGyro, speedFilter.speed - speedFilter.cumulativeCorrection)

        if (time - lastStopCheck >= STOP_CHECK_INTERVAL) {
            lastStopCheck = time
            checkStopped(time)
        }
    }

    fun onSpeedFix(fix: SpeedFix) {
        gpsHorizontalAccuracy = fix.horizontalAccuracy
        gpsSpeedAccuracy = fix.speedAccuracy
        val measured = fix.speed ?: return
        val sigma = fix.speedAccuracy?.takeIf { it > 0.0 } ?: DEFAULT_GPS_SIGMA
        if (sigma > MAX_GPS_SIGMA) return

        // Trust the fix's own timestamp unless it's clearly on a different clock.
        val fixTime = if (fix.time < now - MAX_FIX_AGE || fix.time > now + MAX_FIX_LEAD) now else fix.time
        val predicted = history.speedAt(fixTime)?.plus(speedFilter.cumulativeCorrection)

        val accepted = speedFilter.update(measured, max(sigma, MIN_GPS_SIGMA), predicted ?: speedFilter.speed)
        if (!accepted) return
        now = max(now, fixTime)
        lastUsableFix = fix.copy(time = fixTime)
        learnFromFix(fixTime, measured)
    }

    /** Forget the phone's position in the car and start again. Peaks are kept. */
    fun requestCalibration() {
        resetMount()
        stopped = false
        lastStopCheck = Double.NEGATIVE_INFINITY
    }

    fun resetPeaks() {
        peaks = Peaks()
    }

    fun mountCalibration(): MountCalibration? = mountUp?.let { MountCalibration(it, forward) }

    fun currentPeaks(): Peaks = peaks

    /**
     * Switches to a saved car's calibration and peaks. The calibration is only a
     * starting point: a phone tilted differently is caught at the next stop, and a
     * phone turned differently is corrected by fresh forward learning.
     */
    fun loadCar(mount: MountCalibration?, savedPeaks: Peaks) {
        resetMount()
        mountUp = mount?.up
        gravityUp = mount?.up
        forward = mount?.forward
        peaks = savedPeaks
        stopped = false
        lastStopCheck = Double.NEGATIVE_INFINITY
    }

    fun snapshot(): TelemetrySnapshot {
        val up = mountUp
        val fwd = forward
        val motion = smoothedAccel
        val longitudinal: Double?
        val lateral: Double?
        val total: Double?
        if (up != null && fwd != null && motion != null) {
            val left = up cross fwd
            longitudinal = (motion dot fwd) / STANDARD_GRAVITY
            lateral = (motion dot left) / STANDARD_GRAVITY
            total = motion.perpendicularTo(up).norm / STANDARD_GRAVITY
        } else {
            longitudinal = null
            lateral = null
            total = if (up != null && motion != null) {
                motion.perpendicularTo(up).norm / STANDARD_GRAVITY
            } else {
                null
            }
        }

        val source = speedSource()
        return TelemetrySnapshot(
            speedMps = when (source) {
                SpeedSource.LIVE, SpeedSource.ESTIMATED -> speedFilter.speed
                SpeedSource.STOPPED -> 0.0
                SpeedSource.ACQUIRING, SpeedSource.NO_SIGNAL -> null
            },
            speedSource = source,
            speedSigmaMps = if (speedFilter.initialized) speedFilter.sigma else null,
            calibration = when {
                up == null -> CalibrationState.WAITING_FOR_STOP
                fwd == null -> CalibrationState.LEARNING_FORWARD
                else -> CalibrationState.READY
            },
            learningProgress = learner.progress,
            longitudinalG = longitudinal,
            lateralG = lateral,
            totalG = total,
            peaks = peaks,
            stopped = stopped,
            gpsSpeedAccuracyMps = gpsSpeedAccuracy,
            gpsHorizontalAccuracyM = gpsHorizontalAccuracy,
        )
    }

    private fun speedSource(): SpeedSource {
        val fix = lastUsableFix ?: return SpeedSource.ACQUIRING
        if (stopped) return SpeedSource.STOPPED
        val age = now - fix.time
        return when {
            age <= GPS_FRESH_SECONDS -> SpeedSource.LIVE
            forward != null && age <= MAX_ESTIMATE_SECONDS &&
                speedFilter.sigma <= MAX_ESTIMATE_SIGMA -> SpeedSource.ESTIMATED
            else -> SpeedSource.NO_SIGNAL
        }
    }

    private fun smoothAndTrackPeaks(motion: Vec3, dt: Double) {
        // Two cascaded one-pole filters: copes with uneven sensor timing and rejects vibration.
        val alpha = 1.0 - exp(-dt * SMOOTHING_RATE)
        if (smoothedAccel == null) {
            smoothing1 = motion
            smoothing2 = motion
        }
        smoothing1 += (motion - smoothing1) * alpha
        smoothing2 += (smoothing1 - smoothing2) * alpha
        smoothedAccel = smoothing2

        val up = mountUp ?: return
        val fwd = forward ?: return
        val longitudinal = (smoothing2 dot fwd) / STANDARD_GRAVITY
        val lateral = (smoothing2 dot (up cross fwd)) / STANDARD_GRAVITY
        peaks = Peaks(
            accel = max(peaks.accel, longitudinal),
            brake = max(peaks.brake, -longitudinal),
            left = max(peaks.left, lateral),
            right = max(peaks.right, -lateral),
        )
    }

    private fun checkStopped(time: Double) {
        val w = history.window(time - STOP_WINDOW_SECONDS, time)
        if (w == null || w.spanSeconds < STOP_WINDOW_SECONDS * 0.8) {
            stopped = false
            return
        }

        val fix = lastUsableFix
        val gpsFresh = fix != null && time - fix.time <= GPS_FRESH_SECONDS
        // A car on a smooth road can feel still, so GPS has the final say when it's available.
        if (gpsFresh && fix.speed!! > GPS_STOPPED_SPEED) {
            stopped = false
            return
        }

        val forceLimit = if (gpsFresh) STOP_FORCE_STD else STOP_FORCE_STD_NO_GPS
        val turning = (w.meanGyro - gyroBias).norm
        stopped = w.forceStd < forceLimit && w.gyroStd < STOP_GYRO_STD && turning < STOP_GYRO_MEAN
        if (stopped) onStopped(w)
    }

    private fun onStopped(w: WindowStats) {
        val measuredUp = w.meanForce.normalized()
        gravityMagnitude += (w.meanForce.norm - gravityMagnitude) * STOPPED_BLEND
        gyroBias += (w.meanGyro - gyroBias) * STOPPED_BLEND

        val up = mountUp
        if (up == null || angleBetween(up, measuredUp) > MOUNT_MOVED_RADIANS) {
            resetMount()
            mountUp = measuredUp
            gravityUp = measuredUp
        } else {
            gravityUp = (gravityUp!! + (measuredUp - gravityUp!!) * STOPPED_BLEND).normalized()
        }
        speedFilter.updateStopped()
    }

    private fun resetMount() {
        mountUp = null
        gravityUp = null
        forward = null
        learner.reset()
        anchorTime = Double.NaN
        smoothedAccel = null
    }

    private fun learnFromFix(fixTime: Double, measured: Double) {
        val previousAnchor = anchorTime
        if (!previousAnchor.isNaN() && fixTime - previousAnchor < MIN_ANCHOR_SPACING) return
        val previousSpeed = anchorSpeed
        anchorTime = fixTime
        anchorSpeed = measured
        if (previousAnchor.isNaN()) return
        val interval = fixTime - previousAnchor
        if (interval > MAX_ANCHOR_SPACING) return

        val up = mountUp ?: return
        val tracked = gravityUp ?: return
        val w = history.window(previousAnchor, fixTime) ?: return
        val speedChangeRate = (measured - previousSpeed) / interval
        val averageSpeed = (measured + previousSpeed) / 2
        if (averageSpeed < MIN_LEARNING_SPEED) return
        val yawRate = (w.meanGyro - gyroBias) dot up

        if (abs(speedChangeRate) >= MIN_LEARNING_RATE && abs(yawRate) < MAX_LEARNING_YAW_RATE) {
            val motion = w.meanForce - tracked * gravityMagnitude
            learner.add(speedChangeRate, motion.perpendicularTo(up))
            learner.estimate(up)?.let { forward = it }
        }

        val fwd = forward ?: return
        val expectedMotion = fwd * speedChangeRate + (up cross fwd) * (averageSpeed * yawRate)
        val gravityOnly = w.meanForce - expectedMotion
        if (abs(gravityOnly.norm - gravityMagnitude) > MAX_GRAVITY_ERROR) return
        val blend = min(interval / GRAVITY_CORRECTION_SECONDS, 1.0)
        gravityUp = (tracked + (gravityOnly.normalized() - tracked) * blend).normalized()
    }

    private fun angleBetween(a: Vec3, b: Vec3) = acos((a dot b).coerceIn(-1.0, 1.0))

    private companion object {
        const val MAX_SENSOR_GAP = 0.25

        const val DEFAULT_GPS_SIGMA = 0.5
        const val MIN_GPS_SIGMA = 0.1
        const val MAX_GPS_SIGMA = 3.0
        const val MAX_FIX_AGE = 3.0
        const val MAX_FIX_LEAD = 0.5

        const val GPS_FRESH_SECONDS = 2.0
        const val MAX_ESTIMATE_SECONDS = 12.0
        const val MAX_ESTIMATE_SIGMA = 3.0

        /** Gives roughly a 5 Hz cutoff through the two cascaded filters. */
        const val SMOOTHING_RATE = 2 * Math.PI * 5.0 * 1.55

        const val STOP_CHECK_INTERVAL = 0.1
        const val STOP_WINDOW_SECONDS = 1.5
        const val STOP_FORCE_STD = 0.15
        const val STOP_FORCE_STD_NO_GPS = 0.08
        const val STOP_GYRO_STD = 0.02
        const val STOP_GYRO_MEAN = 0.05
        const val GPS_STOPPED_SPEED = 0.5
        const val STOPPED_BLEND = 0.2

        /** More than any road slope: if gravity has moved this far, the phone was moved. */
        const val MOUNT_MOVED_RADIANS = 20.0 * Math.PI / 180.0

        const val MIN_ANCHOR_SPACING = 0.8
        const val MAX_ANCHOR_SPACING = 2.5
        const val MIN_LEARNING_SPEED = 3.0
        const val MIN_LEARNING_RATE = 0.4
        const val MAX_LEARNING_YAW_RATE = 0.06
        const val MAX_GRAVITY_ERROR = 1.0
        const val GRAVITY_CORRECTION_SECONDS = 8.0
    }
}
