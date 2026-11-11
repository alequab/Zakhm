package app.zakhm.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** The words a map file shows, in the app's language. */
data class TripText(
    val name: String,
    /** The drive in one line, for places that show only plain text. */
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
    /** The drive's figures as label and value, for the summary table. */
    val facts: List<Pair<String, String>> = emptyList(),
    val speedLabel: String = "Speed",
    val timeLabel: String = "Time",
    val gLabel: String = "G-force",
    val forwardGLabel: String = "Forward G",
    val sideGLabel: String = "Sideways G",
    val driveFolder: String = "Drive",
    val tourName: String = "Fly along the drive",
    val timelineName: String = "Car on the timeline",
    val rightToLeft: Boolean = false,
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
 *
 * The KML is written for Google Earth first: besides the coloured path and markers
 * it holds a track that moves a car along the time slider, with speed and G-force
 * for Earth's profile graph, and a tour that flies along the drive. Programs that
 * don't know those parts skip them and still show the path and markers.
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
    private const val ICONS = "http://maps.google.com/mapfiles/kml"
    private const val CIRCLE_ICON = "$ICONS/shapes/placemark_circle.png"
    private const val START_ICON = "$ICONS/paddle/go.png"
    private const val END_ICON = "$ICONS/paddle/stop.png"
    private const val TOP_SPEED_ICON = "$ICONS/shapes/flag.png"
    private const val CAR_ICON = "$ICONS/shapes/cabs.png"

    /** Never divides the colours finer than this, so a crawl in traffic isn't painted red. */
    private const val MIN_TOP_SPEED_MPS = 8.0

    /** The tour looks at the car from this far behind and above, in metres and degrees. */
    private const val TOUR_RANGE_M = 320.0
    private const val TOUR_TILT_DEGREES = 62.0
    private const val TOUR_MAX_STEPS = 90
    private const val TOUR_MIN_SECONDS = 25.0
    private const val TOUR_MAX_SECONDS = 120.0

    /** How many times faster than the drive the tour flies, before the limits above. */
    private const val TOUR_SPEED_UP = 12.0
    private const val OVERVIEW_MIN_RANGE_M = 600.0

    private enum class Pin(val id: String) { START("start"), END("end"), TOP_SPEED("topspeed") }

    private class Mark(
        val name: String,
        /** One plain line, for GPX. */
        val plain: String,
        val rows: List<Pair<String, String>>,
        val style: String,
        val point: TripPoint,
    )

    fun kml(points: List<TripPoint>, text: TripText): String {
        val top = maxOf(points.maxOfOrNull { it.speedMps ?: 0.0 } ?: 0.0, MIN_TOP_SPEED_MPS)
        val step = top / SPEED_COLOURS.size
        fun band(p: TripPoint) = ((p.speedMps ?: 0.0) / step).toInt().coerceIn(0, SPEED_COLOURS.lastIndex)
        fun bandName(i: Int) = "%.0f–%.0f %s".format(Locale.US, i * step * text.speedFactor, (i + 1) * step * text.speedFactor, text.speedUnit)

        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:gx=\"http://www.google.com/kml/ext/2.2\">\n<Document>\n")
        out.append("<name>").append(xml(text.name)).append("</name>\n<open>1</open>\n")
        out.append("<Snippet maxLines=\"1\">").append(xml(text.description)).append("</Snippet>\n")
        out.append("<description>").append(table(text.facts.ifEmpty { listOf("" to text.description) }, text)).append("</description>\n")
        overview(points)?.let { out.append(it) }

        // Styles. Each has a balloon without Earth's "directions to here" links.
        val balloon = "<BalloonStyle><text><![CDATA[<h3>$[name]</h3>$[description]]]></text></BalloonStyle>"
        SPEED_COLOURS.forEachIndexed { i, colour ->
            // The dark edge keeps the colours readable over bright satellite pictures.
            out.append("<Style id=\"speed$i\"><LineStyle><color>").append(kmlColour(colour))
                .append("</color><width>6</width><gx:outerColor>b3000000</gx:outerColor><gx:outerWidth>0.25</gx:outerWidth></LineStyle>")
                .append(balloon).append("</Style>\n")
        }
        EVENT_COLOURS.forEach { (kind, colour) ->
            // The name shows only under the pointer, or a busy drive would be a wall of text.
            val id = kind.name.lowercase(Locale.US)
            out.append(iconStyle("${id}_n", CIRCLE_ICON, 1.1, kmlColour(colour), labelScale = 0.0, balloon = balloon))
            out.append(iconStyle("${id}_h", CIRCLE_ICON, 1.5, kmlColour(colour), labelScale = 1.0, balloon = balloon))
            out.append(styleMap(id))
        }
        out.append(iconStyle(Pin.START.id, START_ICON, 1.1, null, 0.9, balloon, paddle = true))
        out.append(iconStyle(Pin.END.id, END_ICON, 1.1, null, 0.9, balloon, paddle = true))
        out.append(iconStyle(Pin.TOP_SPEED.id, TOP_SPEED_ICON, 1.2, null, 0.9, balloon))
        out.append("<Style id=\"car\"><IconStyle><scale>1.2</scale><Icon><href>").append(CAR_ICON)
            .append("</href></Icon></IconStyle><LabelStyle><scale>0</scale></LabelStyle>")
            .append("<LineStyle><color>00ffffff</color><width>0</width></LineStyle></Style>\n")
        // One line in the list for the whole path, instead of one for every stretch.
        out.append("<Style id=\"oneLine\"><ListStyle><listItemType>checkHideChildren</listItemType></ListStyle></Style>\n")

        out.append("<Schema id=\"readings\">")
        out.append(arrayField("speed", "${text.speedLabel} (${text.speedUnit})"))
        out.append(arrayField("forward_g", text.forwardGLabel))
        out.append(arrayField("side_g", text.sideGLabel))
        out.append("</Schema>\n")

        val marks = marks(points, text)
        out.append("<Folder>\n<name>").append(xml(text.driveFolder)).append("</name>\n<open>1</open>\n")
        marks.filter { it.style in PIN_IDS }.forEach { out.append(placemark(it, text, timed = false)) }
        out.append("</Folder>\n")

        out.append("<Folder>\n<name>").append(xml(text.speedFolder)).append("</name>\n")
        out.append("<Snippet maxLines=\"1\">").append(xml("${bandName(0)} … ${bandName(SPEED_COLOURS.lastIndex)}")).append("</Snippet>\n")
        out.append("<styleUrl>#oneLine</styleUrl>\n")
        for (piece in Trips.segments(points)) {
            var from = 0
            while (from < piece.lastIndex) {
                // Each stretch is coloured by the point it leads to, and shares its ends with its neighbours.
                val colour = band(piece[from + 1])
                var to = from + 1
                while (to < piece.lastIndex && band(piece[to + 1]) == colour) to++
                out.append("<Placemark><name>").append(xml(bandName(colour))).append("</name><description>")
                    .append(table(listOf(text.timeLabel to "${text.clock(piece[from].timeMs)} – ${text.clock(piece[to].timeMs)}"), text))
                    .append("</description><styleUrl>#speed").append(colour)
                    .append("</styleUrl><LineString><tessellate>1</tessellate><coordinates>")
                for (i in from..to) out.append(coordinates(piece[i])).append(' ')
                out.append("</coordinates></LineString></Placemark>\n")
                from = to
            }
        }
        out.append("</Folder>\n")

        out.append("<Folder>\n<name>").append(xml(text.gFolder)).append("</name>\n")
        marks.filter { it.style !in PIN_IDS }.forEach { out.append(placemark(it, text, timed = true)) }
        out.append("</Folder>\n")

        if (points.size >= 2) {
            out.append(track(points, text))
            out.append(tour(points, text))
        }
        out.append("</Document>\n</kml>\n")
        return out.toString()
    }

    private val PIN_IDS = Pin.entries.map { it.id }.toSet()

    private fun iconStyle(
        id: String,
        icon: String,
        scale: Double,
        colour: String?,
        labelScale: Double,
        balloon: String,
        paddle: Boolean = false,
    ): String {
        val tint = colour?.let { "<color>$it</color>" }.orEmpty()
        // A paddle stands on its tip; the other icons sit on their middle.
        val hotSpot = if (paddle) "<hotSpot x=\"32\" y=\"1\" xunits=\"pixels\" yunits=\"pixels\"/>" else ""
        return "<Style id=\"$id\"><IconStyle>$tint<scale>${number(scale, 1)}</scale><Icon><href>$icon</href></Icon>$hotSpot</IconStyle>" +
            "<LabelStyle><scale>${number(labelScale, 1)}</scale></LabelStyle>$balloon</Style>\n"
    }

    private fun styleMap(id: String) =
        "<StyleMap id=\"$id\"><Pair><key>normal</key><styleUrl>#${id}_n</styleUrl></Pair>" +
            "<Pair><key>highlight</key><styleUrl>#${id}_h</styleUrl></Pair></StyleMap>\n"

    private fun arrayField(name: String, shown: String) =
        "<gx:SimpleArrayField name=\"$name\" type=\"float\"><displayName>${xml(shown)}</displayName></gx:SimpleArrayField>"

    private fun placemark(mark: Mark, text: TripText, timed: Boolean): String {
        val out = StringBuilder()
        out.append("<Placemark><name>").append(xml(mark.name)).append("</name>")
        out.append("<Snippet maxLines=\"1\">").append(xml(mark.plain)).append("</Snippet>")
        out.append("<description>").append(table(mark.rows, text)).append("</description>")
        // With a time, a marker appears on Earth's time slider as the car reaches it.
        if (timed) out.append("<TimeStamp><when>").append(iso(mark.point.timeMs)).append("</when></TimeStamp>")
        out.append("<styleUrl>#").append(mark.style).append("</styleUrl><Point><coordinates>")
            .append(coordinates(mark.point)).append("</coordinates></Point></Placemark>\n")
        return out.toString()
    }

    /** A small table for a balloon. Values are escaped twice over: once for the page, once by being data. */
    private fun table(rows: List<Pair<String, String>>, text: TripText): String {
        val out = StringBuilder("<![CDATA[<table cellpadding=\"3\"")
        if (text.rightToLeft) out.append(" dir=\"rtl\"")
        out.append(">")
        rows.forEach { (label, value) ->
            out.append("<tr>")
            if (label.isNotEmpty()) out.append("<td><b>").append(xml(label)).append("</b></td>")
            out.append("<td>").append(xml(value)).append("</td></tr>")
        }
        out.append("</table>]]>")
        return out.toString()
    }

    /** The whole drive from straight above, which is where Earth starts and the tour ends. */
    private fun overview(points: List<TripPoint>): String? {
        if (points.isEmpty()) return null
        val south = points.minOf { it.latitude }
        val north = points.maxOf { it.latitude }
        val west = points.minOf { it.longitude }
        val east = points.maxOf { it.longitude }
        val corner = TripPoint(0, south, west, null, null, null)
        val across = Trips.metersBetween(corner, corner.copy(latitude = north, longitude = east))
        return lookAt((south + north) / 2, (west + east) / 2, heading = 0.0, tilt = 0.0, range = max(across * 1.6, OVERVIEW_MIN_RANGE_M)) + "\n"
    }

    private fun lookAt(latitude: Double, longitude: Double, heading: Double, tilt: Double, range: Double) =
        "<LookAt><longitude>${number(longitude, 6)}</longitude><latitude>${number(latitude, 6)}</latitude><altitude>0</altitude>" +
            "<heading>${number(heading, 1)}</heading><tilt>${number(tilt, 1)}</tilt><range>${number(range, 0)}</range>" +
            "<altitudeMode>relativeToGround</altitudeMode></LookAt>"

    /**
     * The drive against time. Earth moves the car along it with the time slider, and
     * draws speed and G-force in its profile graph.
     */
    private fun track(points: List<TripPoint>, text: TripText): String {
        val out = StringBuilder()
        out.append("<Placemark><name>").append(xml(text.timelineName)).append("</name><styleUrl>#car</styleUrl>\n<gx:Track>\n")
        points.forEach { out.append("<when>").append(iso(it.timeMs)).append("</when>\n") }
        points.forEach { out.append("<gx:coord>").append(number(it.longitude, 6)).append(' ').append(number(it.latitude, 6)).append(" 0</gx:coord>\n") }
        out.append("<ExtendedData><SchemaData schemaUrl=\"#readings\">\n")
        fun array(name: String, value: (TripPoint) -> Double?, decimals: Int) {
            out.append("<gx:SimpleArrayData name=\"").append(name).append("\">")
            // Every array must be as long as the track, so a missing reading is written as 0.
            points.forEach { out.append("<gx:value>").append(number(value(it) ?: 0.0, decimals)).append("</gx:value>") }
            out.append("</gx:SimpleArrayData>\n")
        }
        array("speed", { p -> p.speedMps?.let { it * text.speedFactor } }, 1)
        array("forward_g", { it.forwardG }, 3)
        array("side_g", { it.sideG }, 3)
        out.append("</SchemaData></ExtendedData>\n</gx:Track></Placemark>\n")
        return out.toString()
    }

    /** A flight along the drive from behind the car, ending with the whole of it in view. */
    private fun tour(points: List<TripPoint>, text: TripText): String {
        val stride = max(1, (points.size - 1 + TOUR_MAX_STEPS - 1) / TOUR_MAX_STEPS)
        val stops = points.indices.filter { it % stride == 0 }.map { points[it] }
        val driveSeconds = (points.last().timeMs - points.first().timeMs) / 1000.0
        val seconds = (driveSeconds / TOUR_SPEED_UP).coerceIn(TOUR_MIN_SECONDS, TOUR_MAX_SECONDS)
        val each = seconds / stops.size

        val out = StringBuilder()
        out.append("<gx:Tour><name>").append(xml(text.tourName)).append("</name>\n<gx:Playlist>\n")
        var heading = 0.0
        stops.forEachIndexed { i, stop ->
            // Looks the way the car is about to go; standing still keeps the last direction.
            val next = stops.getOrNull(i + 1)
            if (next != null && Trips.metersBetween(stop, next) > 2.0) heading = bearing(stop, next)
            // The first flight arrives from wherever Earth was; the rest join into one smooth run.
            out.append("<gx:FlyTo><gx:duration>").append(number(if (i == 0) 5.0 else each, 2)).append("</gx:duration>")
                .append("<gx:flyToMode>").append(if (i == 0) "bounce" else "smooth").append("</gx:flyToMode>")
                .append(lookAt(stop.latitude, stop.longitude, heading, TOUR_TILT_DEGREES, TOUR_RANGE_M))
                .append("</gx:FlyTo>\n")
        }
        out.append("<gx:Wait><gx:duration>1.0</gx:duration></gx:Wait>\n")
        overview(points)?.let { out.append("<gx:FlyTo><gx:duration>4.0</gx:duration><gx:flyToMode>bounce</gx:flyToMode>").append(it.trim()).append("</gx:FlyTo>\n") }
        out.append("</gx:Playlist>\n</gx:Tour>\n")
        return out.toString()
    }

    /** The compass direction from one place to the next, in degrees from north. */
    private fun bearing(from: TripPoint, to: TripPoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
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
                .append("</time><name>").append(xml(mark.name)).append("</name><desc>").append(xml(mark.plain))
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

    /** Where the drive began and ended, its top speed, and each hard push. */
    private fun marks(points: List<TripPoint>, text: TripText): List<Mark> {
        if (points.isEmpty()) return emptyList()
        fun rows(p: TripPoint, g: Double? = null) = listOfNotNull(
            g?.let { text.gLabel to "%.2f G".format(Locale.US, it) },
            p.speedMps?.let { text.speedLabel to text.speed(it) },
            text.timeLabel to text.clock(p.timeMs),
        )
        fun plain(p: TripPoint) = listOfNotNull(p.speedMps?.let(text::speed), text.clock(p.timeMs)).joinToString(" · ")

        val first = points.first()
        val marks = mutableListOf(Mark(text.start, text.clock(first.timeMs), listOf(text.timeLabel to text.clock(first.timeMs)), Pin.START.id, first))
        points.filter { it.speedMps != null }.maxByOrNull { it.speedMps!! }?.let { fastest ->
            marks += Mark("${text.topSpeed} ${text.speed(fastest.speedMps!!)}", text.clock(fastest.timeMs), rows(fastest), Pin.TOP_SPEED.id, fastest)
        }
        Trips.events(points).forEach { event ->
            val name = "%s %.2f G".format(Locale.US, text.label(event.kind), event.g)
            marks += Mark(name, plain(event.point), rows(event.point, event.g), event.kind.name.lowercase(Locale.US), event.point)
        }
        val last = points.last()
        marks += Mark(text.end, text.clock(last.timeMs), listOf(text.timeLabel to text.clock(last.timeMs)), Pin.END.id, last)
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
