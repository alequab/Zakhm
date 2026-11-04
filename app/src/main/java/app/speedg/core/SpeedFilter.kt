package app.speedg.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Kalman filter over speed and accelerometer bias.
 *
 * The forward acceleration predicts speed between GPS fixes and the GPS corrects
 * it. The bias state soaks up whatever constant error remains in that
 * acceleration, which is what keeps the estimate usable when GPS drops out.
 */
class SpeedFilter {
    var speed = 0.0
        private set
    var bias = 0.0
        private set
    var initialized = false
        private set

    /**
     * Sum of every correction so far. History stores `speed - cumulativeCorrection`,
     * so a late fix compares against old predictions as corrected by later fixes
     * instead of correcting the same error twice.
     */
    var cumulativeCorrection = 0.0
        private set

    private var pvv = 0.0
    private var pvb = 0.0
    private var pbb = INITIAL_BIAS_VARIANCE
    private var rejectedInARow = 0
    private var usingAccel = false

    val sigma: Double get() = sqrt(max(pvv, 0.0))

    fun predict(dt: Double, forwardAccel: Double?) {
        if (!initialized) return
        usingAccel = forwardAccel != null
        if (forwardAccel != null) {
            speed = max(speed + (forwardAccel - bias) * dt, 0.0)
            pvv += -2 * dt * pvb + dt * dt * pbb + ACCEL_PROCESS_NOISE * dt
            pvb -= dt * pbb
            pbb += BIAS_PROCESS_NOISE * dt
        } else {
            // Without a known forward axis, speed can only be assumed to wander.
            pvv += NO_ACCEL_PROCESS_NOISE * dt
        }
    }

    /**
     * Applies a measurement of what the speed was at the fix's timestamp.
     *
     * @param predictedAtFix our own estimate for that same moment.
     * @return true if the measurement was used.
     */
    fun update(measured: Double, measurementSigma: Double, predictedAtFix: Double = speed): Boolean {
        val r = measurementSigma * measurementSigma
        if (!initialized) {
            speed = measured
            pvv = r
            pvb = 0.0
            initialized = true
            return true
        }

        val innovation = measured - predictedAtFix
        val s = pvv + r
        if (abs(innovation) > GATE_SIGMAS * sqrt(s) + GATE_MARGIN) {
            // One wild fix is noise; several in a row mean we are the ones who are wrong.
            if (++rejectedInARow < MAX_REJECTED_IN_A_ROW) return false
            cumulativeCorrection += measured - speed
            speed = measured
            pvv = r
            pvb = 0.0
            pbb = INITIAL_BIAS_VARIANCE
            rejectedInARow = 0
            return true
        }
        rejectedInARow = 0

        val kv = pvv / s
        val kb = pvb / s
        val before = speed
        speed = max(speed + kv * innovation, 0.0)
        cumulativeCorrection += speed - before
        if (usingAccel) {
            bias = (bias + kb * innovation).coerceIn(-MAX_BIAS, MAX_BIAS)
        }
        val newPbb = pbb - kb * pvb
        val newPvb = (1 - kv) * pvb
        pvv = (1 - kv) * pvv
        pvb = newPvb
        pbb = newPbb
        return true
    }

    /** The car is known to be stopped. */
    fun updateStopped() {
        if (!initialized) return
        update(0.0, STOPPED_SIGMA)
    }

    private companion object {
        const val ACCEL_PROCESS_NOISE = 0.04
        const val NO_ACCEL_PROCESS_NOISE = 4.0
        const val BIAS_PROCESS_NOISE = 1e-5
        const val INITIAL_BIAS_VARIANCE = 0.04
        const val MAX_BIAS = 1.5
        const val GATE_SIGMAS = 5.0
        const val GATE_MARGIN = 1.0
        const val MAX_REJECTED_IN_A_ROW = 3
        const val STOPPED_SIGMA = 0.02
    }
}
