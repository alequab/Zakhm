package app.zakhm.core

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Works out which way the car points, as seen by a phone mounted at any angle.
 *
 * Motion worked out from speed readings is matched against the force the phone
 * felt over the same interval. Speed changes push along the car's axis and turns
 * push across it, so each gives a `(expected strength, force pointing forward)`
 * pair. Force along that axis follows the expected strength in sign and size,
 * while everything else averages away, so the sum of `strength * force` points forward.
 */
class ForwardLearner {
    private var sum = Vec3.ZERO
    private var strengthSquared = 0.0
    private var forceSquared = 0.0
    private var count = 0

    fun reset() {
        sum = Vec3.ZERO
        strengthSquared = 0.0
        forceSquared = 0.0
        count = 0
    }

    fun add(expectedStrength: Double, forwardForce: Vec3) {
        // Evidence that never agrees shouldn't block what comes after it, such as after a bumpy start.
        if (count >= MAX_SAMPLES && agreement() < MIN_AGREEMENT) reset()
        sum += forwardForce * expectedStrength
        strengthSquared += expectedStrength * expectedStrength
        forceSquared += forwardForce dot forwardForce
        count++
    }

    private fun agreement(): Double {
        val scale = sqrt(strengthSquared * forceSquared)
        return if (scale > 0.0) sum.norm / scale else 0.0
    }

    private val evidence: Double
        get() = min(1.0, min(count.toDouble() / MIN_SAMPLES, strengthSquared / MIN_STRENGTH_SQUARED))

    /** 0..1 progress. Never reaches 1 until the evidence also agrees, so it can't sit at 100% unfinished. */
    val progress: Double
        get() = if (evidence >= 1.0 && agreement() < MIN_AGREEMENT) UNSETTLED_PROGRESS else evidence

    /** The forward axis in the phone's frame, or null until the evidence is good enough. */
    fun estimate(up: Vec3): Vec3? {
        if (evidence < 1.0 || agreement() < MIN_AGREEMENT) return null
        return sum.perpendicularTo(up).normalized()
    }

    private companion object {
        const val MIN_SAMPLES = 4
        const val MIN_STRENGTH_SQUARED = 1.5
        const val MIN_AGREEMENT = 0.75
        const val MAX_SAMPLES = 40
        const val UNSETTLED_PROGRESS = 0.95
    }
}
