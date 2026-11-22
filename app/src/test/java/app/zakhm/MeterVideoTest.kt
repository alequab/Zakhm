package app.zakhm

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zakhm.core.CalibrationState
import app.zakhm.core.CarProfile
import app.zakhm.core.Peaks
import app.zakhm.core.SpeedInput
import app.zakhm.core.SpeedSource
import app.zakhm.core.TelemetrySnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.hypot

/**
 * Plays the pushes of a real F1 onboard video through each G-force style, full screen in landscape at the
 * owner's phone size, and saves it as an animated GIF in `build/screens` to watch.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h392dp-land-mdpi")
class MeterVideoTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * The pushes seen in a real F1 onboard video, second by second, scaled from its 5 G to a
     * road car's 1 G: hard braking, left and right turns, a braking turn into each front
     * corner, and quiet moments. Shown felt by the driver, as in the video (and the owner's
     * setting): braking lights the top. (seconds, longitudinal G with + speeding up, and the
     * side that lights with + right)
     */
    private val keyframes = listOf(
        Triple(0.0, 0.0, 0.0),
        Triple(1.0, -1.0, 0.0), Triple(3.0, -1.0, 0.0),
        Triple(4.0, 0.0, 0.44), Triple(5.0, 0.0, 0.36), Triple(6.0, 0.0, 0.14),
        Triple(6.5, 0.0, -0.14), Triple(7.0, 0.0, -0.46),
        Triple(7.6, -0.57, -0.57),
        Triple(8.2, 0.0, -0.84), Triple(9.5, 0.0, -0.84),
        Triple(10.0, 0.0, -0.06),
        Triple(10.6, 0.0, -0.7), Triple(11.2, 0.0, -0.94), Triple(12.5, 0.0, -0.94),
        Triple(13.0, 0.0, 0.46), Triple(13.6, 0.0, 0.18), Triple(14.2, 0.0, 0.0),
        Triple(15.0, 0.0, 0.2),
        Triple(15.8, -0.62, 0.62), Triple(17.5, -0.62, 0.62),
        Triple(18.2, 0.0, 0.44), Triple(19.0, 0.0, 0.38), Triple(19.6, 0.0, 0.0),
    )

    private fun drive(t: Double): Pair<Double, Double> {
        val next = keyframes.indexOfFirst { it.first >= t }.takeIf { it > 0 } ?: return keyframes.last().let { it.second to it.third }
        val (t0, l0, s0) = keyframes[next - 1]
        val (t1, l1, s1) = keyframes[next]
        val k = (t - t0) / (t1 - t0)
        return (l0 + (l1 - l0) * k) to (s0 + (s1 - s0) * k)
    }

    @Test
    fun recordEachStyle() {
        var snapshot by mutableStateOf(TelemetrySnapshot())
        var style by mutableStateOf(GStyle.WINDOW)
        compose.setContent {
            TelemetryScreen(
                TelemetryUiState(
                    snapshot = snapshot,
                    selectedInput = SpeedInput.OBD,
                    activeInput = SpeedInput.OBD,
                    obdState = ObdState.CONNECTED,
                    cars = listOf(CarProfile("a", "Picanto", input = SpeedInput.OBD)),
                    activeCarId = "a",
                ),
                UpdateState.None,
                true,
                ScreenSettings(
                    useMph = false,
                    rotation = RotationMode.AUTO,
                    obdDevices = emptyList(),
                    scanningForAdapters = false,
                    needsBluetoothPermission = false,
                    language = AppLanguage.ENGLISH,
                    gStyle = style,
                    gView = GView.DRIVER,
                    gRange = 1.0,
                ),
                ScreenActions(
                    requestPermission = {}, calibrate = {}, resetPeaks = {}, setUseMph = {}, setRotation = {},
                    setLanguage = {}, setTheme = { _, _ -> }, selectInput = {}, selectObdDevice = {},
                    requestBluetooth = {}, scanForAdapters = {}, toggleObdConnection = {}, selectCar = {},
                    addCar = {}, renameCar = { _, _ -> }, deleteCar = {}, saveUnknownCarToCurrent = {},
                    saveUnknownCarAsNew = {}, skipUnknownCar = {}, toggleRecording = {}, shareRecording = {},
                    startUpdate = {}, dismissUpdate = {},
                ),
                startFocus = Focus.G,
            )
        }
        // The clock moves one frame at a time, so timed effects (the red flash) last as long as on a phone.
        compose.mainClock.autoAdvance = false
        for (s in listOf(GStyle.WINDOW, GStyle.RING, GStyle.CIRCLE)) {
            style = s
            // Each frame goes straight into the file, so a long recording needn't fit in memory.
            val frames = sequence {
            var t = 0.0
            while (t < SECONDS) {
                val (long, lat) = drive(t)
                snapshot = TelemetrySnapshot(
                    speedMps = 20.0,
                    speedSource = SpeedSource.LIVE,
                    calibration = CalibrationState.READY,
                    learningProgress = 1.0,
                    longitudinalG = long,
                    lateralG = -lat,
                    totalG = hypot(long, lat),
                    peaks = Peaks(accel = 0.35, brake = 0.85, left = 0.55, right = 0.95),
                )
                compose.mainClock.advanceTimeBy(1000L / FPS)
                yield(capture())
                t += 1.0 / FPS
            }
            }
            writeGif(frames, File("build/screens/meter-${s.name.lowercase()}.gif"))
        }
    }

    private fun capture(): BufferedImage {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_RGB).apply {
            setRGB(0, 0, bitmap.width, bitmap.height, pixels, 0, bitmap.width)
        }
    }

    /** An endlessly looping GIF, one frame every 1/[FPS] s. */
    private fun writeGif(frames: Sequence<BufferedImage>, file: File) {
        file.parentFile.mkdirs()
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        ImageIO.createImageOutputStream(file).use { out ->
            writer.output = out
            writer.prepareWriteSequence(null)
            val delay = (100 / FPS).toString()
            frames.forEach { frame ->
                val type = ImageTypeSpecifier.createFromRenderedImage(frame)
                val meta = writer.getDefaultImageMetadata(type, writer.defaultWriteParam)
                val format = meta.nativeMetadataFormatName
                val root = meta.getAsTree(format) as IIOMetadataNode
                fun child(name: String): IIOMetadataNode {
                    for (i in 0 until root.length) if (root.item(i).nodeName == name) return root.item(i) as IIOMetadataNode
                    return IIOMetadataNode(name).also { root.appendChild(it) }
                }
                child("GraphicControlExtension").apply {
                    setAttribute("disposalMethod", "none")
                    setAttribute("userInputFlag", "FALSE")
                    setAttribute("transparentColorFlag", "FALSE")
                    setAttribute("delayTime", delay)
                    setAttribute("transparentColorIndex", "0")
                }
                child("ApplicationExtensions").appendChild(
                    IIOMetadataNode("ApplicationExtension").apply {
                        setAttribute("applicationID", "NETSCAPE")
                        setAttribute("authenticationCode", "2.0")
                        userObject = byteArrayOf(1, 0, 0)
                    },
                )
                meta.setFromTree(format, root)
                writer.writeToSequence(IIOImage(frame, null, meta), writer.defaultWriteParam)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
    }

    private companion object {
        const val FPS = 12
        const val SECONDS = 20.0
    }
}
