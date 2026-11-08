package app.zakhm.core

import kotlin.math.abs
import kotlin.math.exp

/**
 * Finds how late a speed source reports, by matching its speed changes against the
 * phone's own, which arrive with almost no delay. An OBD adapter adds the request's
 * round trip and the car's own filtering; a GPS chip smooths its speed too. Knowing
 * the delay lets every reading be placed at the moment it really describes.
 */
class SourceTiming(savedLag: Double? = null) {
    private val lags = DoubleArray(LAG_STEPS) { it * LAG_STEP }
    private val cost = DoubleArray(LAG_STEPS)
    private var excitation = 0.0
    private var lastTime = Double.NaN
    private var lastSpeed = 0.0
    private var learnedLag: Double? = null
    private val startingLag = savedLag?.takeIf { it.isFinite() && it in 0.0..MAX_LAG }

    /** The best known delay in seconds: this drive's if learned, else the car's saved one. */
    val lag: Double? get() = learnedLag ?: startingLag

    /** Whether this drive has had enough speed changes to measure the delay itself. */
    val measured: Boolean get() = learnedLag != null

    /**
     * Adds a reading. [phoneSpeedAt] gives the phone's integrated speed at a moment,
     * or null when it isn't known then; only its changes are used, never its level.
     */
    fun add(time: Double, speed: Double, phoneSpeedAt: (Double) -> Double?) {
        val previousTime = lastTime
        val previousSpeed = lastSpeed
        // A fast stream is compared in steps, as tiny steps are mostly rounding.
        if (!previousTime.isNaN() && time - previousTime < MIN_SPACING) return
        lastTime = time
        lastSpeed = speed
        if (previousTime.isNaN()) return
        val dt = time - previousTime
        if (dt > MAX_SPACING) return
        // Near a standstill the phone's speed stops at zero while a late source is still slowing, which looks like delay.
        if (speed < MIN_SPEED || previousSpeed < MIN_SPEED) return

        val change = speed - previousSpeed
        val errors = DoubleArray(LAG_STEPS)
        for (i in lags.indices) {
            val later = phoneSpeedAt(time - lags[i]) ?: return
            val earlier = phoneSpeedAt(previousTime - lags[i]) ?: return
            errors[i] = change - (later - earlier)
        }
        val keep = exp(-dt / MEMORY_SECONDS)
        for (i in lags.indices) cost[i] = cost[i] * keep + errors[i] * errors[i]
        excitation = excitation * keep + abs(change)
        if (excitation >= MIN_EXCITATION) learnedLag = bestLag()
    }

    /** The phone's speed can't be trusted across this gap, so don't compare across it. */
    fun breakChain() {
        lastTime = Double.NaN
    }

    fun describe(): String = "lag=%s measured=%s excitation=%.1f".format(
        java.util.Locale.US,
        lag?.let { "%.2f".format(java.util.Locale.US, it) } ?: "-",
        measured,
        excitation,
    )

    private fun bestLag(): Double {
        var best = 0
        for (i in cost.indices) if (cost[i] < cost[best]) best = i
        // A parabola through the best point and its neighbours finds the delay between steps.
        if (best == 0 || best == cost.lastIndex) return lags[best]
        val a = cost[best - 1]
        val b = cost[best]
        val c = cost[best + 1]
        val curve = a - 2 * b + c
        if (curve <= 0.0) return lags[best]
        val offset = (0.5 * (a - c) / curve).coerceIn(-0.5, 0.5)
        return lags[best] + offset * LAG_STEP
    }

    private companion object {
        const val MAX_LAG = 2.0
        const val LAG_STEP = 0.05
        const val LAG_STEPS = (MAX_LAG / LAG_STEP).toInt() + 1
        const val MIN_SPACING = 0.25
        const val MAX_SPACING = 1.5

        /** About 7 km/h. */
        const val MIN_SPEED = 2.0
        const val MEMORY_SECONDS = 180.0

        /** About ten real speed-ups or slow-downs, so one bump can't decide it. */
        const val MIN_EXCITATION = 20.0
    }
}

/**
 * Learns how far the car's own speed reading is from GPS on a steady road. Cars
 * often read a few percent off, and the error changes with tyre wear and size.
 * Once known, OBD readings are corrected by it.
 */
class SpeedScale(saved: Double? = null) {
    private var sumProduct = 0.0
    private var sumSquare = 0.0
    private var samples = 0
    private val starting = saved?.takeIf { it.isFinite() && it in MIN_SCALE..MAX_SCALE }
    private val carReadings = ArrayDeque<Pair<Double, Double>>()

    /** The correction for car readings: this drive's if learned, else the car's saved one, else none. */
    val factor: Double get() = learned ?: starting ?: 1.0
    val learned: Double?
        get() = if (samples >= MIN_SAMPLES && sumSquare > 0.0) (sumProduct / sumSquare).coerceIn(MIN_SCALE, MAX_SCALE) else null
    val sampleCount: Int get() = samples

    /** A car reading, already placed at the moment it describes. */
    fun addCar(time: Double, speed: Double) {
        carReadings.addLast(time to speed)
        while (carReadings.size > 2 && carReadings.first().first < time - CAR_MEMORY_SECONDS) carReadings.removeFirst()
    }

    /** The car's speed at [time] from its readings around it, or null when there are none close by. */
    fun carSpeedAt(time: Double): Double? {
        var before: Pair<Double, Double>? = null
        var after: Pair<Double, Double>? = null
        for (r in carReadings) {
            if (r.first <= time) before = r else if (after == null) after = r
        }
        val b = before ?: return null
        val a = after ?: return if (time - b.first <= MAX_GAP) b.second else null
        if (a.first - b.first > MAX_GAP) return null
        val f = (time - b.first) / (a.first - b.first)
        return b.second + (a.second - b.second) * f
    }

    /**
     * Compares a GPS reading with the car's at the same moment, when both are
     * trustworthy: moving, steady, and GPS sure of itself. Returns the car's
     * corrected speed then, for logging, or null if there was none to compare.
     */
    fun addGps(time: Double, gpsSpeed: Double, gpsAccuracy: Double?): Double? {
        val car = carSpeedAt(time) ?: return null
        val steady = carSpeedAt(time - STEADY_SECONDS)?.let { abs(car - it) / STEADY_SECONDS < MAX_STEADY_RATE } ?: false
        val sure = gpsAccuracy != null && gpsAccuracy <= MAX_GPS_ACCURACY
        if (steady && sure && car >= MIN_SPEED && gpsSpeed >= MIN_SPEED) {
            sumProduct = sumProduct * KEEP + gpsSpeed * car
            sumSquare = sumSquare * KEEP + car * car
            samples++
        }
        return car * factor
    }

    private companion object {
        const val MIN_SCALE = 0.9
        const val MAX_SCALE = 1.1
        const val MIN_SAMPLES = 60
        const val KEEP = 0.998
        const val CAR_MEMORY_SECONDS = 6.0
        const val MAX_GAP = 1.0
        const val STEADY_SECONDS = 1.0
        const val MAX_STEADY_RATE = 0.4
        const val MAX_GPS_ACCURACY = 1.0

        /** About 30 km/h: slower, GPS speed noise is too large a share of the speed. */
        const val MIN_SPEED = 8.0
    }
}
