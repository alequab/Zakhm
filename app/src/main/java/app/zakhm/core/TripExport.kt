package app.zakhm.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The words a map file shows, in the app's language. */
data class TripText(
    val name: String,
    val description: String,
    val speedFolder: String,
    val gFolder: String,
    val start: String,
    val end: String,
    val topSpeed: String,
    val speedingUp: String,
    val braking: String,
    val leftTurn: String,
    val rightTurn: String,
    val speedUnit: String,
    /** Multiplies metres per second into [speedUnit]. */
    val speedFactor: Double,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun label(kind: GEventKind) = when (kind) {
        GEventKind.SPEEDING_UP -> speedingUp
        GEventKind.BRAKING -> braking
        GEventKind.LEFT_TURN -> leftTurn
        GEventKind.RIGHT_TURN -> rightTurn
    }

    fun speed(mps: Double) = "%.0f %s".format(Locale.US, mps * speedFactor, speedUnit)

    fun clock(timeMs: Long): String = CLOCK.format(Instant.ofEpochMilli(timeMs).atZone(zone))

    private companion object {
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    }
}

/**
 * Writes a saved drive as files that map programs open. KML carries its own colours
 * and markers, so it looks the same everywhere; GPX is what nearly everything reads.
 */
object TripExport {
    /** Slow to fast, as `RRGGBB`. */
    private val SPEED_COLOURS = listOf("2B6CFF", "00A6E0", "00B894", "5BC236", "C8D21E", "FFB300", "FF6A00", "E81E1E")
    private val EVENT_COLOURS = mapOf(
        GEventKind.SPEEDING_UP to "2ECC71",
        GEventKind.BRAKING to "E81E1E",
        GEventKind.LEFT_TURN to "FFB300",
        GEventKind.RIGHT_TURN to "FFB300",
    )
    private const val DOT = "http://maps.google.com/mapfiles/kml/shapes/shaded_dot.png"
    private const val PIN = "http://maps.google.com/mapfiles/kml/paddle/wht-blank.png"

    /** Never divides the colours finer than this, so a crawl in traffic isn't painted red. */
    private const val MIN_TOP_SPEED_MPS = 8.0

    fun kml(points: List<TripPoint>, text: TripText): String {
        val top = maxOf(points.maxOfOrNull { it.speedMps ?: 0.0 } ?: 0.0, MIN_TOP_SPEED_MPS)
        val step = top / SPEED_COLOURS.size
        fun band(p: TripPoint) = ((p.speedMps ?: 0.0) / step).toInt().coerceIn(0, SPEED_COLOURS.lastIndex)

        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
        out.append("<name>").append(xml(text.name)).append("</name>\n")
        out.append("<description>").append(xml(text.description)).append("</description>\n")
        SPEED_COLOURS.forEachIndexed { i, colour ->
            out.append("<Style id=\"speed$i\"><LineStyle><color>").append(kmlColour(colour))
                .append("</color><width>6</width></LineStyle></Style>\n")
        }
        EVENT_COLOURS.forEach { (kind, colour) ->
            out.append("<Style id=\"").append(kind.name.lowercase(Locale.US)).append("\"><IconStyle><color>")
                .append(kmlColour(colour)).append("</color><scale>1.2</scale><Icon><href>").append(DOT)
                .append("</href></Icon></IconStyle></Style>\n")
        }
        out.append("<Style id=\"pin\"><IconStyle><Icon><href>").append(PIN).append("</href></Icon></IconStyle></Style>\n")

        out.append("<Folder>\n<name>").append(xml(text.speedFolder)).append("</name>\n")
        for (piece in Trips.segments(points)) {
            var from = 0
            while (from < piece.lastIndex) {
                // Each stretch is coloured by the point it leads to, and shares its ends with its neighbours.
                val colour = band(piece[from + 1])
                var to = from + 1
                while (to < piece.lastIndex && band(piece[to + 1]) == colour) to++
                out.append("<Placemark><name>")
                    .append(xml("%.0f–%.0f %s".format(Locale.US, colour * step * text.speedFactor, (colour + 1) * step * text.speedFactor, text.speedUnit)))
                    .append("</name><styleUrl>#speed").append(colour)
                    .append("</styleUrl><LineString><tessellate>1</tessellate><coordinates>")
                for (i in from..to) out.append(coordinates(piece[i])).append(' ')
                out.append("</coordinates></LineString></Placemark>\n")
                from = to
            }
        }
        out.append("</Folder>\n")

        out.append("<Folder>\n<name>").append(xml(text.gFolder)).append("</name>\n")
        for (mark in marks(points, text)) {
            out.append("<Placemark><name>").append(xml(mark.name)).append("</name><description>")
                .append(xml(mark.description)).append("</description><styleUrl>#").append(mark.style)
                .append("</styleUrl><Point><coordinates>").append(coordinates(mark.point))
                .append("</coordinates></Point></Placemark>\n")
        }
        out.append("</Folder>\n</Document>\n</kml>\n")
        return out.toString()
    }

    fun gpx(points: List<TripPoint>, text: TripText): String {
        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"Zakhm\" xmlns=\"http://www.topografix.com/GPX/1/1\"")
            .append(" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v2\"")
            .append(" xmlns:zakhm=\"https://github.com/alequab/Zakhm\">\n")
        out.append("<metadata><name>").append(xml(text.name)).append("</name><desc>").append(xml(text.description)).append("</desc>")
        points.firstOrNull()?.let { out.append("<time>").append(iso(it.timeMs)).append("</time>") }
        out.append("</metadata>\n")
        for (mark in marks(points, text)) {
            out.append("<wpt ").append(position(mark.point)).append("><time>").append(iso(mark.point.timeMs))
                .append("</time><name>").append(xml(mark.name)).append("</name><desc>").append(xml(mark.description))
                .append("</desc></wpt>\n")
        }
        out.append("<trk><name>").append(xml(text.name)).append("</name>\n")
        for (piece in Trips.segments(points)) {
            out.append("<trkseg>\n")
            for (p in piece) {
                out.append("<trkpt ").append(position(p)).append("><time>").append(iso(p.timeMs)).append("</time>")
                if (p.speedMps != null || p.forwardG != null || p.sideG != null) {
                    out.append("<extensions>")
                    // The one place most viewers look for a speed, in metres per second.
                    p.speedMps?.let {
                        out.append("<gpxtpx:TrackPointExtension><gpxtpx:speed>").append(number(it, 2))
                            .append("</gpxtpx:speed></gpxtpx:TrackPointExtension>")
                    }
                    p.forwardG?.let { out.append("<zakhm:forwardG>").append(number(it, 3)).append("</zakhm:forwardG>") }
                    p.sideG?.let { out.append("<zakhm:sideG>").append(number(it, 3)).append("</zakhm:sideG>") }
                    out.append("</extensions>")
                }
                out.append("</trkpt>\n")
            }
            out.append("</trkseg>\n")
        }
        out.append("</trk>\n</gpx>\n")
        return out.toString()
    }

    private class Mark(val name: String, val description: String, val style: String, val point: TripPoint)

    /** Where the drive began and ended, its top speed, and each hard push. */
    private fun marks(points: List<TripPoint>, text: TripText): List<Mark> {
        if (points.isEmpty()) return emptyList()
        fun about(p: TripPoint) = listOfNotNull(p.speedMps?.let(text::speed), text.clock(p.timeMs)).joinToString(" · ")
        val marks = mutableListOf(Mark(text.start, text.clock(points.first().timeMs), "pin", points.first()))
        points.filter { it.speedMps != null }.maxByOrNull { it.speedMps!! }?.let { fastest ->
            marks += Mark("${text.topSpeed} ${text.speed(fastest.speedMps!!)}", text.clock(fastest.timeMs), "pin", fastest)
        }
        Trips.events(points).forEach { event ->
            val name = "%s %.2f G".format(Locale.US, text.label(event.kind), event.g)
            marks += Mark(name, about(event.point), event.kind.name.lowercase(Locale.US), event.point)
        }
        marks += Mark(text.end, text.clock(points.last().timeMs), "pin", points.last())
        return marks
    }

    /** KML writes colours backwards: opacity, blue, green, red. */
    private fun kmlColour(rgb: String) = "ff" + rgb.substring(4, 6) + rgb.substring(2, 4) + rgb.substring(0, 2)

    private fun coordinates(p: TripPoint) = "%.6f,%.6f,0".format(Locale.US, p.longitude, p.latitude)

    private fun position(p: TripPoint) = "lat=\"%.6f\" lon=\"%.6f\"".format(Locale.US, p.latitude, p.longitude)

    private fun number(value: Double, decimals: Int) = "%.${decimals}f".format(Locale.US, value)

    private fun iso(timeMs: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timeMs))

    private fun xml(text: String) = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
