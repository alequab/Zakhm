package app.zakhm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

class TripTest {
    private val text = TripText(
        name = "Zakhm <test> & more",
        description = "12 min",
        speedFolder = "Speed",
        gFolder = "G-force",
        start = "Start",
        end = "End",
        topSpeed = "Top speed",
        speedingUp = "Speeding up",
        braking = "Braking",
        leftTurn = "Left turn",
        rightTurn = "Right turn",
        speedUnit = "km/h",
        speedFactor = 3.6,
        zone = ZoneOffset.UTC,
    )

    /** Heading north at a steady speed, one point a second. */
    private fun drive(seconds: Int, speedMps: Double = 20.0, g: (Int) -> Pair<Double?, Double?> = { 0.0 to 0.0 }) =
        (0 until seconds).map { i ->
            val (forward, side) = g(i)
            TripPoint(1_700_000_000_000 + i * 1000L, 24.0 + i * speedMps / 111_195.0, 46.0, speedMps, forward, side)
        }

    private fun parse(xml: String) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(InputSource(StringReader(xml)))

    @Test
    fun aPointKeepsTheStrongestPushSinceTheOneBefore() {
        val recorder = TripRecorder()
        recorder.onG(0.1, -0.05)
        recorder.onG(-0.45, 0.2)
        recorder.onG(0.2, null)
        recorder.onG(Double.NaN, 0.1)
        val point = recorder.onPosition(5, 24.0, 46.0, 10.0)
        assertEquals(-0.45, point.forwardG!!, 1e-9)
        assertEquals(0.2, point.sideG!!, 1e-9)
        // Nothing carries over into the next point.
        val next = recorder.onPosition(6, 24.0, 46.0, 10.0)
        assertNull(next.forwardG)
        assertNull(next.sideG)
    }

    @Test
    fun aSavedDriveReadsBackTheSame() {
        val points = listOf(
            TripPoint(1_700_000_000_000, 24.713552, 46.675296, 13.89, 0.123, -0.456),
            TripPoint(1_700_000_001_000, -33.868820, 151.209296, null, null, null),
        )
        val lines = listOf(TripFile.HEADER) + points.map(TripFile::line)
        assertEquals(points, TripFile.parse(lines.asSequence()))
    }

    @Test
    fun brokenLinesAreSkipped() {
        val good = TripFile.line(TripPoint(1, 24.0, 46.0, 1.0, 0.0, 0.0))
        val lines = sequenceOf(TripFile.HEADER, good, "12,24.0", "x,24,46,1,0,0", "5,124.0,46.0,1,0,0", "")
        assertEquals(1, TripFile.parse(lines).size)
    }

    @Test
    fun summaryMeasuresTheDrive() {
        val summary = Trips.summary(drive(61, g = { if (it == 30) -0.52 to 0.1 else 0.05 to 0.0 }))!!
        assertEquals(60, summary.durationS)
        assertEquals(1200.0, summary.distanceM, 1.0)
        assertEquals(20.0, summary.topSpeedMps, 1e-9)
        assertEquals(0.52, summary.strongestG, 1e-9)
        assertNull(Trips.summary(emptyList()))
    }

    @Test
    fun standingStillAddsNoDistanceHoweverThePositionWanders() {
        val parked = (0 until 60).map { TripPoint(it * 1000L, 24.0 + (it % 2) * 0.00005, 46.0, 0.0, 0.0, 0.0) }
        assertEquals(0.0, Trips.summary(parked)!!.distanceM, 1e-9)
    }

    @Test
    fun distanceAcrossAGapComesFromThePositions() {
        val a = TripPoint(0, 24.0, 46.0, 20.0, 0.0, 0.0)
        val b = TripPoint(60_000, 24.01, 46.0, 20.0, 0.0, 0.0)
        assertEquals(1112.0, Trips.summary(listOf(a, b))!!.distanceM, 2.0)
    }

    @Test
    fun oneHardBrakeIsOneEventAtItsPeak() {
        val points = drive(40, g = {
            when (it) {
                10 -> -0.40 to 0.0
                11 -> -0.61 to 0.0
                // Easing off for a moment doesn't make it two.
                12 -> -0.20 to 0.0
                13 -> -0.45 to 0.0
                else -> 0.02 to 0.0
            }
        })
        val events = Trips.events(points)
        assertEquals(1, events.size)
        assertEquals(GEventKind.BRAKING, events[0].kind)
        assertEquals(0.61, events[0].g, 1e-9)
        assertEquals(points[11], events[0].point)
    }

    @Test
    fun eventsAreSortedAndNamedByDirection() {
        val points = drive(60, g = {
            when (it) {
                5 -> 0.30 to 0.0
                20 -> 0.0 to 0.55
                35 -> 0.0 to -0.48
                50 -> -0.50 to 0.0
                else -> 0.1 to 0.1
            }
        })
        assertEquals(
            listOf(GEventKind.SPEEDING_UP, GEventKind.LEFT_TURN, GEventKind.RIGHT_TURN, GEventKind.BRAKING),
            Trips.events(points).map { it.kind },
        )
    }

    @Test
    fun gentleDrivingMarksNothing() {
        assertTrue(Trips.events(drive(120, g = { 0.15 to -0.2 })).isEmpty())
    }

    @Test
    fun aLongSilenceSplitsThePath() {
        val points = drive(10) + drive(10).map { it.copy(timeMs = it.timeMs + 60_000) }
        assertEquals(listOf(10, 10), Trips.segments(points).map { it.size })
    }

    @Test
    fun kmlIsWellFormedAndColoursThePathBySpeed() {
        val points = (0 until 80).map { i ->
            TripPoint(1_700_000_000_000 + i * 1000L, 24.0 + i * 0.0002, 46.0, i * 0.4, if (i == 40) -0.5 else 0.0, 0.0)
        }
        val kml = TripExport.kml(points, text)
        val doc = parse(kml)
        val lines = doc.getElementsByTagName("LineString").length
        assertEquals("one stretch per colour", 8, lines)
        // Every stretch starts where the one before ended, so the path has no holes.
        val stretches = (0 until lines).map { doc.getElementsByTagName("coordinates").item(it).textContent.trim().split(' ') }
        stretches.zipWithNext().forEach { (a, b) -> assertEquals(a.last(), b.first()) }
        assertEquals(80, stretches.sumOf { it.size } - (lines - 1))
        assertTrue(kml.contains("Braking 0.50 G"))
        assertTrue(kml.contains("Zakhm &lt;test&gt; &amp; more"))
        // Longitude first: the other way round puts the drive in the wrong country.
        assertTrue(kml.contains("46.000000,24.000000,0"))
        // KML colours run blue, green, red.
        assertTrue(kml.contains("<color>ffFF6C2B</color>"))
    }

    @Test
    fun gpxIsWellFormedAndCarriesSpeedAndG() {
        val points = drive(30, g = { if (it == 12) 0.0 to 0.6 else 0.0 to 0.0 })
        val gpx = TripExport.gpx(points, text)
        val doc = parse(gpx)
        assertEquals(30, doc.getElementsByTagName("trkpt").length)
        assertEquals(1, doc.getElementsByTagName("trkseg").length)
        assertEquals(30, doc.getElementsByTagNameNS("http://www.garmin.com/xmlschemas/TrackPointExtension/v2", "speed").length)
        assertEquals("20.00", doc.getElementsByTagNameNS("http://www.garmin.com/xmlschemas/TrackPointExtension/v2", "speed").item(0).textContent)
        assertTrue(gpx.contains("<time>2023-11-14T22:13:20Z</time>"))
        assertTrue(gpx.contains("<name>Left turn 0.60 G</name>"))
        assertTrue(gpx.contains("<desc>72 km/h · 22:13:32</desc>"))
        // Waypoints must come before the track for the file to be valid GPX.
        assertTrue(gpx.indexOf("<wpt") < gpx.indexOf("<trk>"))
    }

    @Test
    fun anEmptyDriveStillMakesValidFiles() {
        parse(TripExport.kml(emptyList(), text))
        parse(TripExport.gpx(emptyList(), text))
        assertFalse(TripExport.gpx(emptyList(), text).contains("<wpt"))
    }
}
