package app.zakhm.core

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Works out which way the car points, as seen by a phone mounted at any angle.
 *
 * Motion worked out from speed readings is matched against the force the phone
 * felt over the same interval. Speed changes push along the car's axis and turns
 * push across it, so each gives a `(expected strength, force pointing forward)`
 * pair. Force along that axis follows the expected strength in sign and size.
 *
 * Forward comes from how the force *changes* with the strength, not from their plain
 * product: anything constant, such as gravity judged a little off or a late GPS,
 * then drops out. That only works once both directions have been seen, speeding up
 * and slowing down or turns both ways, so it isn't trusted before. On a real drive,
 * forward learned from a single speed-up read braking as speeding up.
 */
class ForwardLearner {
    private var count = 0
    private var sumStrength = 0.0
    private var sumStrengthSquared = 0.0
    private var sumForce = Vec3.ZERO
    private var sumForceSquared = 0.0
    private var sumProduct = Vec3.ZERO
    private var positiveSquared = 0.0
    private var negativeSquared = 0.0

    fun reset() {
        count = 0
        sumStrength = 0.0
        sumStrengthSquared = 0.0
        sumForce = Vec3.ZERO
        sumForceSquared = 0.0
        sumProduct = Vec3.ZERO
        positiveSquared = 0.0
        negativeSquared = 0.0
    }

    fun add(expectedStrength: Double, forwardForce: Vec3) {
        // Evidence that never agrees shouldn't block what comes after it, such as after a bumpy start.
        if (count >= MAX_SAMPLES && agreement() < MIN_AGREEMENT) reset()
        count++
        sumStrength += expectedStrength
        sumStrengthSquared += expectedStrength * expectedStrength
        sumForce += forwardForce
        sumForceSquared += forwardForce dot forwardForce
        sumProduct += forwardForce * expectedStrength
        if (expectedStrength > 0) positiveSquared += expectedStrength * expectedStrength else negativeSquared += expectedStrength * expectedStrength
    }

    /** How the force moves with the strength, with anything constant removed. */
    private fun covariance(): Vec3 = if (count == 0) Vec3.ZERO else sumProduct - sumForce * (sumStrength / count)

    private fun strengthSpread() = if (count == 0) 0.0 else sumStrengthSquared - sumStrength * sumStrength / count

    private fun forceSpread() = if (count == 0) 0.0 else sumForceSquared - (sumForce dot sumForce) / count

    private fun agreement(): Double {
        val scale = sqrt(strengthSpread() * forceSpread())
        return if (scale > 0.0) covariance().norm / scale else 0.0
    }

    private val evidence: Double
        get() = min(
            1.0,
            minOf(
                count.toDouble() / MIN_SAMPLES,
                strengthSpread() / MIN_STRENGTH_SQUARED,
                // Both directions, so a constant error can't pass for forward.
                min(positiveSquared, negativeSquared) / MIN_EACH_DIRECTION_SQUARED,
            ),
        )

    /** 0..1 progress. Never reaches 1 until the evidence also agrees, so it can't sit at 100% unfinished. */
    val progress: Double
        get() = if (evidence >= 1.0 && agreement() < MIN_AGREEMENT) UNSETTLED_PROGRESS else evidence

    /** The forward axis in the phone's frame, or null until the evidence is good enough. */
    fun estimate(up: Vec3): Vec3? {
        if (evidence < 1.0 || agreement() < MIN_AGREEMENT) return null
        return covariance().perpendicularTo(up).normalized()
    }

    private companion object {
        const val MIN_SAMPLES = 6
        const val MIN_STRENGTH_SQUARED = 1.5

        /** About two seconds of gentle speeding up and the same of slowing down, or turns both ways. */
        const val MIN_EACH_DIRECTION_SQUARED = 0.4
        const val MIN_AGREEMENT = 0.75
        const val MAX_SAMPLES = 40
        const val UNSETTLED_PROGRESS = 0.95
    }
}
