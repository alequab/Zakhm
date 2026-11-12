package app.zakhm

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
import app.zakhm.car.AndroidAutoSpeed
import app.zakhm.core.CarIdentity
import app.zakhm.core.CarProfile
import app.zakhm.core.CarRegistry
import app.zakhm.core.CarTheme
import app.zakhm.core.DriveLog
import app.zakhm.core.FixSource
import app.zakhm.core.GpsTrust
import app.zakhm.core.SpeedFix
import app.zakhm.core.SpeedInput
import app.zakhm.core.SpeedRouter
import app.zakhm.core.TelemetryEngine
import app.zakhm.core.TelemetrySnapshot
import app.zakhm.core.TripFile
import app.zakhm.core.TripRecorder
import app.zakhm.core.Vec3
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
    /** A car that identified itself but isn't saved, waiting for the user to decide. */
    val unknownCar: CarIdentity? = null,
    val satellitesUsed: Int? = null,
    val gpsEnabled: Boolean = true,
    val recording: Boolean = false,
    val recordingSeconds: Long = 0,
    val lastRecording: File? = null,
    val recordingError: String? = null,
    /** The drives saved on this phone, newest first. */
    val trips: List<SavedTrip> = emptyList(),
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
    private var unknownCar: CarIdentity? = null
    private var unknownCarViaObd = false

    /** Cars the user said "not now" to. The adapter repeats the VIN on every reconnect, so don't ask again this run. */
    private val skippedCars = mutableSetOf<String>()

    private var obdAddress: String? = null
    private var obdConnection: ObdConnection? = null
    private var obdState = ObdState.IDLE
    private var obdSession = 0

    private var recorder: BufferedWriter? = null
    private var recordingFile: File? = null
    private var recordingStart = 0L
    private var lastRecorderFlush = 0L
    private var lastRecording: File? = null
    private var recordingError: String? = null

    /** The drive being saved for the map, alongside the raw recording. */
    private val tripStore = TripStore(File(context.filesDir, "drives"))
    private val tripRecorder = TripRecorder()
    private var tripWriter: BufferedWriter? = null
    private var tripFile: File? = null
    private var tripPoints = 0
    private var trips = emptyList<SavedTrip>()

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    engine.onAccel(event.timestamp / 1e9, Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()))
                    // Hundreds of readings a second, so only build the text when it's wanted.
                    if (recorder != null) record(DriveLog.accel(event.timestamp, v[0], v[1], v[2]))
                }
                Sensor.TYPE_GYROSCOPE -> {
                    engine.onGyro(event.timestamp / 1e9, Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()))
                    if (recorder != null) record(DriveLog.gyro(event.timestamp, v[0], v[1], v[2]))
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // GPS keeps running behind a car source, ready to take over if it goes quiet.
            val speed = if (location.hasSpeed()) location.speed else null
            val speedAccuracy = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null
            val horizontalAccuracy = if (location.hasAccuracy()) location.accuracy else null
            // Always given to the engine: behind a car source it's compared and learned from, not shown.
            engine.onSpeedFix(
                SpeedFix(
                    time = location.elapsedRealtimeNanos / 1e9,
                    speed = speed?.toDouble(),
                    speedAccuracy = speedAccuracy?.toDouble(),
                    horizontalAccuracy = horizontalAccuracy?.toDouble(),
                    source = FixSource.GPS,
                ),
            )
            record(DriveLog.gps(location.elapsedRealtimeNanos, speed, speedAccuracy, horizontalAccuracy))
            recordPosition(location)
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
        handler.post {
            trips = tripStore.list()
            loadCars()
        }
    }

    /** Requires precise location permission to have been granted. */
    @SuppressLint("MissingPermission")
    fun start() = handler.post {
        if (running) return@post
        running = true
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(sensorListener, it, SENSOR_PERIOD_US, handler)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(sensorListener, it, SENSOR_PERIOD_US, handler)
        }
        // The GPS provider directly: the fused provider smooths and delays speed.
        gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                locationListener,
                thread.looper,
            )
            locationManager.registerGnssStatusCallback(gnssCallback, handler)
        } catch (_: IllegalArgumentException) {
            // A device with no GPS. A car source can still give speed.
            gpsEnabled = false
        }
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
        satellitesUsed = null
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
        obdDisconnected = false
        cars.updateActive { it.copy(input = SpeedInput.OBD, obdAddress = address) }
        applyActiveSource()
        // Choosing the adapter it was already set to still means "connect", after a disconnect.
        if (running && obdConnection == null) startObdIfSelected()
        saveCars()
        publish()
    }

    /**
     * Lets go of the adapter, or connects again. Lasts until the app is next started,
     * so a forgotten disconnect can't leave the car's speed off for good.
     */
    fun toggleObdConnection() = handler.post {
        if (obdDisconnected) {
            obdDisconnected = false
            if (running) startObdIfSelected()
        } else {
            obdDisconnected = true
            stopObd()
            obdState = ObdState.DISCONNECTED
        }
        publish()
    }

    private var obdDisconnected = false

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

    /** Changes the active car's colours. Sliders call this constantly, so only final changes are written to storage. */
    fun setTheme(theme: CarTheme, final: Boolean) = handler.post {
        cars.updateActive { it.copy(theme = theme) }
        if (final) saveCars()
        publish()
    }

    fun calibrate() = handler.post { engine.requestCalibration() }

    fun resetPeaks() = handler.post { engine.resetPeaks() }

    fun toggleRecording() = handler.post {
        if (recorder != null) stopRecordingNow() else startRecordingNow()
    }

    fun deleteTrip(trip: SavedTrip) = handler.post {
        tripStore.delete(trip.file)
        trips = tripStore.list()
        publish()
    }

    /** What was learned about this phone's GPS. It belongs to the phone, so it is kept apart from the cars. */
    // Lazy: the background thread can reach it before the constructor gets this far.
    private val gpsTrustStore by lazy { context.getSharedPreferences("gps_trust", Context.MODE_PRIVATE) }

    private fun loadGpsTrust() {
        if (!gpsTrustStore.contains(KEY_TRUST_SAMPLES)) return
        engine.loadGpsTrust(
            GpsTrust.Saved(
                errorSquares = gpsTrustStore.getFloat(KEY_TRUST_ERRORS, 0f).toDouble(),
                reportedSquares = gpsTrustStore.getFloat(KEY_TRUST_REPORTED, 0f).toDouble(),
                samples = gpsTrustStore.getInt(KEY_TRUST_SAMPLES, 0),
            ),
        )
    }

    private fun saveGpsTrust() {
        val trust = engine.gpsTrust()
        gpsTrustStore.edit()
            .putFloat(KEY_TRUST_ERRORS, trust.errorSquares.toFloat())
            .putFloat(KEY_TRUST_REPORTED, trust.reportedSquares.toFloat())
            .putInt(KEY_TRUST_SAMPLES, trust.samples)
            .apply()
    }

    private fun loadCars() {
        loadGpsTrust()
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
        cars.updateActive { it.copy(mount = engine.mountCalibration(), peaks = engine.currentPeaks(), learned = engine.learnedCar()) }
    }

    private fun saveCars() {
        captureActiveCar()
        saveGpsTrust()
        carStore.save(cars.cars, cars.active.id)
    }

    private fun applyActiveCar() {
        val car = cars.active
        engine.loadCar(car.mount, car.peaks, car.learned)
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
        if (cars.selectKnown(identity) != null) {
            unknownCar = null
            finishIdentifying(before, viaObd, R.string.car_notice_recognized)
            return
        }
        if (identity.key !in skippedCars) {
            unknownCar = identity
            unknownCarViaObd = viaObd
        }
        publish()
    }

    /** Saves the car that's waiting to the car in use. */
    fun saveUnknownCarToCurrent() = handler.post {
        val identity = unknownCar ?: return@post
        captureActiveCar()
        val before = cars.active
        unknownCar = null
        cars.saveToActive(identity)
        finishIdentifying(before, unknownCarViaObd, R.string.car_notice_saved)
    }

    /** Saves the car that's waiting as a new car and switches to it. */
    fun saveUnknownCarAsNew() = handler.post {
        val identity = unknownCar ?: return@post
        captureActiveCar()
        val before = cars.active
        unknownCar = null
        cars.saveAsNew(identity)
        finishIdentifying(before, unknownCarViaObd, R.string.car_notice_new)
    }

    fun skipUnknownCar() = handler.post {
        unknownCar?.let { skippedCars += it.key }
        unknownCar = null
        publish()
    }

    private fun finishIdentifying(before: CarProfile, viaObd: Boolean, noticeText: Int) {
        // The car answered over this adapter, so that's how to reach it from now on.
        if (viaObd) cars.updateActive { it.copy(input = SpeedInput.OBD, obdAddress = obdAddress) }
        val car = cars.active
        if (car.id != before.id) applyActiveCar() else applyActiveSource()
        if (car.id != before.id || car.identityKey != before.identityKey) {
            carNotice = context.getString(noticeText, car.name)
            carNoticeUntil = SystemClock.elapsedRealtime() + CAR_NOTICE_MS
        }
        saveCars()
        publish()
    }

    private fun startObdIfSelected() {
        val address = obdAddress
        val adapter = bluetoothManager?.adapter
        if (router.selected != SpeedInput.OBD || address == null || adapter == null) return
        if (obdDisconnected) {
            obdState = ObdState.DISCONNECTED
            return
        }
        // A stopped connection's last messages can still be on their way, and must not act on the new choice.
        val session = ++obdSession
        fun current(action: () -> Unit) = handler.post { if (session == obdSession) action() }
        obdConnection = ObdConnection(
            context = context,
            adapter = adapter,
            address = address,
            onState = { state -> current { obdState = state } },
            onSpeed = { kmh, nanos, reading -> current { onObdSpeed(kmh, nanos, reading) } },
            onVin = { vin ->
                val name = context.getString(R.string.car_vin_name, vin.takeLast(CarIdentity.VIN_NAME_LENGTH))
                current { onCarIdentified(CarIdentity.fromVin(vin, name), viaObd = true) }
            },
        ).also { it.start() }
    }

    private fun stopObd() {
        obdSession++
        obdConnection?.stop()
        obdConnection = null
        obdState = ObdState.IDLE
    }

    private fun onObdSpeed(kmh: Double, nanos: Long, reading: ObdReading) {
        // Wheel speeds are far finer than the request's whole km/h.
        val sigma = if (reading.kind == ObdReading.Kind.STREAM) OBD_STREAM_SIGMA else OBD_SPEED_SIGMA
        useCarSpeed(SpeedInput.OBD, (kmh / 3.6).toFloat(), sigma, nanos)
    }

    private fun onAndroidAutoSpeed(metersPerSecond: Float) {
        useCarSpeed(SpeedInput.ANDROID_AUTO, metersPerSecond, ANDROID_AUTO_SPEED_SIGMA, SystemClock.elapsedRealtimeNanos())
    }

    private fun useCarSpeed(source: SpeedInput, metersPerSecond: Float, sigma: Float, nanos: Long) {
        if (!router.onCarFix(source, nanos / 1e9)) return
        engine.onSpeedFix(SpeedFix(nanos / 1e9, metersPerSecond.toDouble(), sigma.toDouble(), null, FixSource.CAR))
        record(DriveLog.car(nanos, metersPerSecond, sigma))
    }

    private fun publish() {
        val snapshot = engine.snapshot()
        if (tripWriter != null) tripRecorder.onG(snapshot.longitudinalG, snapshot.lateralG)
        _state.value = TelemetryUiState(
            snapshot = snapshot,
            selectedInput = router.selected,
            activeInput = router.active(SystemClock.elapsedRealtimeNanos() / 1e9),
            obdState = obdState,
            obdAddress = obdAddress,
            cars = cars.cars,
            activeCarId = cars.active.id,
            carNotice = carNotice.takeIf { SystemClock.elapsedRealtime() < carNoticeUntil },
            unknownCar = unknownCar,
            satellitesUsed = satellitesUsed,
            gpsEnabled = gpsEnabled,
            recording = recorder != null,
            recordingSeconds = if (recorder != null) (SystemClock.elapsedRealtime() - recordingStart) / 1000 else 0,
            lastRecording = lastRecording,
            recordingError = recordingError,
            trips = trips,
        )
    }

    private fun startRecordingNow() {
        recordingError = null
        try {
            val dir = context.getExternalFilesDir("recordings") ?: throw IOException()
            removeOldRecordings(dir)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "drive-$stamp.csv.gz")
            val out = GZIPOutputStream(FileOutputStream(file), 64 * 1024, true).bufferedWriter()
            out.write(DriveLog.HEADER)
            out.newLine()
            recorder = out
            recordingFile = file
            recordingStart = SystemClock.elapsedRealtime()
            lastRecorderFlush = recordingStart
            try {
                startTrip(stamp)
            } catch (_: IOException) {
                // The raw recording still runs; only the drive for the map is missing.
                recordingError = context.getString(R.string.recording_error_trip)
            }
        } catch (_: IOException) {
            recordingError = context.getString(R.string.recording_error_start)
        }
    }

    @Throws(IOException::class)
    private fun startTrip(stamp: String) {
        val file = tripStore.newFile(stamp)
        val out = file.bufferedWriter()
        out.write(TripFile.HEADER)
        out.newLine()
        tripWriter = out
        tripFile = file
        tripPoints = 0
    }

    /** Adds where the car is now to the drive being saved. */
    private fun recordPosition(location: Location) {
        val out = tripWriter ?: return
        // A position this vague would draw the path through buildings.
        if (location.hasAccuracy() && location.accuracy > MAX_TRIP_POSITION_ERROR_M) return
        val point = tripRecorder.onPosition(location.time, location.latitude, location.longitude, engine.snapshot().speedMps)
        try {
            out.write(TripFile.line(point))
            out.newLine()
            // About one a second, so nothing is lost if the app is closed mid-drive.
            out.flush()
            tripPoints++
        } catch (_: IOException) {
            recordingError = context.getString(R.string.recording_error_write)
            stopTrip()
        }
    }

    private fun stopTrip() {
        val out = tripWriter ?: return
        tripWriter = null
        try {
            out.close()
        } catch (_: IOException) {
            recordingError = context.getString(R.string.recording_error_finish)
        }
        // Without at least two places there is no path to show.
        if (tripPoints < 2) tripFile?.delete()
        tripFile = null
        trips = tripStore.list()
    }

    /** Raw sensor recordings are large, so only the newest are kept. */
    private fun removeOldRecordings(dir: File) {
        dir.listFiles().orEmpty()
            .sortedByDescending { it.lastModified() }
            .drop(MAX_RECORDINGS - 1)
            .forEach { it.delete() }
    }

    private fun stopRecordingNow() {
        stopTrip()
        val out = recorder ?: return
        recorder = null
        try {
            out.close()
            lastRecording = recordingFile
        } catch (_: IOException) {
            recordingError = context.getString(R.string.recording_error_finish)
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
        } catch (_: IOException) {
            recordingError = context.getString(R.string.recording_error_write)
            stopRecordingNow()
        }
    }

    private companion object {
        const val PUBLISH_INTERVAL_MS = 50L

        /** 200 readings a second is far more than a car's motion needs, and half the work of the fastest rate. */
        const val SENSOR_PERIOD_US = 5000
        const val MAX_RECORDINGS = 20
        const val RECORDER_FLUSH_INTERVAL_MS = 5000L
        const val MAX_TRIP_POSITION_ERROR_M = 50f
        const val CAR_NOTICE_MS = 6000L
        const val KEY_TRUST_ERRORS = "error_squares"
        const val KEY_TRUST_REPORTED = "reported_squares"
        const val KEY_TRUST_SAMPLES = "samples"

        /** OBD reports whole km/h, and wheel speed carries some error of its own. */
        const val OBD_SPEED_SIGMA = 0.3f
        const val OBD_STREAM_SIGMA = 0.15f

        const val ANDROID_AUTO_SPEED_SIGMA = 0.2f
    }
}
