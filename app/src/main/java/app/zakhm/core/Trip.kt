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

    fun onPosition(timeMs: Long, latitude: Double, longitude: Double, speedMps: Double?): TripPoint {
        val point = TripPoint(timeMs, latitude, longitude, speedMps?.takeIf { it.isFinite() }, forwardG, sideG)
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

/** A saved drive as text: one header line, then one line per point. */
object TripFile {
    const val HEADER = "time_ms,lat,lon,speed_mps,forward_g,side_g"

    fun line(p: TripPoint): String = listOf(
        p.timeMs.toString(),
        "%.6f".format(Locale.US, p.latitude),
        "%.6f".format(Locale.US, p.longitude),
        p.speedMps?.let { "%.2f".format(Locale.US, it) }.orEmpty(),
        p.forwardG?.let { "%.3f".format(Locale.US, it) }.orEmpty(),
        p.sideG?.let { "%.3f".format(Locale.US, it) }.orEmpty(),
    ).joinToString(",")

    /** Lines that can't be read are skipped: a drive cut short by a crash still opens. */
    fun parse(lines: Sequence<String>): List<TripPoint> = lines.mapNotNull { line ->
        val parts = line.split(',')
        if (parts.size < 6) return@mapNotNull null
        val time = parts[0].toLongOrNull() ?: return@mapNotNull null
        val latitude = parts[1].toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return@mapNotNull null
        val longitude = parts[2].toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return@mapNotNull null
        TripPoint(time, latitude, longitude, parts[3].toDoubleOrNull(), parts[4].toDoubleOrNull(), parts[5].toDoubleOrNull())
    }.toList()
}

data class TripSummary(
    val startMs: Long,
    val durationS: Long,
    val distanceM: Double,
    val topSpeedMps: Double,
    /** The strongest push in any direction, in G. */
    val strongestG: Double,
)

enum class GEventKind { SPEEDING_UP, BRAKING, LEFT_TURN, RIGHT_TURN }

/** A moment worth marking on the map: the peak of one hard push. */
data class GEvent(val kind: GEventKind, val point: TripPoint, val g: Double)

object Trips {
    /** With no points for longer than this, the path is broken instead of drawn straight across. */
    const val GAP_MS = 10_000L

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

    fun summary(points: List<TripPoint>): TripSummary? {
        val first = points.firstOrNull() ?: return null
        var distance = 0.0
        for (i in 1 until points.size) distance += step(points[i - 1], points[i])
        return TripSummary(
            startMs = first.timeMs,
            durationS = max(0L, points.last().timeMs - first.timeMs) / 1000,
            distanceM = distance,
            topSpeedMps = points.maxOf { it.speedMps ?: 0.0 },
            strongestG = points.maxOf { max(abs(it.forwardG ?: 0.0), abs(it.sideG ?: 0.0)) },
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
}
