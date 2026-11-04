package app.speedg.core

import java.io.BufferedReader

/**
 * Text format of recorded drives. One event per line, oldest first:
 *
 *     A,<nanos>,<x>,<y>,<z>                accelerometer, m/s^2
 *     G,<nanos>,<x>,<y>,<z>                gyroscope, rad/s
 *     L,<nanos>,<speed>,<speedAcc>,<hAcc>  GPS fix, blank when not reported
 *
 * Fixes are written in the order they arrived, so replaying reproduces the same
 * latency the phone saw. No position is recorded, so a log can't reveal where a
 * drive took place.
 */
object DriveLog {
    const val HEADER = "# SpeedG drive log v1"

    fun accel(nanos: Long, x: Float, y: Float, z: Float) = "A,$nanos,$x,$y,$z"

    fun gyro(nanos: Long, x: Float, y: Float, z: Float) = "G,$nanos,$x,$y,$z"

    fun gps(nanos: Long, speed: Float?, speedAccuracy: Float?, horizontalAccuracy: Float?) =
        "L,$nanos,${speed ?: ""},${speedAccuracy ?: ""},${horizontalAccuracy ?: ""}"

    /** Feeds a recorded drive to [engine], calling [afterEvent] with each event's time in seconds. */
    fun replay(reader: BufferedReader, engine: TelemetryEngine, afterEvent: (Double) -> Unit = {}) {
        reader.forEachLine { line ->
            if (line.isEmpty() || line[0] == '#') return@forEachLine
            val p = line.split(',')
            val time = p[1].toLong() / 1e9
            when (p[0]) {
                "A" -> engine.onAccel(time, vector(p))
                "G" -> engine.onGyro(time, vector(p))
                "L" -> engine.onGps(
                    GpsFix(
                        time = time,
                        speed = p[2].toDoubleOrNull(),
                        speedAccuracy = p[3].toDoubleOrNull(),
                        horizontalAccuracy = p[4].toDoubleOrNull(),
                    ),
                )
                else -> return@forEachLine
            }
            afterEvent(time)
        }
    }

    private fun vector(p: List<String>) = Vec3(p[2].toDouble(), p[3].toDouble(), p[4].toDouble())
}
