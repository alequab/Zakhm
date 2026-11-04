package app.zakhm.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Replays a real recorded drive and prints what the engine made of it:
 *
 *     gradlew :app:testDebugUnitTest --tests "*ReplayFileTest*" -PreplayFile=C:\path\drive.csv.gz -i
 */
class ReplayFileTest {
    @Test
    fun replayRecordedDrive() {
        val path = System.getProperty("replayFile").orEmpty()
        assumeTrue("no replayFile given", path.isNotEmpty())

        val engine = TelemetryEngine()
        var nextPrint = Double.NEGATIVE_INFINITY
        println("time_s,speed_kmh,source,long_g,lat_g,calibration")
        GZIPInputStream(File(path).inputStream()).bufferedReader().use { reader ->
            DriveLog.replay(reader, engine) { time ->
                if (time >= nextPrint) {
                    nextPrint = time + 0.5
                    val s = engine.snapshot()
                    println(
                        "%.2f,%s,%s,%s,%s,%s".format(
                            time,
                            s.speedMps?.let { "%.1f".format(it * 3.6) } ?: "",
                            s.speedSource,
                            s.longitudinalG?.let { "%.3f".format(it) } ?: "",
                            s.lateralG?.let { "%.3f".format(it) } ?: "",
                            s.calibration,
                        ),
                    )
                }
            }
        }
        println("peaks: ${engine.snapshot().peaks}")
    }
}
