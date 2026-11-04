package app.speedg

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import app.speedg.core.DriveLog
import app.speedg.core.GpsFix
import app.speedg.core.TelemetryEngine
import app.speedg.core.TelemetrySnapshot
import app.speedg.core.Vec3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

data class TelemetryUiState(
    val snapshot: TelemetrySnapshot = TelemetrySnapshot(),
    val satellitesUsed: Int? = null,
    val gpsEnabled: Boolean = true,
    val recording: Boolean = false,
    val recordingSeconds: Long = 0,
    val lastRecording: File? = null,
    val recordingError: String? = null,
)

/**
 * Connects the phone's sensors to [TelemetryEngine]. Everything except the public
 * entry points runs on one background thread, so the engine needs no locking and
 * the UI thread can't delay a sensor reading.
 */
class TelemetryController(private val context: Context) {
    private val thread = HandlerThread("telemetry").apply { start() }
    private val handler = Handler(thread.looper)

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val locationManager = context.getSystemService(LocationManager::class.java)

    private val engine = TelemetryEngine()
    private val _state = MutableStateFlow(TelemetryUiState())
    val state: StateFlow<TelemetryUiState> = _state

    private var running = false
    private var satellitesUsed: Int? = null
    private var gpsEnabled = true

    private var recorder: BufferedWriter? = null
    private var recordingFile: File? = null
    private var recordingStart = 0L
    private var lastRecorderFlush = 0L
    private var lastRecording: File? = null
    private var recordingError: String? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    engine.onAccel(event.timestamp / 1e9, Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()))
                    record(DriveLog.accel(event.timestamp, v[0], v[1], v[2]))
                }
                Sensor.TYPE_GYROSCOPE -> {
                    engine.onGyro(event.timestamp / 1e9, Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()))
                    record(DriveLog.gyro(event.timestamp, v[0], v[1], v[2]))
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val speed = if (location.hasSpeed()) location.speed else null
            val speedAccuracy = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null
            val horizontalAccuracy = if (location.hasAccuracy()) location.accuracy else null
            engine.onGps(
                GpsFix(
                    time = location.elapsedRealtimeNanos / 1e9,
                    speed = speed?.toDouble(),
                    speedAccuracy = speedAccuracy?.toDouble(),
                    horizontalAccuracy = horizontalAccuracy?.toDouble(),
                ),
            )
            record(DriveLog.gps(location.elapsedRealtimeNanos, speed, speedAccuracy, horizontalAccuracy))
        }

        override fun onProviderEnabled(provider: String) {
            gpsEnabled = true
        }

        override fun onProviderDisabled(provider: String) {
            gpsEnabled = false
        }

        // Android 8 to 10 require this one even though it is never meaningful now.
        @Deprecated("Required on older Android versions")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satellitesUsed = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
    }

    private val publisher = object : Runnable {
        override fun run() {
            publish()
            if (running) handler.postDelayed(this, PUBLISH_INTERVAL_MS)
        }
    }

    /** Requires precise location permission to have been granted. */
    @SuppressLint("MissingPermission")
    fun start() = handler.post {
        if (running) return@post
        running = true
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
        }
        // The GPS provider directly: the fused provider smooths and delays speed.
        gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            0L,
            0f,
            locationListener,
            thread.looper,
        )
        locationManager.registerGnssStatusCallback(gnssCallback, handler)
        handler.post(publisher)
    }

    fun stop() = handler.post {
        if (!running) return@post
        running = false
        sensorManager.unregisterListener(sensorListener)
        locationManager.removeUpdates(locationListener)
        locationManager.unregisterGnssStatusCallback(gnssCallback)
        handler.removeCallbacks(publisher)
        stopRecordingNow()
        publish()
    }

    fun shutdown() {
        stop()
        thread.quitSafely()
    }

    fun calibrate() = handler.post { engine.requestCalibration() }

    fun resetPeaks() = handler.post { engine.resetPeaks() }

    fun toggleRecording() = handler.post {
        if (recorder != null) stopRecordingNow() else startRecordingNow()
    }

    private fun publish() {
        _state.value = TelemetryUiState(
            snapshot = engine.snapshot(),
            satellitesUsed = satellitesUsed,
            gpsEnabled = gpsEnabled,
            recording = recorder != null,
            recordingSeconds = if (recorder != null) (SystemClock.elapsedRealtime() - recordingStart) / 1000 else 0,
            lastRecording = lastRecording,
            recordingError = recordingError,
        )
    }

    private fun startRecordingNow() {
        recordingError = null
        try {
            val dir = context.getExternalFilesDir("recordings") ?: throw IOException("Storage unavailable")
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "drive-$stamp.csv.gz")
            val out = GZIPOutputStream(FileOutputStream(file), 64 * 1024, true).bufferedWriter()
            out.write(DriveLog.HEADER)
            out.newLine()
            recorder = out
            recordingFile = file
            recordingStart = SystemClock.elapsedRealtime()
            lastRecorderFlush = recordingStart
        } catch (e: IOException) {
            recordingError = e.message ?: "Could not start recording"
        }
    }

    private fun stopRecordingNow() {
        val out = recorder ?: return
        recorder = null
        try {
            out.close()
            lastRecording = recordingFile
        } catch (e: IOException) {
            recordingError = e.message ?: "Could not finish recording"
        }
        recordingFile = null
    }

    private fun record(line: String) {
        val out = recorder ?: return
        try {
            out.write(line)
            out.newLine()
            // Limits what a crash can lose without flushing on every sample.
            val time = SystemClock.elapsedRealtime()
            if (time - lastRecorderFlush >= RECORDER_FLUSH_INTERVAL_MS) {
                lastRecorderFlush = time
                out.flush()
            }
        } catch (e: IOException) {
            recordingError = e.message ?: "Recording failed"
            stopRecordingNow()
        }
    }

    private companion object {
        const val PUBLISH_INTERVAL_MS = 50L
        const val RECORDER_FLUSH_INTERVAL_MS = 5000L
    }
}
