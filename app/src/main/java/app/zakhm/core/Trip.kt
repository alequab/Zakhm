package app.zakhm.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** One place on a saved drive, about once a second. */
data class TripPoint(
    /** Clock time, in milliseconds since 1970 UTC. */
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val speedMps: Double?,
    /** The strongest since the point before. Positive = speeding up, negative = braking. */
    val forwardG: Double?,
    /** The strongest since the point before. Positive = turning left. */
    val sideG: Double?,
    /** Height above sea level, in metres, when GPS gave one. */
    val altitudeM: Double? = null,
    /** How far off GPS said this position may be, in metres. */
    val accuracyM: Double? = null,
    /** Where the speed came from at this moment. */
    val speedFrom: SpeedInput? = null,
)

/**
 * Gathers a drive's points. G-force changes far faster than positions arrive, so
 * each point carries the strongest push since the one before, not the push at that instant.
 */
class TripRecorder {
    private var forwardG: Double? = null
    private var sideG: Double? = null

    fun onG(forward: Double?, side: Double?) {
        forwardG = stronger(forwardG, forward)
        sideG = stronger(sideG, side)
    }

    fun onPosition(
        timeMs: Long,
        latitude: Double,
        longitude: Double,
        speedMps: Double?,
        altitudeM: Double? = null,
        accuracyM: Double? = null,
        speedFrom: SpeedInput? = null,
    ): TripPoint {
        val point = TripPoint(
            timeMs, latitude, longitude, speedMps?.takeIf { it.isFinite() }, forwardG, sideG,
            altitudeM?.takeIf { it.isFinite() }, accuracyM?.takeIf { it.isFinite() && it >= 0 }, speedFrom,
        )
        forwardG = null
        sideG = null
        return point
    }

    private fun stronger(kept: Double?, new: Double?): Double? = when {
        new == null || !new.isFinite() -> kept
        kept == null || abs(new) > abs(kept) -> new
        else -> kept
    }
}

/**
 * A saved drive as text: one header line, then one line per point. Drives saved before
 * 1.6.2 have only the first six columns, and still open.
 */
object TripFile {
    const val HEADER = "time_ms,lat,lon,speed_mps,forward_g,side_g,alt_m,acc_m,speed_from"

    fun line(p: TripPoint): String = listOf(
        p.timeMs.toString(),
        "%.6f".format(Locale.US, p.latitude),
        "%.6f".format(Locale.US, p.longitude),
        p.speedMps?.let { "%.2f".format(Locale.US, it) }.orEmpty(),
        p.forwardG?.let { "%.3f".format(Locale.US, it) }.orEmpty(),
        p.sideG?.let { "%.3f".format(Locale.US, it) }.orEmpty(),
        p.altitudeM?.let { "%.1f".format(Locale.US, it) }.orEmpty(),
        p.accuracyM?.let { "%.1f".format(Locale.US, it) }.orEmpty(),
        p.speedFrom?.name?.lowercase(Locale.US).orEmpty(),
    ).joinToString(",")

    /** Lines that can't be read are skipped: a drive cut short by a crash still opens. */
    fun parse(lines: Sequence<String>): List<TripPoint> = lines.mapNotNull { line ->
        val parts = line.split(',')
        if (parts.size < 6) return@mapNotNull null
        val time = parts[0].toLongOrNull() ?: return@mapNotNull null
        val latitude = parts[1].toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return@mapNotNull null
        val longitude = parts[2].toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return@mapNotNull null
        TripPoint(
            time, latitude, longitude, parts[3].toDoubleOrNull(), parts[4].toDoubleOrNull(), parts[5].toDoubleOrNull(),
            altitudeM = parts.getOrNull(6)?.toDoubleOrNull(),
            accuracyM = parts.getOrNull(7)?.toDoubleOrNull(),
            speedFrom = parts.getOrNull(8)?.trim()?.let { name -> SpeedInput.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } },
        )
    }.toList()
}

data class TripSummary(
    val startMs: Long,
    val durationS: Long,
    val distanceM: Double,
    val topSpeedMps: Double,
    /** The strongest push in any direction, in G. */
    val strongestG: Double,
    /** Time spent moving, not standing still. */
    val movingS: Long = durationS,
    /** The strongest forward and sideways push together, in G. */
    val strongestCombinedG: Double = strongestG,
    /** Height gained along the way, in metres, when the drive has heights. */
    val climbM: Double? = null,
    /** Share of the drive whose speed came from the car (OBD or Android Auto), 0 to 1, when known. */
    val carSpeedShare: Double? = null,
)

enum class GEventKind { SPEEDING_UP, BRAKING, LEFT_TURN, RIGHT_TURN }

/** A moment worth marking on the map: the peak of one hard push. */
data class GEvent(val kind: GEventKind, val point: TripPoint, val g: Double)

/** Where the car stood still for a while. */
data class TripStop(val from: TripPoint, val to: TripPoint) {
    val seconds get() = (to.timeMs - from.timeMs) / 1000
}

/** A start from standstill, with the seconds it took to pass each target speed (metres per second). */
data class Launch(val start: TripPoint, val reached: List<Pair<Double, Double>>, val end: TripPoint)

/** One hard braking, from the speed before it to the speed after, and how far it took. */
data class Braking(val event: GEvent, val from: TripPoint, val to: TripPoint, val distanceM: Double)

/** A point where the drive passed another whole kilometre or mile. */
data class DistancePost(val point: TripPoint, val distanceM: Double)

object Trips {
    /** With no points for longer than this, the path is broken instead of drawn straight across. */
    const val GAP_MS = 10_000L

    /** Slower than this counts as standing still: GPS shows a little speed even when parked. */
    const val STILL_MPS = 0.5

    /** A stop shorter than this is only a slow moment, not worth a marker. */
    const val STOP_MIN_SECONDS = 20L

    /** What counts as notable, in G. A road car's firm braking is about 0.35 and hard cornering 0.4. */
    private val THRESHOLDS = mapOf(
        GEventKind.SPEEDING_UP to 0.25,
        GEventKind.BRAKING to 0.35,
        GEventKind.LEFT_TURN to 0.40,
        GEventKind.RIGHT_TURN to 0.40,
    )

    /** One push that dips under the limit for less than this is still one push. */
    private const val SAME_EVENT_MS = 3_000L

    /** Beyond this between points, speed says less about the distance than the positions do. */
    private const val SPEED_DISTANCE_MAX_MS = 5_000L
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** GPS heights wander by metres; a rise counts only once it is this tall. */
    private const val CLIMB_STEP_M = 4.0
    private const val LAUNCH_MAX_MS = 40_000L

    /** Speed may dip by this much, as in a gear change, before a launch counts as over. */
    private const val LAUNCH_EASE_MPS = 1.5
    private const val BRAKING_LOOK_MS = 15_000L
    /** Smaller changes in speed than this are GPS wobble, not braking. */
    private const val SPEED_WOBBLE_MPS = 0.1

    fun summary(points: List<TripPoint>): TripSummary? {
        val first = points.firstOrNull() ?: return null
        var distance = 0.0
        for (i in 1 until points.size) distance += step(points[i - 1], points[i])
        val durationS = max(0L, points.last().timeMs - first.timeMs) / 1000
        val stoppedS = stops(points, minSeconds = 1, inMiddleOnly = false).sumOf { it.seconds }
        val sources = points.mapNotNull { it.speedFrom }
        return TripSummary(
            startMs = first.timeMs,
            durationS = durationS,
            distanceM = distance,
            topSpeedMps = points.maxOf { it.speedMps ?: 0.0 },
            strongestG = points.maxOf { max(abs(it.forwardG ?: 0.0), abs(it.sideG ?: 0.0)) },
            movingS = (durationS - stoppedS).coerceAtLeast(0),
            strongestCombinedG = points.maxOf { combinedG(it) ?: 0.0 },
            climbM = climb(points),
            carSpeedShare = sources.takeIf { it.isNotEmpty() }?.let { list -> list.count { it != SpeedInput.PHONE }.toDouble() / list.size },
        )
    }

    /**
     * Speed over time where both are known, because GPS positions wander by metres while
     * the car stands still, which would add distance at every stop.
     */
    private fun step(a: TripPoint, b: TripPoint): Double {
        val ms = b.timeMs - a.timeMs
        val from = a.speedMps
        val to = b.speedMps
        return if (from != null && to != null && ms in 1..SPEED_DISTANCE_MAX_MS) {
            (from + to) / 2 * ms / 1000.0
        } else {
            metersBetween(a, b)
        }
    }

    fun metersBetween(a: TripPoint, b: TripPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Forward and sideways push together, as one strength. */
    fun combinedG(p: TripPoint): Double? {
        if (p.forwardG == null && p.sideG == null) return null
        val f = p.forwardG ?: 0.0
        val s = p.sideG ?: 0.0
        return sqrt(f * f + s * s)
    }

    /** Height gained along the drive, or null when it has no heights. */
    fun climb(points: List<TripPoint>): Double? {
        val heights = points.mapNotNull { it.altitudeM }
        if (heights.size < 2) return null
        var total = 0.0
        var base = heights.first()
        for (h in heights) {
            if (h - base >= CLIMB_STEP_M) {
                total += h - base
                base = h
            } else if (h < base) {
                base = h
            }
        }
        return total
    }

    /** The hard pushes of a drive, each once at its peak, in the order they happened. */
    fun events(points: List<TripPoint>): List<GEvent> =
        GEventKind.entries.flatMap { eventsOf(it, points) }.sortedBy { it.point.timeMs }

    private fun eventsOf(kind: GEventKind, points: List<TripPoint>): List<GEvent> {
        val threshold = THRESHOLDS.getValue(kind)
        val found = mutableListOf<GEvent>()
        var peak: GEvent? = null
        var lastOverMs = 0L
        for (p in points) {
            val g = when (kind) {
                GEventKind.SPEEDING_UP -> p.forwardG
                GEventKind.BRAKING -> p.forwardG?.let { -it }
                GEventKind.LEFT_TURN -> p.sideG
                GEventKind.RIGHT_TURN -> p.sideG?.let { -it }
            }
            val current = peak
            if (current != null && p.timeMs - lastOverMs > SAME_EVENT_MS) {
                found += current
                peak = null
            }
            if (g != null && g >= threshold) {
                lastOverMs = p.timeMs
                if (peak == null || g > peak.g) peak = GEvent(kind, p, g)
            }
        }
        peak?.let { found += it }
        return found
    }

    /** The path in pieces, split wherever the positions stopped for a while. */
    fun segments(points: List<TripPoint>): List<List<TripPoint>> {
        val pieces = mutableListOf<MutableList<TripPoint>>()
        for (p in points) {
            val piece = pieces.lastOrNull()
            if (piece == null || p.timeMs - piece.last().timeMs > GAP_MS) pieces += mutableListOf(p) else piece += p
        }
        return pieces
    }

    /** Where GPS went quiet: the last point before each break and the first after it. */
    fun gaps(points: List<TripPoint>): List<Pair<TripPoint, TripPoint>> =
        segments(points).zipWithNext { a, b -> a.last() to b.first() }

    /**
     * Where the car stood still for at least [minSeconds]. With [inMiddleOnly], waiting before
     * setting off and after arriving is left out, since the start and end pins show those.
     */
    fun stops(points: List<TripPoint>, minSeconds: Long = STOP_MIN_SECONDS, inMiddleOnly: Boolean = true): List<TripStop> {
        val found = mutableListOf<TripStop>()
        var from: TripPoint? = null
        var last: TripPoint? = null
        for (p in points) {
            val still = p.speedMps != null && p.speedMps <= STILL_MPS
            val broken = last != null && p.timeMs - last.timeMs > GAP_MS
            val begun = from
            if (begun != null && last != null && (!still || broken)) {
                TripStop(begun, last).takeIf { it.seconds >= minSeconds }?.let { found += it }
                from = null
            }
            if (still && from == null) from = p
            last = p
        }
        val begun = from
        if (begun != null && last != null) TripStop(begun, last).takeIf { it.seconds >= minSeconds }?.let { found += it }
        if (!inMiddleOnly || points.isEmpty()) return found
        return found.filter { it.from !== points.first() && it.to !== points.last() }
    }

    /**
     * Starts from standstill that passed at least the first of [targetsMps], with the time to
     * each target worked out between points. A run ends when the car eases off or GPS breaks.
     */
    fun launches(points: List<TripPoint>, targetsMps: List<Double>): List<Launch> {
        val found = mutableListOf<Launch>()
        var i = 0
        while (i < points.size - 1) {
            val start = points[i]
            val startSpeed = start.speedMps
            val nextSpeed = points[i + 1].speedMps
            // The last still moment before the car moves.
            if (startSpeed == null || startSpeed > STILL_MPS || nextSpeed == null || nextSpeed <= STILL_MPS) {
                i++
                continue
            }
            val reached = mutableListOf<Pair<Double, Double>>()
            var top = 0.0
            var prev = start
            var j = i + 1
            while (j < points.size) {
                val p = points[j]
                val v = p.speedMps ?: break
                if (p.timeMs - prev.timeMs > SPEED_DISTANCE_MAX_MS || p.timeMs - start.timeMs > LAUNCH_MAX_MS) break
                if (v < top - LAUNCH_EASE_MPS) break
                val before = prev.speedMps ?: 0.0
                for (target in targetsMps) {
                    if (reached.none { it.first == target } && v >= target) {
                        val share = if (v > before && before < target) (target - before) / (v - before) else 1.0
                        val atMs = prev.timeMs + share * (p.timeMs - prev.timeMs)
                        reached += target to (atMs - start.timeMs) / 1000.0
                    }
                }
                top = max(top, v)
                prev = p
                j++
            }
            if (reached.isNotEmpty()) found += Launch(start, reached.sortedBy { it.first }, prev)
            i = max(i + 1, j - 1)
        }
        return found
    }

    /** Each hard braking, stretched back to where the slowing began and on to where it ended. */
    fun brakings(points: List<TripPoint>, events: List<GEvent> = events(points)): List<Braking> =
        events.filter { it.kind == GEventKind.BRAKING }.mapNotNull { event ->
            val at = points.indexOf(event.point).takeIf { it >= 0 } ?: return@mapNotNull null
            var from = at
            while (from > 0 && slowing(points[from - 1], points[from]) && event.point.timeMs - points[from - 1].timeMs <= BRAKING_LOOK_MS) from--
            var to = at
            while (to < points.lastIndex && slowing(points[to], points[to + 1]) && points[to + 1].timeMs - event.point.timeMs <= BRAKING_LOOK_MS) to++
            var distance = 0.0
            for (k in from + 1..to) distance += step(points[k - 1], points[k])
            Braking(event, points[from], points[to], distance)
        }

    /** True when the car was slower at [later] than at [earlier], with nothing missing between. */
    private fun slowing(earlier: TripPoint, later: TripPoint): Boolean {
        val a = earlier.speedMps ?: return false
        val b = later.speedMps ?: return false
        return later.timeMs - earlier.timeMs <= SPEED_DISTANCE_MAX_MS && b < a - SPEED_WOBBLE_MPS
    }

    /** A post at each whole [everyM] metres along the drive. */
    fun distancePosts(points: List<TripPoint>, everyM: Double): List<DistancePost> {
        val found = mutableListOf<DistancePost>()
        var total = 0.0
        var next = everyM
        for (i in 1 until points.size) {
            total += step(points[i - 1], points[i])
            while (total >= next) {
                found += DistancePost(points[i], next)
                next += everyM
            }
        }
        return found
    }
}
