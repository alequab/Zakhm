package app.zakhm.core

import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
    val totalGLabel: String = "Total G",
    val driveFolder: String = "Drive",
    val tourName: String = "Fly along the drive",
    val timelineName: String = "Car on the timeline",
    val wallName: String = "Speed wall (3D)",
    val stopsFolder: String = "Stops",
    val stopped: String = "Stopped",
    val durationLabel: String = "Duration",
    val launchesFolder: String = "Launches",
    /** The speeds a launch is timed to, in metres per second. */
    val launchTargetsMps: List<Double> = listOf(60.0, 80.0, 100.0).map { it / 3.6 },
    val speedBefore: String = "Speed before",
    val speedAfter: String = "Speed after",
    val brakingDistance: String = "Braking distance",
    val postsFolder: String = "Distance posts",
    val distanceLabel: String = "Distance",
    val averageSpeed: String = "Average speed so far",
    val distanceUnit: String = "km",
    /** Metres in one [distanceUnit]. */
    val metresPerDistanceUnit: Double = 1000.0,
    val metreUnit: String = "m",
    val secondUnit: String = "s",
    val gLayerName: String = "Path by G-force",
    val gapsFolder: String = "GPS signal lost",
    val gapName: String = "GPS lost",
    val arrowsFolder: String = "Direction",
    val weakGpsName: String = "Weak GPS",
    val accuracyLabel: String = "GPS accuracy",
    val heightLabel: String = "Height",
    val speedFromLabel: String = "Speed from",
    val speedSources: Map<SpeedInput, String> = mapOf(
        SpeedInput.PHONE to "Phone GPS",
        SpeedInput.OBD to "OBD",
        SpeedInput.ANDROID_AUTO to "Android Auto",
    ),
    val legendName: String = "Legend",
    /** A number of seconds as words, such as "2 min 5 s". */
    val duration: (Long) -> String = { s -> if (s < 60) "$s s" else "${s / 60} min ${s % 60} s" },
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

    fun seconds(value: Double) = "%.1f %s".format(Locale.US, value, secondUnit)

    fun metres(value: Double) = "%.0f %s".format(Locale.US, value, metreUnit)

    /** "0–100 km/h" for a launch target. */
    fun launchTarget(mps: Double) = "0–%.0f %s".format(Locale.US, mps * speedFactor, speedUnit)

    private companion object {
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    }
}

/** A colour key for the map, as `RRGGBB` and words, with a title. */
class TripLegend(val title: String, val rows: List<Pair<String, String>>)

/**
 * Writes a saved drive as files that map programs open. The KML is packed with its
 * pictures into a KMZ, so it looks the same everywhere and needs no internet for its
 * markers; GPX is what nearly everything reads.
 *
 * The KML is written for Google Earth first: besides the coloured path and markers
 * it holds a track that moves a car along the time slider, with speed and G-force
 * for Earth's profile graph, a tour that flies along the drive and opens each hard
 * push's card as it passes, and a wall under the path whose height is the speed. Programs
 * that don't know those parts skip them and still show the path and markers.
 */
object TripExport {
    /** The pictures a KMZ carries, by file name inside its `files` folder. */
    enum class Picture(val file: String) {
        /** White, so each kind of push can tint it its own colour. */
        CIRCLE("circle.png"),
        START("start.png"),
        END("end.png"),
        TOP_SPEED("top.png"),
        CAR("car.png"),
        /** White with a dark edge, pointing north; each arrow is turned on the map. */
        ARROW("arrow.png"),
        POST("post.png"),
        STOP("stop.png"),
        LAUNCH("launch.png"),
        GAP("gap.png"),
        LEGEND("legend.png"),
        ;

        val href get() = "files/$file"
    }

    /** Slow to fast, as `RRGGBB`. */
    private val SPEED_COLOURS = listOf("2B6CFF", "00A6E0", "00B894", "5BC236", "C8D21E", "FFB300", "FF6A00", "E81E1E")
    private val EVENT_COLOURS = mapOf(
        GEventKind.SPEEDING_UP to "2ECC71",
        GEventKind.BRAKING to "E81E1E",
        GEventKind.LEFT_TURN to "FFB300",
        GEventKind.RIGHT_TURN to "FFB300",
    )

    /** Gentle to hard, for the path coloured by G-force, with where each colour begins. */
    private val G_COLOURS = listOf("2B6CFF", "00B894", "C8D21E", "FF6A00", "E81E1E")
    private val G_STEPS = listOf(0.0, 0.1, 0.2, 0.3, 0.4)

    /** Worse than this, GPS may put the path on the wrong side of the road. */
    private const val WEAK_GPS_M = 15.0

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

    /** The speed wall's height for each metre per second, so 100 km/h stands about 140 m tall. */
    private const val WALL_METRES_PER_MPS = 5.0

    /** Direction arrows: at least this far apart, and no more than [MAX_ARROWS] on a long drive. */
    private const val ARROW_MIN_SPACING_M = 250.0
    private const val MAX_ARROWS = 40

    /** Start and end of right-to-left text (embedding), for lines Earth would lay out left to right. */
    private val RTL_START = Char(0x202B).toString()
    private val RTL_END = Char(0x202C).toString()

    /** The legend's width on screen; Earth on a phone multiplies it by the screen density. */
    private const val LEGEND_WIDTH = 130

    /** Distance posts: one per unit, unless that would be more than [MAX_POSTS]. */
    private const val MAX_POSTS = 60

    private enum class Pin(val id: String) { START("start"), END("end"), TOP_SPEED("topspeed") }

    /** One value a marker carries: a fixed key for programs, and words for people. */
    private class Field(val key: String, val label: String, val value: String)

    private class Mark(
        val name: String,
        /** One plain line, for GPX. */
        val plain: String,
        val fields: List<Field>,
        val style: String,
        val point: TripPoint,
    ) {
        val isEvent get() = style !in PIN_IDS
    }

    /** A run of the path in one colour; it shares its end points with its neighbours. */
    private class Stretch(val band: Int, val points: List<TripPoint>)

    private class SpeedBands(points: List<TripPoint>, private val text: TripText) {
        private val step = maxOf(points.maxOfOrNull { it.speedMps ?: 0.0 } ?: 0.0, MIN_TOP_SPEED_MPS) / SPEED_COLOURS.size

        fun band(p: TripPoint) = ((p.speedMps ?: 0.0) / step).toInt().coerceIn(0, SPEED_COLOURS.lastIndex)

        fun name(i: Int) = "%.0f–%.0f %s".format(Locale.US, i * step * text.speedFactor, (i + 1) * step * text.speedFactor, text.speedUnit)
    }

    private fun gBand(p: TripPoint): Int {
        val g = Trips.combinedG(p) ?: 0.0
        return G_STEPS.indexOfLast { g >= it }.coerceAtLeast(0)
    }

    private fun gBandName(i: Int) =
        if (i == G_STEPS.lastIndex) "≥ %.1f G".format(Locale.US, G_STEPS[i]) else "%.1f–%.1f G".format(Locale.US, G_STEPS[i], G_STEPS[i + 1])

    /** The speed colours of this drive, for the picture the map shows in its corner. */
    fun legend(points: List<TripPoint>, text: TripText): TripLegend {
        val bands = SpeedBands(points, text)
        return TripLegend(text.speedLabel, SPEED_COLOURS.indices.reversed().map { SPEED_COLOURS[it] to bands.name(it) })
    }

    /** The drive as a KMZ: the KML first, then [pictures] by [Picture.file]. */
    fun kmz(points: List<TripPoint>, text: TripText, pictures: Map<Picture, ByteArray>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            // Programs read the first KML in the archive, so it goes first.
            zip.putNextEntry(ZipEntry("doc.kml"))
            zip.write(kml(points, text, legendSize = pictures[Picture.LEGEND]?.let(::pngSize)).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            pictures.forEach { (picture, bytes) ->
                zip.putNextEntry(ZipEntry(picture.href))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /**
     * The drive as KML. With a [legendSize] (the legend picture's width and height in pixels),
     * it shows [Picture.LEGEND] in the corner.
     */
    fun kml(points: List<TripPoint>, text: TripText, legendSize: Pair<Int, Int>? = null): String {
        val bands = SpeedBands(points, text)
        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:gx=\"http://www.google.com/kml/ext/2.2\">\n<Document>\n")
        out.append("<name>").append(xml(text.shown(text.name))).append("</name>\n<open>1</open>\n")
        out.append("<Snippet maxLines=\"1\">").append(xml(text.shown(text.description))).append("</Snippet>\n")
        // Plain words here: Google Earth on the web shows a document's description as written, tags and all.
        // In Arabic each fact gets a line of its own, wrapped as right-to-left text: Earth lays every line out left to right,
        // so a Latin "G" and the numbers would swap places with the words around them.
        val about = when {
            text.facts.isEmpty() -> text.description
            text.rightToLeft -> text.facts.joinToString("\n") { (label, value) -> "$RTL_START$label: $value$RTL_END" }
            else -> text.facts.joinToString(" · ") { (label, value) -> "$label: $value" }
        }
        out.append("<description>").append(xml(about)).append("</description>\n")
        overview(points)?.let { out.append(it) }
        styles(out)

        out.append("<Schema id=\"readings\">")
        out.append(arrayField("speed", "${text.speedLabel} (${text.speedUnit})"))
        out.append(arrayField("forward_g", text.forwardGLabel))
        out.append(arrayField("side_g", text.sideGLabel))
        out.append(arrayField("total_g", text.totalGLabel))
        out.append("</Schema>\n")

        val events = Trips.events(points)
        val marks = marks(points, text, events)
        val pushes = marks.filter { it.isEvent }
        folder(out, text, text.driveFolder, open = true) {
            marks.filter { !it.isEvent }.forEach { out.append(placemark(it, text, id = null)) }
        }

        // Under the path, so the path draws on top of it.
        val weak = points.any { (it.accuracyM ?: 0.0) > WEAK_GPS_M }
        if (weak) {
            folder(out, text, text.weakGpsName, oneLine = true) {
                for (stretch in stretches(points) { if ((it.accuracyM ?: 0.0) > WEAK_GPS_M) 1 else 0 }) {
                    if (stretch.band == 0) continue
                    val worst = stretch.points.maxOf { it.accuracyM ?: 0.0 }
                    out.append(stretchPlacemark(stretch, text.weakGpsName, "weak", text, listOf(text.accuracyLabel to "± " + text.metres(worst)), band = false))
                    line(out, stretch.points)
                }
            }
        }

        val speedStretches = stretches(points, bands::band)
        folder(out, text, text.speedFolder, oneLine = true, snippet = "${bands.name(0)} … ${bands.name(SPEED_COLOURS.lastIndex)}") {
            for (stretch in speedStretches) {
                out.append(stretchPlacemark(stretch, bands.name(stretch.band), "speed", text))
                line(out, stretch.points)
            }
        }

        folder(out, text, text.gFolder) {
            pushes.forEachIndexed { i, mark -> out.append(placemark(mark, text, id = eventId(i))) }
        }

        val launches = Trips.launches(points, text.launchTargetsMps)
        if (launches.isNotEmpty()) {
            folder(out, text, text.launchesFolder) {
                launches.forEach { launch ->
                    val (target, seconds) = launch.reached.last()
                    val fields = launch.reached.map { (t, s) -> Field("launch_%.0f".format(Locale.US, t * text.speedFactor), text.launchTarget(t), text.seconds(s)) } +
                        Field("time", text.timeLabel, text.clock(launch.start.timeMs))
                    val name = "${text.launchTarget(target)} ${text.seconds(seconds)}"
                    out.append(placemark(Mark(name, name, fields, "launch", launch.start), text, id = null, timed = true))
                }
            }
        }

        val stops = Trips.stops(points)
        if (stops.isNotEmpty()) {
            folder(out, text, text.stopsFolder) {
                stops.forEach { stop ->
                    val name = "${text.stopped} ${text.duration(stop.seconds)}"
                    val fields = listOf(
                        Field("duration", text.durationLabel, text.duration(stop.seconds)),
                        Field("time", text.timeLabel, "${text.clock(stop.from.timeMs)} – ${text.clock(stop.to.timeMs)}"),
                    )
                    out.append(placemark(Mark(name, name, fields, "stop", stop.from), text, id = null, timed = true))
                }
            }
        }

        val posts = posts(points, text)
        if (posts.isNotEmpty()) {
            folder(out, text, text.postsFolder) {
                val start = points.first().timeMs
                posts.forEach { post ->
                    val name = "%.0f %s".format(Locale.US, post.distanceM / text.metresPerDistanceUnit, text.distanceUnit)
                    val seconds = (post.point.timeMs - start) / 1000.0
                    val fields = listOfNotNull(
                        Field("distance", text.distanceLabel, name),
                        Field("time", text.timeLabel, text.clock(post.point.timeMs)),
                        seconds.takeIf { it > 0 }?.let { Field("average_speed", text.averageSpeed, text.speed(post.distanceM / it)) },
                    )
                    out.append(placemark(Mark(name, name, fields, "post", post.point), text, id = null, timed = true))
                }
            }
        }

        val arrows = arrows(points)
        if (arrows.isNotEmpty()) {
            folder(out, text, text.arrowsFolder) {
                arrows.forEach { (p, heading) ->
                    // A style of its own, since each arrow has its own turn.
                    out.append("<Placemark><Style><IconStyle><scale>0.8</scale><heading>").append(number(heading, 0))
                        .append("</heading><Icon><href>").append(Picture.ARROW.href).append("</href></Icon></IconStyle>")
                        .append("<LabelStyle><scale>0</scale></LabelStyle></Style><Point><coordinates>").append(coordinates(p))
                        .append("</coordinates></Point></Placemark>\n")
                }
            }
        }

        val gaps = Trips.gaps(points)
        if (gaps.isNotEmpty()) {
            folder(out, text, text.gapsFolder) {
                gaps.forEach { (before, after) ->
                    val seconds = (after.timeMs - before.timeMs) / 1000
                    val name = "${text.gapName} ${text.duration(seconds)}"
                    val fields = listOf(
                        Field("duration", text.durationLabel, text.duration(seconds)),
                        Field("time", text.timeLabel, "${text.clock(before.timeMs)} – ${text.clock(after.timeMs)}"),
                    )
                    out.append(placemark(Mark(name, name, fields, "gap", before), text, id = null, timed = true))
                    // A thin grey line across, so the break reads as missing, not as a turn.
                    out.append("<Placemark><name>").append(xml(text.shown(name))).append("</name><styleUrl>#gapLine</styleUrl>")
                    line(out, listOf(before, after))
                }
            }
        }

        // Off at first, so the speed colours stay the main picture.
        folder(out, text, text.gLayerName, visible = false, oneLine = true, snippet = "${gBandName(0)} … ${gBandName(G_STEPS.lastIndex)}") {
            for (stretch in stretches(points, ::gBand)) {
                out.append(stretchPlacemark(stretch, gBandName(stretch.band), "g", text))
                line(out, stretch.points)
            }
        }

        // Off at first: seen from straight above it only hides the path. Tilt the view to see it.
        folder(out, text, text.wallName, visible = false, oneLine = true) {
            for (stretch in speedStretches) {
                out.append(stretchPlacemark(stretch, bands.name(stretch.band), "wall", text))
                out.append("<LineString><extrude>1</extrude><tessellate>1</tessellate><altitudeMode>relativeToGround</altitudeMode><coordinates>")
                stretch.points.forEach { out.append(coordinates(it, (it.speedMps ?: 0.0) * WALL_METRES_PER_MPS)).append(' ') }
                out.append("</coordinates></LineString></Placemark>\n")
            }
        }

        if (legendSize != null) {
            // Low on the left, above the panel Earth on a phone keeps at the bottom. A fixed width,
            // since Earth on a phone enlarges pictures by the screen density, and a height to match: Earth
            // on a phone ignores "keep the shape" and stretches it.
            out.append("<ScreenOverlay><name>").append(xml(text.shown(text.legendName))).append("</name><Icon><href>").append(Picture.LEGEND.href)
                .append("</href></Icon><overlayXY x=\"0\" y=\"0\" xunits=\"fraction\" yunits=\"fraction\"/>")
                .append("<screenXY x=\"10\" y=\"0.2\" xunits=\"pixels\" yunits=\"fraction\"/>")
                .append("<size x=\"").append(LEGEND_WIDTH).append("\" y=\"").append(LEGEND_WIDTH * legendSize.second / legendSize.first.coerceAtLeast(1))
                .append("\" xunits=\"pixels\" yunits=\"pixels\"/></ScreenOverlay>\n")
        }

        if (points.size >= 2) {
            out.append(track(points, text))
            out.append(tour(points, pushes, text))
        }
        out.append("</Document>\n</kml>\n")
        return out.toString()
    }

    private fun styles(out: StringBuilder) {
        // Each has a balloon without Earth's "directions to here" links.
        val balloon = BALLOON
        SPEED_COLOURS.forEachIndexed { i, colour -> out.append(lineStyle("speed$i", colour, 6)) }
        G_COLOURS.forEachIndexed { i, colour -> out.append(lineStyle("g$i", colour, 6)) }
        SPEED_COLOURS.forEachIndexed { i, colour ->
            // See-through, so the ground and the path show through the wall.
            out.append("<Style id=\"wall$i\"><LineStyle><color>").append(kmlColour(colour))
                .append("</color><width>2</width></LineStyle><PolyStyle><color>").append(kmlColour(colour, "99"))
                .append("</color></PolyStyle>").append(balloon).append("</Style>\n")
        }
        // A wide pale band under the path where GPS was unsure of the position.
        out.append("<Style id=\"weak\"><LineStyle><color>").append(kmlColour("FFFFFF", "70")).append("</color><width>16</width></LineStyle>")
            .append(balloon).append("</Style>\n")
        out.append("<Style id=\"gapLine\"><LineStyle><color>").append(kmlColour("9E9E9E", "cc")).append("</color><width>2</width></LineStyle>")
            .append(balloon).append("</Style>\n")
        EVENT_COLOURS.forEach { (kind, colour) ->
            // The name shows only under the pointer, or a busy drive would be a wall of text.
            val id = kind.name.lowercase(Locale.US)
            out.append(iconStyle("${id}_n", Picture.CIRCLE, 1.1, kmlColour(colour), labelScale = 0.0))
            out.append(iconStyle("${id}_h", Picture.CIRCLE, 1.5, kmlColour(colour), labelScale = 1.0))
            out.append(styleMap(id))
        }
        out.append(iconStyle(Pin.START.id, Picture.START, 1.2, null, 0.9, pin = true))
        out.append(iconStyle(Pin.END.id, Picture.END, 1.2, null, 0.9, pin = true))
        out.append(iconStyle(Pin.TOP_SPEED.id, Picture.TOP_SPEED, 1.2, null, 0.9))
        out.append(iconStyle("launch", Picture.LAUNCH, 1.1, null, 0.8))
        out.append(iconStyle("stop", Picture.STOP, 1.0, null, 0.0))
        out.append(iconStyle("gap", Picture.GAP, 1.0, null, 0.0))
        out.append(iconStyle("post", Picture.POST, 0.7, null, 0.7))
        out.append("<Style id=\"car\"><IconStyle><scale>1.3</scale><Icon><href>").append(Picture.CAR.href)
            .append("</href></Icon></IconStyle><LabelStyle><scale>0</scale></LabelStyle>")
            .append("<LineStyle><color>00ffffff</color><width>0</width></LineStyle></Style>\n")
        // One line in the list for a whole path, instead of one for every stretch.
        out.append("<Style id=\"oneLine\"><ListStyle><listItemType>checkHideChildren</listItemType></ListStyle></Style>\n")
    }

    private const val BALLOON = "<BalloonStyle><text><![CDATA[<h3>$[name]</h3>$[description]]]></text></BalloonStyle>"

    /** The dark edge keeps the colours readable over bright satellite pictures. */
    private fun lineStyle(id: String, colour: String, width: Int) =
        "<Style id=\"$id\"><LineStyle><color>${kmlColour(colour)}</color><width>$width</width>" +
            "<gx:outerColor>b3000000</gx:outerColor><gx:outerWidth>0.25</gx:outerWidth></LineStyle>$BALLOON</Style>\n"

    private inline fun folder(
        out: StringBuilder,
        text: TripText,
        name: String,
        open: Boolean = false,
        visible: Boolean = true,
        oneLine: Boolean = false,
        snippet: String? = null,
        body: () -> Unit,
    ) {
        out.append("<Folder>\n<name>").append(xml(text.shown(name))).append("</name>\n")
        if (open) out.append("<open>1</open>\n")
        // Only the folder is switched off, so switching it on shows every piece.
        if (!visible) out.append("<visibility>0</visibility>\n")
        snippet?.let { out.append("<Snippet maxLines=\"1\">").append(xml(text.shown(it))).append("</Snippet>\n") }
        if (oneLine) out.append("<styleUrl>#oneLine</styleUrl>\n")
        body()
        out.append("</Folder>\n")
    }

    /** A path on the ground, closing the placemark the caller opened. */
    private fun line(out: StringBuilder, points: List<TripPoint>) {
        out.append("<LineString><tessellate>1</tessellate><coordinates>")
        points.forEach { out.append(coordinates(it)).append(' ') }
        out.append("</coordinates></LineString></Placemark>\n")
    }

    private val PIN_IDS = Pin.entries.map { it.id }.toSet()

    private fun eventId(index: Int) = "event$index"

    /** The path cut where its colour changes, each run coloured by the points it leads to. */
    private fun stretches(points: List<TripPoint>, band: (TripPoint) -> Int): List<Stretch> {
        val out = mutableListOf<Stretch>()
        for (piece in Trips.segments(points)) {
            var from = 0
            while (from < piece.lastIndex) {
                val colour = band(piece[from + 1])
                var to = from + 1
                while (to < piece.lastIndex && band(piece[to + 1]) == colour) to++
                out += Stretch(colour, piece.subList(from, to + 1))
                from = to
            }
        }
        return out
    }

    /** A stretch's placemark up to its geometry, which the caller writes and closes. */
    private fun stretchPlacemark(
        stretch: Stretch,
        name: String,
        style: String,
        text: TripText,
        extra: List<Pair<String, String>> = emptyList(),
        band: Boolean = true,
    ): String {
        val out = StringBuilder("<Placemark><name>").append(xml(text.shown(name))).append("</name>")
        val time = "${text.clock(stretch.points.first().timeMs)} – ${text.clock(stretch.points.last().timeMs)}"
        out.append("<description>").append(table(listOf(text.timeLabel to time) + extra, text)).append("</description>")
        out.append("<styleUrl>#").append(style).append(if (band) stretch.band.toString() else "").append("</styleUrl>")
        return out.toString()
    }

    private fun iconStyle(id: String, picture: Picture, scale: Double, colour: String?, labelScale: Double, pin: Boolean = false): String {
        val tint = colour?.let { "<color>$it</color>" }.orEmpty()
        // A pin stands on its tip; the other pictures sit on their middle.
        val hotSpot = if (pin) "<hotSpot x=\"0.5\" y=\"0\" xunits=\"fraction\" yunits=\"fraction\"/>" else ""
        return "<Style id=\"$id\"><IconStyle>$tint<scale>${number(scale, 1)}</scale><Icon><href>${picture.href}</href></Icon>$hotSpot</IconStyle>" +
            "<LabelStyle><scale>${number(labelScale, 1)}</scale></LabelStyle>$BALLOON</Style>\n"
    }

    private fun styleMap(id: String) =
        "<StyleMap id=\"$id\"><Pair><key>normal</key><styleUrl>#${id}_n</styleUrl></Pair>" +
            "<Pair><key>highlight</key><styleUrl>#${id}_h</styleUrl></Pair></StyleMap>\n"

    private fun arrayField(name: String, shown: String) =
        "<gx:SimpleArrayField name=\"$name\" type=\"float\"><displayName>${xml(shown)}</displayName></gx:SimpleArrayField>"

    /**
     * A marker. Hard pushes get an [id], so the tour can open their cards. Those and any
     * [timed] marker get a time, so they appear on Earth's time slider as the car reaches them.
     */
    private fun placemark(mark: Mark, text: TripText, id: String?, timed: Boolean = id != null): String {
        val out = StringBuilder("<Placemark")
        id?.let { out.append(" id=\"").append(it).append('"') }
        out.append("><name>").append(xml(text.shown(mark.name))).append("</name>")
        out.append("<Snippet maxLines=\"1\">").append(xml(text.shown(mark.plain))).append("</Snippet>")
        out.append("<description>").append(table(mark.fields.map { it.label to it.value }, text)).append("</description>")
        if (timed) out.append("<TimeStamp><when>").append(iso(mark.point.timeMs)).append("</when></TimeStamp>")
        out.append("<styleUrl>#").append(mark.style).append("</styleUrl>")
        // The same values as data, which Earth lists on its own and other programs can read.
        out.append("<ExtendedData>")
        mark.fields.forEach { field ->
            out.append("<Data name=\"").append(field.key).append("\"><displayName>").append(xml(field.label))
                .append("</displayName><value>").append(xml(field.value)).append("</value></Data>")
        }
        out.append("</ExtendedData><Point><coordinates>")
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

    /** Distance posts one unit apart, or a round number of units when that would be too many. */
    private fun posts(points: List<TripPoint>, text: TripText): List<DistancePost> {
        val total = Trips.summary(points)?.distanceM ?: return emptyList()
        val units = total / text.metresPerDistanceUnit
        val every = listOf(1, 2, 5, 10, 20, 50, 100).first { units / it <= MAX_POSTS || it == 100 }
        return Trips.distancePosts(points, every * text.metresPerDistanceUnit)
    }

    /** Points along the path with the direction the car was going there. */
    private fun arrows(points: List<TripPoint>): List<Pair<TripPoint, Double>> {
        val total = Trips.summary(points)?.distanceM ?: return emptyList()
        val spacing = max(ARROW_MIN_SPACING_M, total / MAX_ARROWS)
        val out = mutableListOf<Pair<TripPoint, Double>>()
        for (piece in Trips.segments(points)) {
            // The first arrow sits half a spacing in, so it doesn't cover the start pin.
            var travelled = spacing / 2
            for (i in 1 until piece.size) {
                travelled += Trips.metersBetween(piece[i - 1], piece[i])
                if (travelled < spacing) continue
                // Looks a little ahead, so GPS wobble doesn't turn the arrow sideways.
                val ahead = piece.subList(i, piece.size).firstOrNull { Trips.metersBetween(piece[i - 1], it) > 15.0 } ?: continue
                out += piece[i - 1] to bearing(piece[i - 1], ahead)
                travelled = 0.0
            }
        }
        return out
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
     * draws speed and G-force in its profile graph. The car stays on the ground; the
     * height GPS gave is kept in the coordinates for programs that read it.
     */
    private fun track(points: List<TripPoint>, text: TripText): String {
        val out = StringBuilder()
        out.append("<Placemark><name>").append(xml(text.shown(text.timelineName))).append("</name><styleUrl>#car</styleUrl>\n<gx:Track>\n")
        points.forEach { out.append("<when>").append(iso(it.timeMs)).append("</when>\n") }
        points.forEach {
            out.append("<gx:coord>").append(number(it.longitude, 6)).append(' ').append(number(it.latitude, 6)).append(' ')
                .append(number(it.altitudeM ?: 0.0, 0)).append("</gx:coord>\n")
        }
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
        array("total_g", Trips::combinedG, 3)
        out.append("</SchemaData></ExtendedData>\n</gx:Track></Placemark>\n")
        return out.toString()
    }

    /**
     * A flight along the drive from behind the car, ending with the whole of it in view.
     * At each hard push it opens that marker's card and keeps flying; the card closes at
     * the next step. It never stops for it, because Google Earth on the web shows no
     * card during a tour and a pause there would only look like a stutter.
     */
    private fun tour(points: List<TripPoint>, events: List<Mark>, text: TripText): String {
        val stride = max(1, (points.size - 1 + TOUR_MAX_STEPS - 1) / TOUR_MAX_STEPS)
        val pushes = events.withIndex().associate { (i, mark) -> mark.point to eventId(i) }
        val stops = (points.indices.filter { it % stride == 0 }.map { points[it] } + pushes.keys)
            .distinct().sortedBy { it.timeMs }
        val driveSeconds = (points.last().timeMs - points.first().timeMs) / 1000.0
        val seconds = (driveSeconds / TOUR_SPEED_UP).coerceIn(TOUR_MIN_SECONDS, TOUR_MAX_SECONDS)
        val each = seconds / stops.size

        val out = StringBuilder()
        out.append("<gx:Tour><name>").append(xml(text.shown(text.tourName))).append("</name>\n<gx:Playlist>\n")
        var heading = 0.0
        var open: String? = null
        stops.forEachIndexed { i, stop ->
            // Looks the way the car is about to go; standing still keeps the last direction.
            val next = stops.getOrNull(i + 1)
            if (next != null && Trips.metersBetween(stop, next) > 2.0) heading = bearing(stop, next)
            // The first flight arrives from wherever Earth was; the rest join into one smooth run.
            out.append("<gx:FlyTo><gx:duration>").append(number(if (i == 0) 5.0 else each, 2)).append("</gx:duration>")
                .append("<gx:flyToMode>").append(if (i == 0) "bounce" else "smooth").append("</gx:flyToMode>")
                .append(lookAt(stop.latitude, stop.longitude, heading, TOUR_TILT_DEGREES, TOUR_RANGE_M))
                .append("</gx:FlyTo>\n")
            open?.let { out.append(balloon(it, shown = false)) }
            open = pushes[stop]?.also { out.append(balloon(it, shown = true)) }
        }
        open?.let { out.append(balloon(it, shown = false)) }
        out.append("<gx:Wait><gx:duration>1.0</gx:duration></gx:Wait>\n")
        overview(points)?.let { out.append("<gx:FlyTo><gx:duration>4.0</gx:duration><gx:flyToMode>bounce</gx:flyToMode>").append(it.trim()).append("</gx:FlyTo>\n") }
        out.append("</gx:Playlist>\n</gx:Tour>\n")
        return out.toString()
    }

    /** Opens or closes a marker's card during the tour. Earth undoes these when the tour ends. */
    private fun balloon(id: String, shown: Boolean) =
        "<gx:AnimatedUpdate><gx:duration>0.0</gx:duration><Update><targetHref/><Change>" +
            "<Placemark targetId=\"$id\"><gx:balloonVisibility>${if (shown) 1 else 0}</gx:balloonVisibility></Placemark>" +
            "</Change></Update></gx:AnimatedUpdate>\n"

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
        for (mark in marks(points, text, Trips.events(points))) {
            out.append("<wpt ").append(position(mark.point)).append(">")
            mark.point.altitudeM?.let { out.append("<ele>").append(number(it, 1)).append("</ele>") }
            out.append("<time>").append(iso(mark.point.timeMs))
                .append("</time><name>").append(xml(mark.name)).append("</name><desc>").append(xml(mark.plain))
                .append("</desc></wpt>\n")
        }
        out.append("<trk><name>").append(xml(text.name)).append("</name>\n")
        for (piece in Trips.segments(points)) {
            out.append("<trkseg>\n")
            for (p in piece) {
                out.append("<trkpt ").append(position(p)).append(">")
                // GPX wants the height before the time.
                p.altitudeM?.let { out.append("<ele>").append(number(it, 1)).append("</ele>") }
                out.append("<time>").append(iso(p.timeMs)).append("</time>")
                if (p.speedMps != null || p.forwardG != null || p.sideG != null || p.accuracyM != null || p.speedFrom != null) {
                    out.append("<extensions>")
                    // The one place most viewers look for a speed, in metres per second.
                    p.speedMps?.let {
                        out.append("<gpxtpx:TrackPointExtension><gpxtpx:speed>").append(number(it, 2))
                            .append("</gpxtpx:speed></gpxtpx:TrackPointExtension>")
                    }
                    p.forwardG?.let { out.append("<zakhm:forwardG>").append(number(it, 3)).append("</zakhm:forwardG>") }
                    p.sideG?.let { out.append("<zakhm:sideG>").append(number(it, 3)).append("</zakhm:sideG>") }
                    p.accuracyM?.let { out.append("<zakhm:accuracy>").append(number(it, 1)).append("</zakhm:accuracy>") }
                    p.speedFrom?.let { out.append("<zakhm:speedFrom>").append(it.name.lowercase(Locale.US)).append("</zakhm:speedFrom>") }
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
    private fun marks(points: List<TripPoint>, text: TripText, events: List<GEvent>): List<Mark> {
        if (points.isEmpty()) return emptyList()
        fun time(p: TripPoint) = Field("time", text.timeLabel, text.clock(p.timeMs))
        fun about(p: TripPoint) = listOfNotNull(
            p.altitudeM?.let { Field("height", text.heightLabel, text.metres(it)) },
            p.speedFrom?.let { from -> text.speedSources[from]?.let { Field("speed_from", text.speedFromLabel, it) } },
        )
        fun rows(p: TripPoint, g: Double? = null) = listOfNotNull(
            g?.let { Field("g_force", text.gLabel, "%.2f G".format(Locale.US, it)) },
            p.speedMps?.let { Field("speed", text.speedLabel, text.speed(it)) },
            time(p),
        ) + about(p)
        fun plain(p: TripPoint) = listOfNotNull(p.speedMps?.let(text::speed), text.clock(p.timeMs)).joinToString(" · ")

        val brakings = Trips.brakings(points, events).associateBy { it.event }
        val first = points.first()
        val marks = mutableListOf(Mark(text.start, text.clock(first.timeMs), listOf(time(first)) + about(first), Pin.START.id, first))
        points.filter { it.speedMps != null }.maxByOrNull { it.speedMps!! }?.let { fastest ->
            marks += Mark("${text.topSpeed} ${text.speed(fastest.speedMps!!)}", text.clock(fastest.timeMs), rows(fastest), Pin.TOP_SPEED.id, fastest)
        }
        events.forEach { event ->
            val name = "%s %.2f G".format(Locale.US, text.label(event.kind), event.g)
            val braking = brakings[event]?.takeIf { it.from !== it.to }?.let { b ->
                listOfNotNull(
                    b.from.speedMps?.let { Field("speed_before", text.speedBefore, text.speed(it)) },
                    b.to.speedMps?.let { Field("speed_after", text.speedAfter, text.speed(it)) },
                    Field("braking_distance", text.brakingDistance, text.metres(b.distanceM)),
                )
            }.orEmpty()
            marks += Mark(name, plain(event.point), rows(event.point, event.g) + braking, event.kind.name.lowercase(Locale.US), event.point)
        }
        val last = points.last()
        marks += Mark(text.end, text.clock(last.timeMs), listOf(time(last)) + about(last), Pin.END.id, last)
        return marks
    }

    /** Names as Earth shows them: it lays every line out left to right, so Arabic is wrapped as right-to-left text. */
    private fun TripText.shown(words: String) = if (rightToLeft) RTL_START + words + RTL_END else words

    /** A PNG's width and height, from its header. */
    private fun pngSize(png: ByteArray): Pair<Int, Int>? {
        if (png.size < 24) return null
        fun int(at: Int) = (0 until 4).fold(0) { n, i -> (n shl 8) or (png[at + i].toInt() and 0xFF) }
        return int(16) to int(20)
    }

    /** KML writes colours backwards: opacity, blue, green, red. */
    private fun kmlColour(rgb: String, alpha: String = "ff") = alpha + rgb.substring(4, 6) + rgb.substring(2, 4) + rgb.substring(0, 2)

    private fun coordinates(p: TripPoint, altitude: Double = 0.0) =
        "%.6f,%.6f,%.0f".format(Locale.US, p.longitude, p.latitude, altitude)

    private fun position(p: TripPoint) = "lat=\"%.6f\" lon=\"%.6f\"".format(Locale.US, p.latitude, p.longitude)

    private fun number(value: Double, decimals: Int) = "%.${decimals}f".format(Locale.US, value)

    private fun iso(timeMs: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timeMs))

    private fun xml(text: String) = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
