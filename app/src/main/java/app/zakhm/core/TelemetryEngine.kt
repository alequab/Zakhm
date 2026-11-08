package app.zakhm.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.hypot
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
    val source: FixSource = FixSource.GPS,
)

enum class FixSource { GPS, CAR }

/** What the engine has learned about a car beyond its mount, saved with the car. */
data class LearnedCar(
    val stillness: StillnessStats = StillnessStats(),
    val carLag: Double? = null,
    val gpsLag: Double? = null,
    val carScale: Double? = null,
)

/** How a GPS reading compared with the car's own speed at the same moment, for the log. */
data class GpsCheck(
    val time: Double,
    val gpsSpeed: Double,
    val gpsAccuracy: Double?,
    val carSpeed: Double?,
    val estimate: Double,
    val gpsLag: Double?,
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
    /** Someone is holding or moving the phone, so G-force is hidden and peaks are paused. */
    val phoneInHand: Boolean = false,
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
    private var calmForce: Vec3? = null
    private var calmGyro = Vec3.ZERO
    private var cruisingForce = Vec3.ZERO
    private var cruisingSamples = 0

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
    private var peakSignal: Vec3? = null
    private var peaks = Peaks()

    private var inHand = false
    private var lastShake = Double.NEGATIVE_INFINITY
    private var lastHandCheck = Double.NEGATIVE_INFINITY
    private var lastPeaksCheckpoint = Double.NEGATIVE_INFINITY
    private var recentPeaks = Peaks()
    private var olderPeaks = Peaks()

    private var stillnessModel = StillnessModel()
    private var carTiming = SourceTiming()
    private var gpsTiming = SourceTiming()
    private var carScale = SpeedScale()
    private var lastCarFix = Double.NEGATIVE_INFINITY
    private var lastStillnessLearned = Double.NEGATIVE_INFINITY

    /** The latest GPS reading next to the car's speed at the same moment, for the log. */
    var lastGpsCheck: GpsCheck? = null
        private set

    /** What the vibration said at the last stop check, for the log. */
    var lastStillness = Stillness.UNKNOWN
        private set

    private var mountCheckTime = Double.NaN
    private var mountCheckSpeed = 0.0
    private var mountMismatches = 0
    private var peaksBeforeMismatch = Peaks()

    fun onGyro(time: Double, gyro: Vec3) {
        // One bad reading would otherwise poison every estimate that follows it.
        if (!time.isFinite() || !gyro.isFinite) return
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
        if (!time.isFinite() || !force.isFinite) return
        now = max(now, time)
        val previous = lastAccelTime
        lastAccelTime = time
        val dt = if (previous.isNaN()) 0.0 else time - previous
        val validDt = dt > 0.0 && dt <= MAX_SENSOR_GAP

        val up = gravityUp
        val fwd = forward
        if (validDt) {
            val motion = up?.let { force - it * gravityMagnitude }
            // A hand's movements say nothing about the car's, so GPS alone carries the speed meanwhile.
            val forwardAccel = if (fwd != null && motion != null && !inHand) motion dot fwd else null
            speedFilter.predict(dt, forwardAccel)
            if (motion != null) smoothAndTrackPeaks(motion, dt)
        }

        // A running engine shakes the phone far faster than a car moves, so measure stillness without it.
        val calmAlpha = if (validDt) 1.0 - exp(-dt * CALM_RATE) else 1.0
        val calm = calmForce?.let { it + (force - it) * calmAlpha } ?: force
        calmForce = calm
        calmGyro += (rawGyro - calmGyro) * calmAlpha

        history.add(time, force, rawGyro, speedFilter.speed - speedFilter.cumulativeCorrection, calm, calmGyro)

        if (time - lastHandCheck >= HAND_CHECK_INTERVAL) {
            lastHandCheck = time
            checkHand(time)
        }
        if (time - lastStopCheck >= STOP_CHECK_INTERVAL) {
            lastStopCheck = time
            checkStopped(time)
        }
    }

    /**
     * Notices the phone being picked up or moved within a fraction of a second. A hand
     * turns the phone far faster than a car turns: measured on a real drive, about
     * 1.2 rad/s of wobble in a hand against under 0.06 in a mount, even through turns.
     */
    private fun checkHand(time: Double) {
        val w = history.window(time - HAND_WINDOW_SECONDS, time) ?: return
        val shaking = w.gyroStd > HAND_GYRO_STD
        if (shaking) lastShake = time
        val up = mountUp
        val tracked = gravityUp
        val tilted = up != null && tracked != null && angleBetween(up, tracked) > MOUNT_MOVED_RADIANS
        val settling = time - lastShake < HAND_SETTLE_SECONDS

        if (!inHand && (shaking || tilted)) {
            inHand = true
            // The first moments of handling got through before it was noticed.
            peaks = olderPeaks
            peaksBeforeMismatch = olderPeaks
            peakSignal = null
            smoothedAccel = null
            cruisingForce = Vec3.ZERO
            cruisingSamples = 0
        }
        if (inHand && !settling) {
            // Put back the same way, the calibration still fits. Turned, it has to be learned again.
            if (tilted) {
                noteCalibrationLoss("put back at a different angle: %.1f deg".format(java.util.Locale.US, Math.toDegrees(angleBetween(up!!, tracked!!))))
                resetMount()
            }
            inHand = false
            smoothedAccel = null
            peakSignal = null
            anchorTime = Double.NaN
            mountCheckTime = Double.NaN
        }

        if (!inHand && time - lastPeaksCheckpoint >= PEAKS_CHECKPOINT_SECONDS) {
            lastPeaksCheckpoint = time
            olderPeaks = recentPeaks
            recentPeaks = peaks
        }
    }

    fun onSpeedFix(fix: SpeedFix) {
        val reported = fix.speed?.takeIf { it.isFinite() } ?: return
        val isCar = fix.source == FixSource.CAR
        if (!isCar) {
            gpsHorizontalAccuracy = fix.horizontalAccuracy
            gpsSpeedAccuracy = fix.speedAccuracy
        }
        // Trust the fix's own timestamp unless it's clearly on a different clock.
        val stamped = if (!fix.time.isFinite() || fix.time < now - MAX_FIX_AGE || fix.time > now + MAX_FIX_LEAD) now else fix.time

        // Each source reports a little late; once that delay is measured, the reading goes where it belongs.
        val timing = if (isCar) carTiming else gpsTiming
        if (phoneSpeedKnown()) timing.add(stamped, reported, ::phoneSpeedAt) else timing.breakChain()
        val fixTime = stamped - (timing.lag ?: 0.0)

        val measured: Double
        if (isCar) {
            carScale.addCar(fixTime, reported)
            measured = reported * carScale.factor
            lastCarFix = stamped
        } else {
            measured = reported
            val car = carScale.addGps(fixTime, reported, fix.speedAccuracy)
            lastGpsCheck = GpsCheck(
                time = fixTime,
                gpsSpeed = reported,
                gpsAccuracy = fix.speedAccuracy,
                carSpeed = car,
                estimate = (history.speedAt(fixTime)?.plus(speedFilter.cumulativeCorrection)) ?: speedFilter.speed,
                gpsLag = timing.lag,
            )
            // With the car's own speed coming in, GPS is compared and learned from, not shown.
            if (stamped - lastCarFix <= CAR_FRESH_SECONDS) return
        }
        val sigma = fix.speedAccuracy?.takeIf { it > 0.0 && it.isFinite() } ?: DEFAULT_GPS_SIGMA
        if (sigma > MAX_GPS_SIGMA) return
        if (!inHand && mountWasMoved(fixTime, measured)) {
            resetMount()
            peaks = peaksBeforeMismatch
            speedFilter.restartFrom(measured, max(sigma, MIN_GPS_SIGMA))
        }
        val predicted = history.speedAt(fixTime)?.plus(speedFilter.cumulativeCorrection)

        val accepted = speedFilter.update(measured, max(sigma, MIN_GPS_SIGMA), predicted ?: speedFilter.speed)
        if (!accepted) return
        now = max(now, fixTime)
        // Kept with the time it was stamped, not moved back by its delay, as this says how fresh it is.
        lastUsableFix = fix.copy(time = stamped, speed = measured)
        // Whatever the sensors felt in a hand would teach the wrong axes.
        if (inHand) {
            anchorTime = Double.NaN
            return
        }
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
        rememberPeaksAsSafe()
    }

    private fun rememberPeaksAsSafe() {
        peaksBeforeMismatch = peaks
        recentPeaks = peaks
        olderPeaks = peaks
    }

    fun mountCalibration(): MountCalibration? = mountUp?.let { MountCalibration(it, forward) }

    fun currentPeaks(): Peaks = peaks

    /**
     * Switches to a saved car's calibration and peaks. The calibration is only a
     * starting point: a phone tilted differently is caught at the next stop, and a
     * phone turned differently is corrected by fresh forward learning.
     */
    fun loadCar(mount: MountCalibration?, savedPeaks: Peaks, learned: LearnedCar = LearnedCar()) {
        resetMount()
        stillnessModel = StillnessModel(learned.stillness)
        carTiming = SourceTiming(learned.carLag)
        gpsTiming = SourceTiming(learned.gpsLag)
        carScale = SpeedScale(learned.carScale)
        lastStillness = Stillness.UNKNOWN
        mountUp = mount?.up
        gravityUp = mount?.up
        forward = mount?.forward
        peaks = savedPeaks
        rememberPeaksAsSafe()
        inHand = false
        peakSignal = null
        stopped = false
        lastStopCheck = Double.NEGATIVE_INFINITY
    }

    fun snapshot(): TelemetrySnapshot {
        val up = mountUp
        val fwd = forward
        val motion = smoothedAccel?.takeIf { !inHand }
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
            phoneInHand = inHand,
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

        // A pothole or a shaky mount lasts a moment, while a car's real forces build over a few tenths of a second.
        val peakAlpha = 1.0 - exp(-dt * PEAK_SMOOTHING_RATE)
        val held = peakSignal?.let { it + (smoothing2 - it) * peakAlpha } ?: smoothing2
        peakSignal = held

        if (inHand) return
        val up = mountUp ?: return
        val fwd = forward ?: return
        val longitudinal = (held dot fwd) / STANDARD_GRAVITY
        val lateral = (held dot (up cross fwd)) / STANDARD_GRAVITY
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
        val still = stillnessModel.classify(w.forceStd, w.gyroStd)
        lastStillness = still
        if (gpsFresh) learnStillness(time, w, fix!!)
        val fromGps = fix?.source != FixSource.CAR

        // A car on a smooth road can feel still, so the measured speed has the final say when it's
        // available. The exception: a small GPS speed while this car's learned vibration says
        // parked, as receivers report several km/h standing still.
        if (gpsFresh && fix!!.speed!! > GPS_STOPPED_SPEED) {
            val gpsDrift = fromGps && still == Stillness.PARKED && fix.speed!! <= MAX_PARKED_GPS_SPEED
            if (!gpsDrift) {
                stopped = false
                return
            }
        }
        // The same vibration says when a parked car starts rolling, before GPS notices.
        if (stopped && fromGps && still == Stillness.ROLLING) {
            stopped = false
            return
        }

        // Without GPS, a smooth road can feel still, so the car must also have slowed down to count as stopped.
        if (!gpsFresh && speedFilter.initialized && speedFilter.speed > GPS_STOPPED_SPEED) {
            stopped = false
            return
        }

        // With GPS also saying stopped, allow a rough idle, and only rule out someone moving the phone.
        // Harder to leave than to enter, so readings near a limit can't flip the state back and forth.
        val hold = if (stopped) STOP_RELEASE_FACTOR else 1.0
        val forceLimit = (if (gpsFresh) STOP_FORCE_STD else STOP_FORCE_STD_NO_GPS) * hold
        val gyroLimit = (if (gpsFresh) STOP_GYRO_STD else STOP_GYRO_STD_NO_GPS) * hold
        val turning = (w.meanGyro - gyroBias).norm
        stopped = w.forceStd < forceLimit && w.gyroStd < gyroLimit && turning < STOP_GYRO_MEAN * hold
        if (stopped) onStopped(w)
    }

    /**
     * Notices a phone that moved while the car was moving, which the check at a stop
     * can't see. The car's speed says how hard it's really accelerating, so a large,
     * lasting disagreement with the sensors means the saved axes no longer fit.
     */
    private fun mountWasMoved(fixTime: Double, measured: Double): Boolean {
        val previousTime = mountCheckTime
        if (!previousTime.isNaN() && fixTime - previousTime < MIN_ANCHOR_SPACING) return false
        val previousSpeed = mountCheckSpeed
        mountCheckTime = fixTime
        mountCheckSpeed = measured
        val up = mountUp
        val tracked = gravityUp
        val interval = fixTime - previousTime
        val averageSpeed = (measured + previousSpeed) / 2
        if (up == null || tracked == null || previousTime.isNaN() || interval > MAX_ANCHOR_SPACING ||
            averageSpeed < MIN_LEARNING_SPEED
        ) {
            mountMismatches = 0
            peaksBeforeMismatch = peaks
            return false
        }
        val w = history.window(previousTime, fixTime) ?: return false
        val speedChangeRate = (measured - previousSpeed) / interval
        val turnAccel = averageSpeed * ((w.meanGyro - gyroBias) dot tracked)
        val motion = w.meanForce - tracked * gravityMagnitude
        val fwd = forward
        val unexplained = if (fwd != null) {
            (motion - fwd * speedChangeRate - (up cross fwd) * turnAccel).norm
        } else {
            motion.norm - hypot(speedChangeRate, turnAccel)
        }
        val tilt = angleBetween(up, tracked)
        val mismatch = unexplained > MAX_UNEXPLAINED_ACCEL || tilt > MOUNT_MOVED_RADIANS
        if (!mismatch) {
            mountMismatches = 0
            peaksBeforeMismatch = peaks
            return false
        }
        mountMismatches++
        if (mountMismatches < MOUNT_MISMATCHES_NEEDED) return false
        mountMismatches = 0
        noteCalibrationLoss(
            "sensors disagree with speed: unexplained=%.2f m/s2 tilt=%.1f deg speed=%.1f km/h rate=%.2f turn=%.2f forwardKnown=%s".format(
                java.util.Locale.US, unexplained, Math.toDegrees(tilt), averageSpeed * 3.6, speedChangeRate, turnAccel, fwd != null,
            ),
        )
        return true
    }

    /** Teaches the car's parked and rolling vibration from moments a trusted speed says which it is. */
    private fun learnStillness(time: Double, w: WindowStats, fix: SpeedFix) {
        if (inHand || time - lastStillnessLearned < STILLNESS_LEARN_SECONDS) return
        val speed = fix.speed ?: return
        val parked = when (fix.source) {
            // The car's own speed is the best judge; it reads whole km/h, so 0 is a true stop.
            FixSource.CAR -> when {
                speed == 0.0 -> true
                speed in CAR_ROLLING_LEARN_RANGE -> false
                else -> null
            }
            // GPS only when it's clear-cut: zero and sure of itself, or well above the speeds it imagines.
            FixSource.GPS -> {
                val accuracy = fix.speedAccuracy ?: Double.MAX_VALUE
                when {
                    speed < GPS_PARKED_LEARN_SPEED && accuracy <= GPS_PARKED_LEARN_ACCURACY -> true
                    speed in GPS_ROLLING_LEARN_RANGE && accuracy <= GPS_ROLLING_LEARN_ACCURACY -> false
                    else -> null
                }
            }
        } ?: return
        lastStillnessLearned = time
        if (parked) stillnessModel.learnParked(w.forceStd, w.gyroStd) else stillnessModel.learnRolling(w.forceStd, w.gyroStd)
    }

    private fun phoneSpeedKnown() = mountUp != null && forward != null && !inHand && speedFilter.initialized

    private fun phoneSpeedAt(time: Double): Double? = history.speedAt(time)

    /** How many times calibration has been thrown away, and why the last time, for the log. */
    var calibrationLosses = 0
        private set
    var lastCalibrationLoss: String? = null
        private set

    private fun noteCalibrationLoss(reason: String) {
        calibrationLosses++
        lastCalibrationLoss = reason
    }

    /** What's been learned about this car beyond its mount, to save with it. */
    fun learnedCar() = LearnedCar(
        stillness = stillnessModel.stats,
        carLag = carTiming.lag,
        gpsLag = gpsTiming.lag,
        carScale = carScale.learned ?: carScale.factor.takeIf { it != 1.0 },
    )

    /** One line on everything learned so far, for the debug log. */
    fun learningSummary(): String =
        "still={${stillnessModel.describe()}} now=$lastStillness obd={${carTiming.describe()}} " +
            "gps={${gpsTiming.describe()}} scale=%.4f learned=%s samples=%d".format(
                java.util.Locale.US,
                carScale.factor,
                carScale.learned != null,
                carScale.sampleCount,
            )

    private fun onStopped(w: WindowStats) {
        val measuredUp = w.meanForce.normalized()
        gravityMagnitude += (w.meanForce.norm - gravityMagnitude) * STOPPED_BLEND
        gyroBias += (w.meanGyro - gyroBias) * STOPPED_BLEND

        val up = mountUp
        if (up == null || angleBetween(up, measuredUp) > MOUNT_MOVED_RADIANS) {
            if (up != null) {
                noteCalibrationLoss("tilted at a stop: %.1f deg".format(java.util.Locale.US, Math.toDegrees(angleBetween(up, measuredUp))))
            }
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
        peakSignal = null
        cruisingForce = Vec3.ZERO
        cruisingSamples = 0
        mountMismatches = 0
    }

    /**
     * Finds "down" without waiting for a stop. At a steady speed in a straight line
     * the only lasting force is gravity, so averaging a few intervals gives its direction.
     */
    private fun learnUpWhileCruising(w: WindowStats, speedChangeRate: Double, averageSpeed: Double) {
        // Roads rarely stay straight and level for long, so an unsteady moment is skipped, not a reason to start over.
        val steady = averageSpeed >= MIN_LEARNING_SPEED &&
            abs(speedChangeRate) < MAX_CRUISING_RATE &&
            (w.meanGyro - gyroBias).norm < MAX_CRUISING_TURN
        if (!steady) return
        // The steady moments can be far apart, so make sure the phone hasn't moved in between.
        if (cruisingSamples > 0 && angleBetween(cruisingForce.normalized(), w.meanForce.normalized()) > MAX_CRUISING_SPREAD_RADIANS) {
            cruisingForce = Vec3.ZERO
            cruisingSamples = 0
        }
        cruisingForce += w.meanForce
        cruisingSamples++
        if (cruisingSamples >= CRUISING_SAMPLES_NEEDED) {
            val up = cruisingForce.normalized()
            mountUp = up
            gravityUp = up
        }
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

        val w = history.window(previousAnchor, fixTime) ?: return
        val speedChangeRate = (measured - previousSpeed) / interval
        val averageSpeed = (measured + previousSpeed) / 2
        val up = mountUp
        val tracked = gravityUp
        if (up == null || tracked == null) {
            learnUpWhileCruising(w, speedChangeRate, averageSpeed)
            return
        }
        if (averageSpeed < MIN_LEARNING_SPEED) return
        val yawRate = (w.meanGyro - gyroBias) dot up
        val turnAccel = averageSpeed * yawRate

        val motion = (w.meanForce - tracked * gravityMagnitude).perpendicularTo(up)
        if (abs(speedChangeRate) >= MIN_LEARNING_RATE && abs(yawRate) < MAX_LEARNING_YAW_RATE) {
            learner.add(speedChangeRate, motion)
        } else if (abs(turnAccel) >= MIN_LEARNING_TURN && abs(speedChangeRate) < abs(turnAccel)) {
            // A left turn pushes toward the car's left, and left crossed with up is forward.
            learner.add(turnAccel, motion cross up)
        }
        learner.estimate(up)?.let { forward = it }

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

        /** Gives roughly a 3 Hz cutoff through the two cascaded filters, below most mount and engine shake. */
        const val SMOOTHING_RATE = 2 * Math.PI * 3.0 * 1.55

        /** What a peak must get through, on top of the smoothing above. */
        const val PEAK_SMOOTHING_RATE = 2 * Math.PI * 2.0

        const val HAND_CHECK_INTERVAL = 0.1
        const val HAND_WINDOW_SECONDS = 0.5

        /** Between a mount through hard turns (measured under 0.06) and a hand (measured 1.2 and up). */
        const val HAND_GYRO_STD = 0.3

        /** How long the phone must be left alone before readings come back. */
        const val HAND_SETTLE_SECONDS = 2.0
        const val PEAKS_CHECKPOINT_SECONDS = 1.0

        const val STOP_CHECK_INTERVAL = 0.1
        const val STOP_WINDOW_SECONDS = 1.5
        const val CALM_RATE = 2 * Math.PI * 2.0
        const val STOP_FORCE_STD = 0.5
        const val STOP_FORCE_STD_NO_GPS = 0.08
        const val STOP_GYRO_STD = 0.1
        const val STOP_GYRO_STD_NO_GPS = 0.02
        const val STOP_GYRO_MEAN = 0.05
        const val GPS_STOPPED_SPEED = 0.8

        /** About 14 km/h. Faster than any GPS drift seen while parked, so it's always believed. */
        const val MAX_PARKED_GPS_SPEED = 4.0
        const val CAR_FRESH_SECONDS = 2.0
        const val STILLNESS_LEARN_SECONDS = 0.5
        val CAR_ROLLING_LEARN_RANGE = 1.0..5.5
        const val GPS_PARKED_LEARN_SPEED = 0.2
        const val GPS_PARKED_LEARN_ACCURACY = 0.5
        val GPS_ROLLING_LEARN_RANGE = 4.0..6.0
        const val GPS_ROLLING_LEARN_ACCURACY = 1.0
        const val STOPPED_BLEND = 0.2

        /** More than any road slope: if gravity has moved this far, the phone was moved. */
        const val MOUNT_MOVED_RADIANS = 20.0 * Math.PI / 180.0
        const val STOP_RELEASE_FACTOR = 1.5

        /** About 0.25 G that speed changes and turning can't account for. */
        const val MAX_UNEXPLAINED_ACCEL = 2.5
        const val MOUNT_MISMATCHES_NEEDED = 3

        const val MIN_ANCHOR_SPACING = 0.8
        const val MAX_ANCHOR_SPACING = 2.5
        const val MIN_LEARNING_SPEED = 3.0
        const val MIN_LEARNING_RATE = 0.3
        const val MAX_LEARNING_YAW_RATE = 0.06
        const val MIN_LEARNING_TURN = 0.5
        const val MAX_CRUISING_RATE = 0.2
        const val MAX_CRUISING_TURN = 0.03
        const val MAX_CRUISING_SPREAD_RADIANS = 15.0 * Math.PI / 180.0
        const val CRUISING_SAMPLES_NEEDED = 5
        const val MAX_GRAVITY_ERROR = 1.0
        const val GRAVITY_CORRECTION_SECONDS = 8.0
    }
}
