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

    /** Enough of both, and far enough apart, to be trusted. */
    val ready: Boolean
        get() = stats.parkedForce.count >= MIN_SAMPLES && stats.rollingForce.count >= MIN_SAMPLES &&
            (separation(stats.parkedForce, stats.rollingForce) >= MIN_SEPARATION ||
                separation(stats.parkedGyro, stats.rollingGyro) >= MIN_SEPARATION)

    /** Parked only if every well-separated measure says so; rolling if any of them does. */
    fun classify(forceStd: Double, gyroStd: Double): Stillness {
        if (!ready || !usable(forceStd, gyroStd)) return Stillness.UNKNOWN
        val votes = listOf(
            vote(ln(forceStd), stats.parkedForce, stats.rollingForce),
            vote(ln(gyroStd), stats.parkedGyro, stats.rollingGyro),
        ).filter { it != null }
        return when {
            votes.isEmpty() -> Stillness.UNKNOWN
            votes.any { it == Stillness.ROLLING } -> Stillness.ROLLING
            votes.all { it == Stillness.PARKED } -> Stillness.PARKED
            else -> Stillness.UNKNOWN
        }
    }

    /** A short summary for the debug log. */
    fun describe(): String {
        val s = stats
        // Typical values back in ordinary units, with the spread as a factor.
        fun typical(r: RunningStats) = "%.4f×%.1f".format(java.util.Locale.US, kotlin.math.exp(r.mean), kotlin.math.exp(r.std))
        return "parked=%d rolling=%d sepF=%.1f sepG=%.1f ready=%s force=%s/%s gyro=%s/%s".format(
            java.util.Locale.US,
            s.parkedForce.count.toInt(),
            s.rollingForce.count.toInt(),
            separation(s.parkedForce, s.rollingForce),
            separation(s.parkedGyro, s.rollingGyro),
            ready,
            typical(s.parkedForce),
            typical(s.rollingForce),
            typical(s.parkedGyro),
            typical(s.rollingGyro),
        )
    }

    /** Null when this measure doesn't separate the two well enough to have a say. */
    private fun vote(x: Double, parked: RunningStats, rolling: RunningStats): Stillness? {
        if (separation(parked, rolling) < MIN_SEPARATION) return null
        // Each side has to be clearly nearer its own class, leaving a gap where neither is claimed.
        val toParked = (x - parked.mean) / max(parked.std, MIN_STD)
        val toRolling = (rolling.mean - x) / max(rolling.std, MIN_STD)
        return when {
            toParked <= PARKED_LIMIT_SIGMAS && toRolling >= CLEAR_SIGMAS -> Stillness.PARKED
            toRolling <= ROLLING_LIMIT_SIGMAS && toParked >= CLEAR_SIGMAS -> Stillness.ROLLING
            else -> Stillness.UNKNOWN
        }
    }

    private fun separation(a: RunningStats, b: RunningStats): Double {
        if (a.count < 2 || b.count < 2) return 0.0
        val spread = sqrt((max(a.variance, MIN_STD * MIN_STD) + max(b.variance, MIN_STD * MIN_STD)) / 2)
        return (b.mean - a.mean) / spread
    }

    private fun usable(forceStd: Double, gyroStd: Double) =
        forceStd.isFinite() && gyroStd.isFinite() && forceStd > 0.0 && gyroStd > 0.0

    private companion object {
        const val MIN_SAMPLES = 20.0
        /** Parked and rolling must be at least this many spreads apart before the model is used. */
        const val MIN_SEPARATION = 4.0
        const val MIN_STD = 0.1
        const val PARKED_LIMIT_SIGMAS = 2.0
        const val ROLLING_LIMIT_SIGMAS = 2.0
        const val CLEAR_SIGMAS = 2.0
    }
}
