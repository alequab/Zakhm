package app.speedg.core

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Works out which way the car points, as seen by a phone mounted at any angle.
 *
 * Straight-line speed changes reported by GPS are matched against the force the
 * phone felt over the same interval. Force along the car's axis follows those
 * speed changes in sign and size, while everything else averages away, so the
 * sum of `speedChangeRate * force` points forward.
 */
class ForwardLearner {
    private var sum = Vec3.ZERO
    private var rateSquared = 0.0
    private var forceSquared = 0.0
    private var count = 0

    fun reset() {
        sum = Vec3.ZERO
        rateSquared = 0.0
        forceSquared = 0.0
        count = 0
    }

    fun add(speedChangeRate: Double, horizontalForce: Vec3) {
        sum += horizontalForce * speedChangeRate
        rateSquared += speedChangeRate * speedChangeRate
        forceSquared += horizontalForce dot horizontalForce
        count++
    }

    /** 0..1 progress toward having enough evidence. */
    val progress: Double
        get() = min(1.0, min(count.toDouble() / MIN_SAMPLES, rateSquared / MIN_RATE_SQUARED))

    /** The forward axis in the phone's frame, or null until the evidence is good enough. */
    fun estimate(up: Vec3): Vec3? {
        if (progress < 1.0) return null
        val agreement = sum.norm / sqrt(rateSquared * forceSquared)
        if (agreement < MIN_AGREEMENT) return null
        return sum.perpendicularTo(up).normalized()
    }

    private companion object {
        const val MIN_SAMPLES = 5
        const val MIN_RATE_SQUARED = 5.0
        const val MIN_AGREEMENT = 0.75
    }
}
