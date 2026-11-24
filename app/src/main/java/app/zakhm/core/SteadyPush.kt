package app.zakhm.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/**
 * What the G meters draw, made steady. The readings themselves are right, but on a real drive
 * half the time the push is under 0.05 G, where road bumps flip it between braking and speeding
 * up about twice a second; drawn as it is, the lit side blinks and jumps around. So what's
 * drawn is smoothed a little, comes on at a clear push and only goes off once it has really
 * faded, and a lit place stays put for a moment unless the push has clearly moved on.
 * The number and the peaks still show the readings as they are.
 */
class SteadyPush {
    /** Smoothed push along and across the car, in G, for drawing. */
    var long = 0.0
        private set
    var lat = 0.0
        private set

    /** Whether a direction is shown at all. */
    var shown = false
        private set

    /** Where the lit place is, as the angle of the push in the car (atan2(long, lat)), in steps of 45 degrees. */
    var placeAngle = 0.0
        private set

    /** How many of [bandCount] bands are lit, from strength against the meter's range. */
    var lit = 0
        private set

    private var lastTime = Double.NaN
    private var placeSince = Double.NEGATIVE_INFINITY
    private var shownSince = Double.NEGATIVE_INFINITY

    val total: Double get() = hypot(long, lat)

    /** Takes the latest reading at [time] seconds; nulls (not calibrated yet) clear everything. */
    fun update(time: Double, readingLong: Double?, readingLat: Double?, range: Double, bandCount: Int) {
        if (readingLong == null || readingLat == null || !readingLong.isFinite() || !readingLat.isFinite()) {
            reset()
            return
        }
        val dt = if (lastTime.isNaN()) Double.POSITIVE_INFINITY else time - lastTime
        if (dt <= 0.0) return
        lastTime = time
        val alpha = if (dt.isInfinite()) 1.0 else 1.0 - exp(-dt / SMOOTHING_SECONDS)
        long += (readingLong - long) * alpha
        lat += (readingLat - lat) * alpha
        val total = total
        val angle = atan2(long, lat)

        if (!shown && total >= ON_G) {
            shown = true
            shownSince = time
            placeAngle = nearestPlace(angle)
            placeSince = time
        } else if (shown && total < OFF_G && time - shownSince >= MIN_SHOWN_SECONDS) {
            shown = false
        }

        if (shown) {
            // Moves only once the push is well past the edge of its place and the place has had its moment.
            val off = abs(wrap(angle - placeAngle))
            if (off > PLACE_HALF_WIDTH + PLACE_MARGIN && time - placeSince >= MIN_PLACE_SECONDS) {
                placeAngle = nearestPlace(angle)
                placeSince = time
            }
            val strength = (total / range).coerceIn(0.0, 1.0)
            // Each band has its own on and off point, so a push near a step doesn't flick it.
            var count = lit.coerceAtLeast(1)
            while (count < bandCount && strength >= count.toDouble() / bandCount + BAND_MARGIN) count++
            while (count > 1 && strength < (count - 1).toDouble() / bandCount - BAND_MARGIN) count--
            lit = count
        } else {
            lit = 0
        }
    }

    fun reset() {
        long = 0.0
        lat = 0.0
        shown = false
        lit = 0
        lastTime = Double.NaN
    }

    private fun nearestPlace(angle: Double) = wrap(round(angle / PLACE_STEP) * PLACE_STEP)

    private fun wrap(a: Double): Double {
        var x = a
        while (x > PI) x -= 2 * PI
        while (x <= -PI) x += 2 * PI
        return x
    }

    /** The lit place's direction in the car: (along, across), a unit vector. */
    fun placeLong() = sin(placeAngle)
    fun placeLat() = cos(placeAngle)

    companion object {
        /** Calms what's drawn without making a real push look late. */
        const val SMOOTHING_SECONDS = 0.15

        /** A push this clear lights the meter; it goes dark again only under [OFF_G]. */
        const val ON_G = 0.08
        const val OFF_G = 0.05

        /** Once lit, at least this long, so a brief push doesn't just blink. */
        const val MIN_SHOWN_SECONDS = 0.4

        /** Eight places around the meter: four sides and four corners. */
        const val PLACE_STEP = PI / 4
        const val PLACE_HALF_WIDTH = PI / 8

        /** How far past its place's edge the push must lean before the place moves. */
        const val PLACE_MARGIN = 10.0 * PI / 180

        /** How long a place stays lit before it may move. */
        const val MIN_PLACE_SECONDS = 0.4

        /** Strength, as a share of the range, a push must clear past a band's step to change it. */
        const val BAND_MARGIN = 0.03
    }
}
