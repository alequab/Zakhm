package app.zakhm

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zakhm.core.SpeedInput
import app.zakhm.core.TripExport
import app.zakhm.core.TripPoint
import app.zakhm.core.Trips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the KMZ pictures for real and packs sample drives into `build/drives`, in English
 * and Arabic, to open in Google Earth and look at.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TripPicturesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * Two laps of a made-up track with every kind of marker: a launch from standstill,
     * hard braking and cornering, a stop, GPS lost for a while, a weak stretch, heights and OBD speed.
     */
    private fun sampleDrive(): List<TripPoint> {
        val out = mutableListOf<TripPoint>()
        var time = 1_700_000_000_000L
        var angle = 0.0
        fun add(speed: Double, forward: Double, side: Double, accuracy: Double = 4.0, from: SpeedInput = SpeedInput.OBD) {
            // About 3.4 km a lap.
            angle += speed / 540.0
            out += TripPoint(
                time, 50.3350 + 0.004 * sin(angle), 6.9470 + 0.012 * cos(angle), speed, forward, side,
                altitudeM = 600.0 + 25.0 * sin(angle), accuracyM = accuracy, speedFrom = from,
            )
            time += 1000
        }
        repeat(5) { add(0.0, 0.0, 0.0) }
        for (i in 1..10) add(i * 2.9, 0.31, 0.0)
        repeat(240) { step ->
            val onBend = abs(cos(angle)) < 0.5
            val speed = if (onBend) 14.0 else 29.0
            val braking = !onBend && abs(cos(angle + 0.06)) < 0.5
            add(speed, if (braking) -0.55 else 0.03, if (onBend) 0.48 else 0.02, accuracy = if (step in 120..135) 22.0 else 4.0)
        }
        for (v in listOf(20.0, 12.0, 5.0, 0.0)) add(v, -0.4, 0.0)
        repeat(45) { add(0.0, 0.0, 0.0) }
        for (i in 1..8) add(i * 3.0, 0.28, 0.0, from = SpeedInput.PHONE)
        // GPS lost for 35 s.
        time += 35_000
        angle += 24.0 * 35 / 540.0
        repeat(150) { add(24.0, 0.02, if (abs(cos(angle)) < 0.5) 0.42 else 0.02, from = SpeedInput.PHONE) }
        for (v in listOf(16.0, 8.0, 0.0)) add(v, -0.3, 0.0)
        repeat(10) { add(0.0, 0.0, 0.0) }
        return out
    }

    private fun writeSample(name: String): File {
        val points = sampleDrive()
        val text = tripText(context, Trips.summary(points)!!, useMph = false)
        val legend = TripExport.legend(points, text)
        val pictures = TripPictures.all(legend, text.rightToLeft)
        val dir = File("build/drives").apply { mkdirs() }
        File(dir, "legend-$name.png").writeBytes(pictures.getValue(TripExport.Picture.LEGEND))
        File(dir, "icons-$name").apply { mkdirs() }.let { icons ->
            pictures.forEach { (picture, bytes) -> File(icons, picture.file).writeBytes(bytes) }
        }
        val file = File(dir, "sample-$name.kmz")
        file.outputStream().use { TripExport.kmz(points, text, pictures, it) }
        return file
    }

    @Test
    fun everyPictureIsARealImage() {
        val pictures = TripPictures.all(TripExport.legend(sampleDrive(), tripText(context, Trips.summary(sampleDrive())!!, false)), false)
        assertEquals(TripExport.Picture.entries.toSet(), pictures.keys)
        pictures.forEach { (picture, bytes) ->
            val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertTrue("${picture.file} is not a picture", image != null && image.width > 0)
            if (picture != TripExport.Picture.LEGEND) assertEquals(64, image.width)
        }
    }

    @Test
    fun englishSample() {
        val file = writeSample("en")
        ZipFile(file).use { zip ->
            assertEquals("doc.kml", zip.entries().nextElement().name)
            val kml = zip.getInputStream(zip.getEntry("doc.kml")).bufferedReader().readText()
            // Every picture the KML asks for is in the file.
            Regex("<href>(files/[a-z]+\\.png)</href>").findAll(kml).map { it.groupValues[1] }.toSet().forEach {
                assertTrue("missing $it", zip.getEntry(it) != null)
            }
            listOf("Launches", "Stops", "Distance posts", "Direction", "GPS signal lost", "Weak GPS", "Path by G-force", "Speed before").forEach {
                assertTrue("missing $it", kml.contains(it))
            }
        }
    }

    @Test
    @Config(qualifiers = "ar")
    fun arabicSample() {
        val file = writeSample("ar")
        ZipFile(file).use { zip ->
            val kml = zip.getInputStream(zip.getEntry("doc.kml")).bufferedReader().readText()
            assertTrue(kml.contains("التوقفات"))
            assertTrue(kml.contains("dir=\"rtl\""))
            // Each fact of the summary on its own line, so Earth can't mix their order.
            assertTrue(kml.contains(" G" + Char(0x202C) + "\n" + Char(0x202B)))
            // Names too, or Earth shows "قوة G" back to front.
            assertTrue(kml.contains("<name>" + Char(0x202B) + "قوة G" + Char(0x202C) + "</name>"))
        }
    }
}
