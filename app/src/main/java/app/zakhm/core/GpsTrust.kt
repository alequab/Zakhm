package app.zakhm.core

import kotlin.math.sqrt

/**
 * How far off this phone's GPS speed really is, against how far off the phone says it
 * may be. Some phones are far too modest: one reported about 2.2 m/s while it was
 * really within 0.37 m/s of the car's own speed, which made GPS count for too little.
 *
 * Learned by comparing GPS with the car's speed whenever both are there. It belongs to
 * the phone, not the car, so what one car teaches applies in every car.
 */
class GpsTrust(saved: Saved? = null) {
    /** What is kept between runs. */
    data class Saved(val errorSquares: Double, val reportedSquares: Double, val samples: Int)

    private var errorSquares = 0.0
    private var reportedSquares = 0.0
    private var samples = 0

    init {
        if (saved != null && saved.errorSquares.isFinite() && saved.reportedSquares.isFinite() &&
            saved.errorSquares >= 0.0 && saved.reportedSquares >= 0.0 && saved.samples >= 0
        ) {
            errorSquares = saved.errorSquares
            reportedSquares = saved.reportedSquares
            samples = saved.samples
        }
    }

    /** True once enough comparisons have been made to stop guessing. */
    val learned: Boolean get() = samples >= MIN_SAMPLES && reportedSquares > 0.0

    /** What the reported accuracy is multiplied by to get the real one. */
    val scale: Double
        get() = if (learned) sqrt(errorSquares / reportedSquares).coerceIn(MIN_SCALE, MAX_SCALE) else DEFAULT_SCALE

    val sampleCount: Int get() = samples

    /** Compares one GPS reading with the car's speed at the same moment. */
    fun add(gpsSpeed: Double, carSpeed: Double, reportedAccuracy: Double?) {
        val reported = reportedAccuracy?.takeIf { it > 0.0 && it.isFinite() } ?: return
        if (!gpsSpeed.isFinite() || !carSpeed.isFinite()) return
        // Slower, GPS speed wanders more than it does on the move, and a car speed in whole km/h is coarse.
        if (gpsSpeed < MIN_SPEED || carSpeed < MIN_SPEED) return
        val error = gpsSpeed - carSpeed
        errorSquares = errorSquares * KEEP + error * error
        reportedSquares = reportedSquares * KEEP + reported * reported
        samples++
    }

    /**
     * The accuracy to believe for a GPS reading that came with [reported]. Until it is
     * measured, the guess only shrinks figures that are clearly too modest: a phone that
     * already claims to be good is taken at its word.
     */
    fun sigma(reported: Double): Double =
        if (learned) reported * scale else maxOf(reported * DEFAULT_SCALE, minOf(reported, GUESS_FLOOR))

    fun saved() = Saved(errorSquares, reportedSquares, samples)

    private companion object {
        /** About two minutes of driving with the car's speed. */
        const val MIN_SAMPLES = 120

        /**
         * Before anything is learned. Taken from the one phone measured, but well short of
         * its 0.17, so an honest phone still isn't trusted far more than it says.
         */
        const val DEFAULT_SCALE = 0.4
        const val MIN_SCALE = 0.1

        /** Before learning, never believe GPS better than this, in m/s, unless the phone itself says so. */
        const val GUESS_FLOOR = 0.5
        const val MAX_SCALE = 1.0

        /** Older comparisons fade, so a changed phone or setting is followed within a few drives. */
        const val KEEP = 0.999

        /** About 11 km/h. */
        const val MIN_SPEED = 3.0
    }
}
