package app.zakhm.core

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** Running average and spread of a value, kept in a form that can be saved. */
data class RunningStats(val count: Double = 0.0, val mean: Double = 0.0, val m2: Double = 0.0) {
    val variance: Double get() = if (count > 1.0) m2 / count else 0.0
    val std: Double get() = sqrt(variance)

    fun add(x: Double): RunningStats {
        // Slowly forgets, so a car that changes (new mount, new tyres) is relearned.
        val n = minOf(count, MAX_WEIGHT) + 1.0
        val delta = x - mean
        val newMean = mean + delta / n
        val keep = minOf(count, MAX_WEIGHT) / maxOf(count, 1.0)
        return RunningStats(n, newMean, m2 * keep + delta * (x - newMean))
    }

    private companion object {
        const val MAX_WEIGHT = 400.0
    }
}

/** What the model has learned about one car, saved with its profile. */
data class StillnessStats(
    val parkedForce: RunningStats = RunningStats(),
    val parkedGyro: RunningStats = RunningStats(),
    val rollingForce: RunningStats = RunningStats(),
    val rollingGyro: RunningStats = RunningStats(),
)

enum class Stillness { UNKNOWN, PARKED, ROLLING }

/**
 * Learns how much one car shakes the phone while parked and while rolling slowly,
 * from moments when a trusted speed says which it is. GPS alone can report several
 * km/h while parked, and a smooth pull-away can feel almost still, so a fixed limit
 * fits some cars and fails others. Measured on a real car: about 0.04 (force) and
 * 0.001 (gyro) parked, against 0.26 and 0.005 rolling at walking pace.
 */
class StillnessModel(stats: StillnessStats = StillnessStats()) {
    var stats = stats
        private set

    fun learnParked(forceStd: Double, gyroStd: Double) {
        if (!usable(forceStd, gyroStd)) return
        stats = stats.copy(parkedForce = stats.parkedForce.add(ln(forceStd)), parkedGyro = stats.parkedGyro.add(ln(gyroStd)))
    }

    fun learnRolling(forceStd: Double, gyroStd: Double) {
        if (!usable(forceStd, gyroStd)) return
        stats = stats.copy(rollingForce = stats.rollingForce.add(ln(forceStd)), rollingGyro = stats.rollingGyro.add(ln(gyroStd)))
    }

    /**
     * A measure is used when a typical parked moment is quieter than the car ever is
     * while rolling. Rolling moments are judged from a trusted speed and come out
     * consistent; parked ones also catch a phone being fiddled with at a stop, so
     * they're only used to check that this line actually separates the two.
     */
    private fun separates(parked: RunningStats, rolling: RunningStats) =
        parked.count >= MIN_SAMPLES && rolling.count >= MIN_SAMPLES && parked.mean < rollingFloor(rolling)

    private fun rollingFloor(rolling: RunningStats) = rolling.mean - FLOOR_SIGMAS * max(rolling.std, MIN_STD)

    private val forceSeparates get() = separates(stats.parkedForce, stats.rollingForce)
    private val gyroSeparates get() = separates(stats.parkedGyro, stats.rollingGyro)

    /** Enough of both, and at least one measure that tells them apart. */
    val ready: Boolean get() = forceSeparates || gyroSeparates

    /**
     * Parked when every measure that separates says the car is quieter than it ever
     * rolls. Rolling when the car shakes about as much as it usually does rolling.
     */
    fun classify(forceStd: Double, gyroStd: Double): Stillness {
        if (!ready || !usable(forceStd, gyroStd)) return Stillness.UNKNOWN
        val force = ln(forceStd)
        val gyro = ln(gyroStd)
        val measures = listOfNotNull(
            if (forceSeparates) force to stats.rollingForce else null,
            if (gyroSeparates) gyro to stats.rollingGyro else null,
        )
        return when {
            measures.all { (x, rolling) -> x < rollingFloor(rolling) } -> Stillness.PARKED
            measures.any { (x, rolling) -> x >= rolling.mean - ROLLING_SIGMAS * max(rolling.std, MIN_STD) } -> Stillness.ROLLING
            else -> Stillness.UNKNOWN
        }
    }

    /** A short summary for the debug log. */
    fun describe(): String {
        val s = stats
        // Typical values back in ordinary units, with the spread as a factor.
        fun typical(r: RunningStats) = "%.4f×%.1f".format(java.util.Locale.US, kotlin.math.exp(r.mean), kotlin.math.exp(r.std))
        return "parked=%d rolling=%d ready=%s force=%s/%s floor=%.4f%s gyro=%s/%s floor=%.4f%s".format(
            java.util.Locale.US,
            s.parkedForce.count.toInt(),
            s.rollingForce.count.toInt(),
            ready,
            typical(s.parkedForce),
            typical(s.rollingForce),
            kotlin.math.exp(rollingFloor(s.rollingForce)),
            if (forceSeparates) "(used)" else "",
            typical(s.parkedGyro),
            typical(s.rollingGyro),
            kotlin.math.exp(rollingFloor(s.rollingGyro)),
            if (gyroSeparates) "(used)" else "",
        )
    }

    private fun usable(forceStd: Double, gyroStd: Double) =
        forceStd.isFinite() && gyroStd.isFinite() && forceStd > 0.0 && gyroStd > 0.0

    private companion object {
        const val MIN_SAMPLES = 20.0
        const val MIN_STD = 0.1

        /**
         * How far below its typical rolling shake counts as quieter than the car ever rolls.
         * A rolling moment drops this low well under 1% of the time, and parked has to last too.
         */
        const val FLOOR_SIGMAS = 2.5

        /** Within this of its typical rolling shake counts as rolling. */
        const val ROLLING_SIGMAS = 1.0
    }
}
