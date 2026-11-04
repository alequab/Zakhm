package app.speedg

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
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
import app.speedg.car.AndroidAutoSpeed
import app.speedg.core.CarIdentity
import app.speedg.core.CarProfile
import app.speedg.core.CarRegistry
import app.speedg.core.DriveLog
import app.speedg.core.SpeedFix
import app.speedg.core.SpeedInput
import app.speedg.core.SpeedRouter
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
import java.util.UUID
import java.util.zip.GZIPOutputStream

data class TelemetryUiState(
    val snapshot: TelemetrySnapshot = TelemetrySnapshot(),
    val selectedInput: SpeedInput = SpeedInput.PHONE,
    /** Where speed is coming from right now. Differs from [selectedInput] after falling back to GPS. */
    val activeInput: SpeedInput = SpeedInput.PHONE,
    val obdState: ObdState = ObdState.IDLE,
    val obdAddress: String? = null,
    val cars: List<CarProfile> = emptyList(),
    val activeCarId: String? = null,
    /** Briefly set after the app recognizes a car by itself. */
    val carNotice: String? = null,
    val satellitesUsed: Int? = null,
    val gpsEnabled: Boolean = true,
    val recording: Boolean = false,
    val recordingSeconds: Long = 0,
    val lastRecording: File? = null,
    val recordingError: String? = null,
)

/**
 * Connects the phone's sensors and any car source to [TelemetryEngine]. Everything
 * except the public entry points runs on one background thread, so the engine
 * needs no locking and the UI thread can't delay a sensor reading.
 */
class TelemetryController(private val context: Context) {
    private val thread = HandlerThread("telemetry").apply { start() }
    private val handler = Handler(thread.looper)

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)

    private val engine = TelemetryEngine()
    private val router = SpeedRouter()
    private val _state = MutableStateFlow(TelemetryUiState())
    val state: StateFlow<TelemetryUiState> = _state

    private var running = false
    private var satellitesUsed: Int? = null
    private var gpsEnabled = true

    private val carStore = CarProfileStore(context)
    private lateinit var cars: CarRegistry
    private var carNotice: String? = null
    private var carNoticeUntil = 0L

    private var obdAddress: String? = null
    private var obdConnection: ObdConnection? = null
    private var obdState = ObdState.IDLE

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
            // GPS keeps running behind a car source, ready to take over if it goes quiet.
            if (router.active(SystemClock.elapsedRealtimeNanos() / 1e9) != SpeedInput.PHONE) return
            val speed = if (location.hasSpeed()) location.speed else null
            val speedAccuracy = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null
            val horizontalAccuracy = if (location.hasAccuracy()) location.accuracy else null
            engine.onSpeedFix(
                SpeedFix(
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

    init {
        handler.post { loadCars() }
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
        AndroidAutoSpeed.listener = { speed -> handler.post { onAndroidAutoSpeed(speed) } }
        AndroidAutoSpeed.identityListener = { car -> handler.post { onCarIdentified(car, viaObd = false) } }
        AndroidAutoSpeed.identity?.let { onCarIdentified(it, viaObd = false) }
        startObdIfSelected()
        handler.post(publisher)
    }

    fun stop() = handler.post {
        if (!running) return@post
        running = false
        sensorManager.unregisterListener(sensorListener)
        locationManager.removeUpdates(locationListener)
        locationManager.unregisterGnssStatusCallback(gnssCallback)
        AndroidAutoSpeed.listener = null
        AndroidAutoSpeed.identityListener = null
        stopObd()
        handler.removeCallbacks(publisher)
        stopRecordingNow()
        saveCars()
        publish()
    }

    fun shutdown() {
        stop()
        thread.quitSafely()
    }

    /** Chooses the active car's speed source. */
    fun selectInput(input: SpeedInput) = handler.post {
        cars.updateActive { it.copy(input = input) }
        applyActiveSource()
        saveCars()
        publish()
    }

    /** Chooses the paired adapter the active car connects through. */
    fun selectObdDevice(address: String) = handler.post {
        cars.updateActive { it.copy(input = SpeedInput.OBD, obdAddress = address) }
        applyActiveSource()
        saveCars()
        publish()
    }

    fun selectCar(id: String) = handler.post {
        captureActiveCar()
        if (cars.select(id)) applyActiveCar()
        saveCars()
        publish()
    }

    fun addCar(name: String) = handler.post {
        captureActiveCar()
        cars.add(name)
        applyActiveCar()
        saveCars()
        publish()
    }

    fun renameCar(id: String, name: String) = handler.post {
        cars.rename(id, name)
        saveCars()
        publish()
    }

    fun deleteCar(id: String) = handler.post {
        captureActiveCar()
        if (cars.delete(id)) applyActiveCar()
        saveCars()
        publish()
    }

    fun calibrate() = handler.post { engine.requestCalibration() }

    fun resetPeaks() = handler.post { engine.resetPeaks() }

    fun toggleRecording() = handler.post {
        if (recorder != null) stopRecordingNow() else startRecordingNow()
    }

    private fun loadCars() {
        val saved = carStore.load()
        cars = if (saved != null) {
            CarRegistry(saved.cars, saved.activeId, ::newCarId)
        } else {
            // Before saved cars existed, the source and adapter were plain settings.
            val old = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val input = SpeedInput.entries.firstOrNull { it.name == old.getString("speed_input", null) }
            val first = CarProfile(
                id = newCarId(),
                name = CarRegistry.DEFAULT_NAME,
                input = input ?: SpeedInput.PHONE,
                obdAddress = old.getString("obd_address", null),
            )
            CarRegistry(listOf(first), first.id, ::newCarId)
        }
        applyActiveCar()
        publish()
    }

    private fun newCarId() = UUID.randomUUID().toString()

    /** Copies what the engine has learned about the active car into its profile. */
    private fun captureActiveCar() {
        cars.updateActive { it.copy(mount = engine.mountCalibration(), peaks = engine.currentPeaks()) }
    }

    private fun saveCars() {
        captureActiveCar()
        carStore.save(cars.cars, cars.active.id)
    }

    private fun applyActiveCar() {
        val car = cars.active
        engine.loadCar(car.mount, car.peaks)
        applyActiveSource()
    }

    private fun applyActiveSource() {
        val car = cars.active
        if (router.selected == car.input && obdAddress == car.obdAddress) return
        stopObd()
        router.select(car.input)
        obdAddress = car.obdAddress
        if (running) startObdIfSelected()
    }

    private fun onCarIdentified(identity: CarIdentity, viaObd: Boolean) {
        captureActiveCar()
        val before = cars.active
        val result = cars.recognize(identity)
        // The car answered over this adapter, so that's how to reach it from now on.
        if (viaObd) cars.updateActive { it.copy(input = SpeedInput.OBD, obdAddress = obdAddress) }
        val car = cars.active
        if (car.id != before.id) applyActiveCar() else applyActiveSource()
        if (car.id != before.id || car.identityKey != before.identityKey) {
            carNotice = if (result.isNew) "New car saved: ${car.name}" else "Recognized: ${car.name}"
            carNoticeUntil = SystemClock.elapsedRealtime() + CAR_NOTICE_MS
        }
        saveCars()
        publish()
    }

    private fun startObdIfSelected() {
        val address = obdAddress
        val adapter = bluetoothManager?.adapter
        if (router.selected != SpeedInput.OBD || address == null || adapter == null) return
        obdConnection = ObdConnection(
            adapter = adapter,
            address = address,
            onState = { state -> handler.post { obdState = state } },
            onSpeed = { kmh, nanos -> handler.post { onObdSpeed(kmh, nanos) } },
            onVin = { vin -> handler.post { onCarIdentified(CarIdentity.fromVin(vin), viaObd = true) } },
        ).also { it.start() }
    }

    private fun stopObd() {
        obdConnection?.stop()
        obdConnection = null
        obdState = ObdState.IDLE
    }

    private fun onObdSpeed(kmh: Int, nanos: Long) {
        useCarSpeed(SpeedInput.OBD, kmh / 3.6f, OBD_SPEED_SIGMA, nanos)
    }

    private fun onAndroidAutoSpeed(metersPerSecond: Float) {
        useCarSpeed(SpeedInput.ANDROID_AUTO, metersPerSecond, ANDROID_AUTO_SPEED_SIGMA, SystemClock.elapsedRealtimeNanos())
    }

    private fun useCarSpeed(source: SpeedInput, metersPerSecond: Float, sigma: Float, nanos: Long) {
        if (!router.onCarFix(source, nanos / 1e9)) return
        engine.onSpeedFix(SpeedFix(nanos / 1e9, metersPerSecond.toDouble(), sigma.toDouble(), null))
        record(DriveLog.car(nanos, metersPerSecond, sigma))
    }

    private fun publish() {
        _state.value = TelemetryUiState(
            snapshot = engine.snapshot(),
            selectedInput = router.selected,
            activeInput = router.active(SystemClock.elapsedRealtimeNanos() / 1e9),
            obdState = obdState,
            obdAddress = obdAddress,
            cars = cars.cars,
            activeCarId = cars.active.id,
            carNotice = carNotice.takeIf { SystemClock.elapsedRealtime() < carNoticeUntil },
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
        const val CAR_NOTICE_MS = 6000L

        /** OBD reports whole km/h, and wheel speed carries some error of its own. */
        const val OBD_SPEED_SIGMA = 0.3f
        const val ANDROID_AUTO_SPEED_SIGMA = 0.2f
    }
}
