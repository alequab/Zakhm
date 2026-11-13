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

    /** The style a line's placemark uses. */
    private fun styleOf(line: org.w3c.dom.Element) =
        (line.parentNode as org.w3c.dom.Element).getElementsByTagName("styleUrl").item(0)?.textContent.orEmpty()

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
        // The path itself; the speed wall and the G-force layer repeat it in their own styles.
        val path = (0 until doc.getElementsByTagName("LineString").length)
            .map { doc.getElementsByTagName("LineString").item(it) as org.w3c.dom.Element }
            .filter { line -> styleOf(line).startsWith("#speed") }
        val lines = path.size
        assertEquals("one stretch per colour", 8, lines)
        // Every stretch starts where the one before ended, so the path has no holes.
        val stretches = path.map { it.getElementsByTagName("coordinates").item(0).textContent.trim().split(' ') }
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
    fun kmlCarriesATimelineAndATourForGoogleEarth() {
        val points = drive(300, g = { if (it == 100) -0.5 to 0.0 else 0.0 to 0.0 })
        val doc = parse(TripExport.kml(points, text.copy(facts = listOf("Distance" to "6.0 km"))))
        val gx = "http://www.google.com/kml/ext/2.2"
        // Earth needs a time and a place for every point, and each graph as long as the track.
        assertEquals(300, doc.getElementsByTagName("when").length - doc.getElementsByTagName("TimeStamp").length)
        assertEquals(300, doc.getElementsByTagNameNS(gx, "coord").length)
        assertEquals("46.000000 24.000000 0", doc.getElementsByTagNameNS(gx, "coord").item(0).textContent)
        assertEquals(4, doc.getElementsByTagNameNS(gx, "SimpleArrayData").length)
        assertEquals(1200, doc.getElementsByTagNameNS(gx, "value").length)
        assertEquals("72.0", doc.getElementsByTagNameNS(gx, "value").item(0).textContent)
        // The tour: a bounded number of steps, heading north like the drive, ending on the overview.
        val flights = doc.getElementsByTagNameNS(gx, "FlyTo").length
        assertTrue("flights: $flights", flights in 20..(92))
        val firstStep = doc.getElementsByTagNameNS(gx, "FlyTo").item(1) as org.w3c.dom.Element
        assertEquals("0.0", firstStep.getElementsByTagName("heading").item(0).textContent)
        assertEquals("smooth", doc.getElementsByTagNameNS(gx, "flyToMode").item(1).textContent)
        assertEquals("bounce", doc.getElementsByTagNameNS(gx, "flyToMode").item(flights - 1).textContent)
        // Hard pushes and the 5 km posts carry a time, so they appear as the car reaches them.
        assertEquals(6, doc.getElementsByTagName("TimeStamp").length)
        assertEquals("Distance: 6.0 km", doc.getElementsByTagName("description").item(0).textContent)
        assertTrue(doc.getElementsByTagName("description").item(1).textContent.startsWith("<table"))
        assertEquals(4, doc.getElementsByTagName("StyleMap").length)
    }

    @Test
    fun theTourOpensEachHardPushCardAndClosesItAtTheNextStep() {
        val points = drive(300, g = { if (it == 100) -0.5 to 0.0 else if (it == 200) 0.0 to 0.6 else 0.0 to 0.0 })
        val doc = parse(TripExport.kml(points, text))
        val gx = "http://www.google.com/kml/ext/2.2"
        val shown = doc.getElementsByTagNameNS(gx, "balloonVisibility")
        assertEquals(4, shown.length)
        assertEquals(listOf("1", "0", "1", "0"), (0 until 4).map { shown.item(it).textContent })
        // Each card the tour opens belongs to a marker with that id.
        val targets = (0 until 4).map { (shown.item(it).parentNode as org.w3c.dom.Element).getAttribute("targetId") }
        assertEquals(listOf("event0", "event0", "event1", "event1"), targets)
        val ids = (0 until doc.getElementsByTagName("Placemark").length)
            .map { (doc.getElementsByTagName("Placemark").item(it) as org.w3c.dom.Element).getAttribute("id") }
        assertTrue(ids.containsAll(listOf("event0", "event1")))
        // The braking marker's values are data too.
        val data = doc.getElementsByTagName("Data")
        val keys = (0 until data.length).map { (data.item(it) as org.w3c.dom.Element).getAttribute("name") }
        assertTrue(keys.containsAll(listOf("g_force", "speed", "time")))
    }

    @Test
    fun theSpeedWallStandsAsTallAsTheSpeedAndStartsHidden() {
        val doc = parse(TripExport.kml(drive(60, speedMps = 20.0), text))
        val walls = (0 until doc.getElementsByTagName("LineString").length)
            .map { doc.getElementsByTagName("LineString").item(it) as org.w3c.dom.Element }
            .filter { it.getElementsByTagName("extrude").length == 1 }
        assertEquals(1, walls.size)
        assertEquals("relativeToGround", walls[0].getElementsByTagName("altitudeMode").item(0).textContent)
        // 20 m/s stands 100 m tall.
        assertEquals("46.000000,24.000000,100", walls[0].getElementsByTagName("coordinates").item(0).textContent.trim().split(' ')[0])
        val folder = walls[0].parentNode.parentNode as org.w3c.dom.Element
        assertEquals("Speed wall (3D)", folder.getElementsByTagName("name").item(0).textContent)
        assertEquals("0", folder.getElementsByTagName("visibility").item(0).textContent)
    }

    @Test
    fun aDriveOfOnePointHasNoTimelineOrTour() {
        val kml = TripExport.kml(drive(1), text)
        parse(kml)
        assertFalse(kml.contains("gx:Track"))
        assertFalse(kml.contains("gx:Tour"))
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

    /**
     * A made-up lap, saved to `build/drives` to open in a map program and look at:
     * two straights and two bends, with a hard launch, hard braking and hard cornering.
     */
    @Test
    fun sampleFilesToLookAt() {
        val points = (0 until 240).map { i ->
            val lap = i / 240.0 * 2 * Math.PI
            val onBend = Math.abs(Math.cos(lap)) < 0.5
            val speed = if (onBend) 14.0 else 14.0 + 22.0 * (Math.abs(Math.cos(lap)) - 0.5) * 2
            val forward = when (i % 120) {
                in 42..46 -> 0.34
                in 14..18 -> -0.55
                else -> 0.03
            }
            TripPoint(
                timeMs = 1_700_000_000_000 + i * 1000L,
                latitude = 50.3350 + 0.004 * Math.sin(lap),
                longitude = 6.9470 + 0.012 * Math.cos(lap),
                speedMps = speed,
                forwardG = forward,
                sideG = if (onBend) 0.52 else 0.02,
            )
        }
        val dir = java.io.File("build/drives").apply { mkdirs() }
        java.io.File(dir, "sample.kml").writeText(TripExport.kml(points, text))
        java.io.File(dir, "sample.gpx").writeText(TripExport.gpx(points, text))
        assertEquals(4, Trips.events(points).count { it.kind == GEventKind.BRAKING || it.kind == GEventKind.SPEEDING_UP })
    }

    /** Points one second apart with the given speeds, heading north. */
    private fun speeds(vararg mps: Double, from: Long = 1_700_000_000_000): List<TripPoint> {
        var latitude = 24.0
        return mps.mapIndexed { i, v ->
            if (i > 0) latitude += (mps[i - 1] + v) / 2 / 111_195.0
            TripPoint(from + i * 1000L, latitude, 46.0, v, 0.0, 0.0)
        }
    }

    private fun folder(doc: org.w3c.dom.Document, name: String): org.w3c.dom.Element? =
        (0 until doc.getElementsByTagName("Folder").length)
            .map { doc.getElementsByTagName("Folder").item(it) as org.w3c.dom.Element }
            .firstOrNull { it.getElementsByTagName("name").item(0).textContent == name }

    private fun placemarkNames(folder: org.w3c.dom.Element?): List<String> {
        val marks = folder?.getElementsByTagName("Placemark") ?: return emptyList()
        return (0 until marks.length).mapNotNull { (marks.item(it) as org.w3c.dom.Element).getElementsByTagName("name").item(0)?.textContent }
    }

    @Test
    fun theNewColumnsReadBackAndOldFilesStillOpen() {
        val point = TripPoint(1_700_000_000_000, 24.5, 46.5, 10.0, 0.1, -0.2, altitudeM = 612.4, accuracyM = 4.5, speedFrom = SpeedInput.OBD)
        assertEquals(listOf(point), TripFile.parse(sequenceOf(TripFile.HEADER, TripFile.line(point))))
        // A drive saved by 1.6.1 or earlier.
        val old = TripFile.parse(sequenceOf("time_ms,lat,lon,speed_mps,forward_g,side_g", "5,24.0,46.0,1.00,0.000,0.000"))
        assertEquals(listOf(TripPoint(5, 24.0, 46.0, 1.0, 0.0, 0.0)), old)
        assertNull(Trips.summary(old)!!.climbM)
        assertNull(Trips.summary(old)!!.carSpeedShare)
    }

    @Test
    fun stopsInTheMiddleAreFoundAndWaitingAtTheEndsIsNot() {
        val still = DoubleArray(40) { 0.0 }
        val moving = DoubleArray(30) { 15.0 }
        val points = speeds(*(DoubleArray(10) { 0.0 } + moving + still + moving + DoubleArray(25) { 0.0 }))
        val stops = Trips.stops(points)
        assertEquals(1, stops.size)
        assertEquals(39, stops[0].seconds)
        assertEquals(points[40], stops[0].from)
        // Short waits are no stop at all.
        assertTrue(Trips.stops(speeds(*(moving + DoubleArray(10) { 0.0 } + moving))).isEmpty())
        // Every still second counts against moving time.
        val summary = Trips.summary(points)!!
        assertEquals(summary.durationS - 9 - 39 - 24, summary.movingS)
    }

    @Test
    fun aLaunchIsTimedToEachTargetBetweenPoints() {
        // 3 m/s every second from standstill: 60 km/h at 5.56 s, 80 at 7.41 s, 100 at 9.26 s.
        val points = speeds(*(DoubleArray(5) { 0.0 } + DoubleArray(12) { (it + 1) * 3.0 } + DoubleArray(20) { 36.0 }))
        val launches = Trips.launches(points, listOf(60.0, 80.0, 100.0).map { it / 3.6 })
        assertEquals(1, launches.size)
        assertEquals(points[4], launches[0].start)
        assertEquals(listOf(5.56, 7.41, 9.26), launches[0].reached.map { Math.round(it.second * 100) / 100.0 })
        // Easing off before 60 is no launch.
        val gentle = speeds(*(DoubleArray(3) { 0.0 } + doubleArrayOf(3.0, 6.0, 9.0, 12.0, 8.0, 8.0)))
        assertTrue(Trips.launches(gentle, listOf(60.0 / 3.6)).isEmpty())
    }

    @Test
    fun aHardBrakingKnowsItsSpeedsAndDistance() {
        // 25 m/s down to standstill at 5 m/s² takes 62.5 m.
        val speedsList = DoubleArray(10) { 25.0 } + DoubleArray(6) { 25.0 - it * 5.0 } + DoubleArray(5) { 0.0 }
        val points = speeds(*speedsList).mapIndexed { i, p -> if (i in 10..15 && i > 10) p.copy(forwardG = if (i == 12) -0.51 else -0.45) else p }
        val brakings = Trips.brakings(points)
        assertEquals(1, brakings.size)
        assertEquals(25.0, brakings[0].from.speedMps!!, 1e-9)
        assertEquals(0.0, brakings[0].to.speedMps!!, 1e-9)
        assertEquals(62.5, brakings[0].distanceM, 0.01)
        val kml = TripExport.kml(points, text)
        assertTrue(kml.contains("<Data name=\"speed_before\"><displayName>Speed before</displayName><value>90 km/h</value></Data>"))
        assertTrue(kml.contains("<Data name=\"speed_after\"><displayName>Speed after</displayName><value>0 km/h</value></Data>"))
        assertTrue(kml.contains("<Data name=\"braking_distance\"><displayName>Braking distance</displayName><value>63 m</value></Data>"))
    }

    @Test
    fun distancePostsStandAtEachWholeUnit() {
        val posts = Trips.distancePosts(drive(301), 1000.0)
        assertEquals(listOf(1000.0, 2000.0, 3000.0, 4000.0, 5000.0, 6000.0), posts.map { it.distanceM })
        // 20 m/s: the first kilometre is passed at 50 s.
        assertEquals(1_700_000_050_000, posts[0].point.timeMs)
        val doc = parse(TripExport.kml(drive(301), text))
        assertEquals(listOf("1 km", "2 km", "3 km", "4 km", "5 km", "6 km"), placemarkNames(folder(doc, "Distance posts")))
        assertTrue(TripExport.kml(drive(301), text).contains("<value>72 km/h</value>"))
    }

    @Test
    fun summaryAddsTotalGClimbAndCarShare() {
        val points = drive(10, g = { if (it == 4) 0.3 to 0.4 else 0.0 to 0.0 }).mapIndexed { i, p ->
            p.copy(
                // Wobbling by a metre or two isn't a climb; the 20 m rise is.
                altitudeM = if (i < 5) 600.0 + (i % 2) * 2 else 620.0,
                speedFrom = if (i < 8) SpeedInput.OBD else SpeedInput.PHONE,
            )
        }
        val summary = Trips.summary(points)!!
        assertEquals(0.5, summary.strongestCombinedG, 1e-9)
        assertEquals(0.4, summary.strongestG, 1e-9)
        assertEquals(20.0, summary.climbM!!, 1e-9)
        assertEquals(0.8, summary.carSpeedShare!!, 1e-9)
    }

    @Test
    fun theKmlHasEveryNewLayer() {
        // Off, a launch, a stop, GPS lost for 40 s with a weak stretch after, and on again.
        val launch = speeds(*(DoubleArray(3) { 0.0 } + DoubleArray(10) { (it + 1) * 3.0 } + DoubleArray(60) { 30.0 }))
        val stop = speeds(*(DoubleArray(30) { 0.0 } + DoubleArray(30) { 20.0 }), from = launch.last().timeMs + 1000)
            // Carries on from where the launch ended.
            .map { it.copy(latitude = it.latitude - 24.0 + launch.last().latitude) }
        val after = speeds(*DoubleArray(60) { 20.0 }, from = stop.last().timeMs + 40_000).mapIndexed { i, p ->
            p.copy(latitude = p.latitude + 0.05, accuracyM = if (i < 10) 25.0 else 4.0, altitudeM = 600.0, speedFrom = SpeedInput.OBD)
        }
        val points = launch + stop + after
        val kml = TripExport.kml(points, text, legendSize = 210 to 340)
        val doc = parse(kml)

        // Named by the fastest target reached; pulling away after the stop only passes 60.
        assertEquals(listOf("0–100 km/h 9.3 s", "0–60 km/h 0.8 s"), placemarkNames(folder(doc, "Launches")))
        assertTrue(kml.contains("<displayName>0–60 km/h</displayName><value>5.6 s</value>"))
        assertEquals(listOf("Stopped 29 s"), placemarkNames(folder(doc, "Stops")))
        // A marker and a thin line across the gap.
        assertEquals(listOf("GPS lost 40 s", "GPS lost 40 s"), placemarkNames(folder(doc, "GPS signal lost")))
        assertEquals(1, folder(doc, "Weak GPS")!!.getElementsByTagName("LineString").length)
        assertTrue(kml.contains("<td><b>GPS accuracy</b></td><td>± 25 m</td>"))
        // The car heads north, so do the arrows.
        val arrows = folder(doc, "Direction")!!.getElementsByTagName("heading")
        assertTrue(arrows.length > 2)
        assertTrue((0 until arrows.length).map { arrows.item(it).textContent }.toString(), (0 until arrows.length).all { arrows.item(it).textContent == "0" })
        // The G-force path starts hidden; the legend sits in the corner.
        assertEquals("0", folder(doc, "Path by G-force")!!.getElementsByTagName("visibility").item(0).textContent)
        assertEquals("files/legend.png", (doc.getElementsByTagName("ScreenOverlay").item(0) as org.w3c.dom.Element).getElementsByTagName("href").item(0).textContent)
        // Markers after the gap know their height and where the speed came from.
        assertTrue(kml.contains("<Data name=\"height\"><displayName>Height</displayName><value>600 m</value></Data>"))
        assertTrue(kml.contains("<Data name=\"speed_from\"><displayName>Speed from</displayName><value>OBD</value></Data>"))
        assertTrue(kml.contains("<gx:coord>46.000000 24."))
        assertFalse(TripExport.kml(points, text).contains("ScreenOverlay"))
        // Every icon is packed in the file, not fetched from the internet.
        assertFalse(kml.contains("http://maps.google.com"))
    }

    @Test
    fun aKmzHoldsTheKmlFirstThenItsPictures() {
        val bytes = java.io.ByteArrayOutputStream()
        // Only a PNG's header is read: 200 by 300 pixels.
        val header = ByteArray(24).also { it[19] = 200.toByte(); it[22] = 1; it[23] = 44 }
        val pictures = mapOf(TripExport.Picture.CIRCLE to byteArrayOf(1, 2, 3), TripExport.Picture.LEGEND to header)
        TripExport.kmz(drive(30), text, pictures, bytes)
        val names = mutableListOf<String>()
        java.util.zip.ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                // The legend keeps its shape at its smaller size.
                if (entry.name == "doc.kml") assertTrue(String(zip.readBytes()).contains("<size x=\"130\" y=\"195\""))
            }
        }
        assertEquals(listOf("doc.kml", "files/circle.png", "files/legend.png"), names)
    }

    @Test
    fun gpxCarriesHeightAccuracyAndSource() {
        val points = drive(3).map { it.copy(altitudeM = 612.4, accuracyM = 3.5, speedFrom = SpeedInput.ANDROID_AUTO) }
        val gpx = TripExport.gpx(points, text)
        parse(gpx)
        assertTrue(gpx.contains("<trkpt lat=\"24.000000\" lon=\"46.000000\"><ele>612.4</ele><time>"))
        assertTrue(gpx.contains("<zakhm:accuracy>3.5</zakhm:accuracy><zakhm:speedFrom>android_auto</zakhm:speedFrom>"))
    }

    @Test
    fun anEmptyDriveStillMakesValidFiles() {
        parse(TripExport.kml(emptyList(), text))
        parse(TripExport.gpx(emptyList(), text))
        assertFalse(TripExport.gpx(emptyList(), text).contains("<wpt"))
    }
}
