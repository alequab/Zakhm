package app.zakhm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.LocaleManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope

import app.zakhm.core.SpeedInput
import app.zakhm.core.TripExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

class TelemetryViewModel(application: Application) : AndroidViewModel(application) {
    val controller = TelemetryController(application)

    private val _update = MutableStateFlow<UpdateState>(UpdateState.None)
    val update: StateFlow<UpdateState> = _update

    init {
        checkForUpdate()
        // The user can say no on Android's screen, which mustn't leave the banner stuck on "installing".
        viewModelScope.launch {
            InstallEvents.ended.collect { cancelled ->
                val current = _update.value
                if (current is UpdateState.Installing) {
                    val found = current.update ?: return@collect
                    _update.value = if (cancelled) UpdateState.Available(found) else UpdateState.Failed(found)
                }
            }
        }
    }

    /** Looks for a new release, unless one is already being handled. */
    fun checkForUpdate() {
        if (_update.value != UpdateState.None) return
        viewModelScope.launch(Dispatchers.IO) {
            val found = try {
                Updater.check(getApplication())
            } catch (_: IOException) {
                null
            }
            if (found != null && _update.value == UpdateState.None) _update.value = UpdateState.Available(found)
        }
    }

    fun startUpdate() {
        val current = _update.value
        if (current is UpdateState.Downloading || current is UpdateState.Installing) return
        val found = current.update ?: return
        val context = getApplication<Application>()
        if (!Updater.canInstall(context)) {
            _update.value = UpdateState.NeedsPermission(found)
            Updater.openInstallPermission(context)
            return
        }
        _update.value = UpdateState.Downloading(found, 0)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apk = Updater.download(context, found) { percent ->
                    _update.value = UpdateState.Downloading(found, percent)
                }
                _update.value = UpdateState.Installing(found)
                Updater.install(context, apk)
            } catch (_: IOException) {
                _update.value = UpdateState.Failed(found)
            } catch (_: RuntimeException) {
                // Android's installer can refuse a session this way.
                _update.value = UpdateState.Failed(found)
            }
        }
    }

    fun dismissUpdate() {
        _update.value = UpdateState.None
    }

    override fun onCleared() = controller.shutdown()
}

class MainActivity : ComponentActivity() {
    private val viewModel: TelemetryViewModel by viewModels()

    private var hasPermission by mutableStateOf(false)
    private var useMph by mutableStateOf(false)
    private var rotation by mutableStateOf(RotationMode.AUTO)
    private var pairedDevices by mutableStateOf(emptyList<ObdDevice>())
    private var scannedDevices by mutableStateOf(emptyList<ObdDevice>())
    private var scanning by mutableStateOf(false)
    private var needsBluetoothPermission by mutableStateOf(false)
    private var chosenLanguage by mutableStateOf<AppLanguage?>(null)
    private var gView by mutableStateOf(GView.CAR)
    private var gRange by mutableStateOf(DEFAULT_G_RANGE)

    private val preferences by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasPermission = checkPermission()
            if (hasPermission) viewModel.controller.start()
        }

    private val bluetoothRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshObdDevices() }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            val address = result.device.address
            if (scannedDevices.none { it.address == address }) {
                scannedDevices = scannedDevices + ObdDevice(name, address)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        useMph = preferences.getBoolean(KEY_MPH, false)
        rotation = RotationMode.entries.firstOrNull { it.name == preferences.getString(KEY_ROTATION, null) }
            ?: RotationMode.AUTO
        applyRotation()
        gView = GView.entries.firstOrNull { it.name == preferences.getString(KEY_G_VIEW, null) } ?: GView.CAR
        gRange = preferences.getFloat(KEY_G_RANGE, DEFAULT_G_RANGE.toFloat()).toDouble().takeIf { it in G_RANGES } ?: DEFAULT_G_RANGE

        // Reading this asks a system service, so it can't be done on every frame.
        chosenLanguage = currentLanguage()

        hasPermission = checkPermission()
        if (!hasPermission) requestPermission() else askForNotificationsOnce()
        Updater.schedule(this)

        val controller = viewModel.controller
        val actions = screenActions()
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val update by viewModel.update.collectAsStateWithLifecycle()
            // The dashboard redraws many times a second, and none of this changes with it.
            val settings = remember(useMph, rotation, pairedDevices, scannedDevices, scanning, needsBluetoothPermission, chosenLanguage, gView, gRange) {
                ScreenSettings(
                    useMph = useMph,
                    rotation = rotation,
                    obdDevices = (pairedDevices + scannedDevices).distinctBy { it.address },
                    scanningForAdapters = scanning,
                    needsBluetoothPermission = needsBluetoothPermission,
                    language = chosenLanguage,
                    gView = gView,
                    gRange = gRange,
                )
            }
            TelemetryScreen(state, update, hasPermission, settings, actions)
        }
    }

    private fun screenActions(): ScreenActions {
        val controller = viewModel.controller
        return ScreenActions(
            requestPermission = ::requestPermission,
            calibrate = { controller.calibrate() },
            resetPeaks = { controller.resetPeaks() },
            setUseMph = ::setUnits,
            setRotation = ::setRotationMode,
            setLanguage = ::setLanguage,
            setTheme = { theme, final -> controller.setTheme(theme, final) },
            selectInput = ::selectInput,
            selectObdDevice = { controller.selectObdDevice(it) },
            requestBluetooth = ::requestBluetooth,
            scanForAdapters = ::scanForAdapters,
            toggleObdConnection = { controller.toggleObdConnection() },
            selectCar = { controller.selectCar(it) },
            addCar = { controller.addCar(it) },
            renameCar = { id, name -> controller.renameCar(id, name) },
            deleteCar = { controller.deleteCar(it) },
            saveUnknownCarToCurrent = { controller.saveUnknownCarToCurrent() },
            saveUnknownCarAsNew = { controller.saveUnknownCarAsNew() },
            skipUnknownCar = { controller.skipUnknownCar() },
            toggleRecording = { controller.toggleRecording() },
            shareRecording = ::shareRecording,
            startUpdate = viewModel::startUpdate,
            dismissUpdate = viewModel::dismissUpdate,
            setGView = ::setGViewMode,
            setGRange = ::setGRangeChoice,
            shareTrip = ::shareTrip,
            deleteTrip = { controller.deleteTrip(it) },
        )
    }

    /** Tapping the update notification while the app is already open arrives here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(Updater.EXTRA_SHOW_UPDATE, false)) viewModel.checkForUpdate()
    }

    override fun onStart() {
        super.onStart()
        hasPermission = checkPermission()
        if (hasPermission) viewModel.controller.start()
        refreshObdDevices()
    }

    override fun onStop() {
        super.onStop()
        stopScan()
        viewModel.controller.stop()
    }

    private fun checkPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermission() {
        // After being refused twice, Android silently ignores the request, so the button must go to settings instead.
        val alreadyAsked = preferences.getBoolean(KEY_ASKED_LOCATION, false)
        if (alreadyAsked && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                return
            } catch (_: ActivityNotFoundException) {
                // Fall through and ask anyway.
            }
        }
        preferences.edit().putBoolean(KEY_ASKED_LOCATION, true).apply()
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions += Manifest.permission.POST_NOTIFICATIONS
        permissionRequest.launch(permissions.toTypedArray())
    }

    /** For people who allowed location before update notifications existed. Asks only once. */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (preferences.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        preferences.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
        permissionRequest.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    private fun hasBluetoothPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            BLUETOOTH_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }

    private fun requestBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) bluetoothRequest.launch(BLUETOOTH_PERMISSIONS)
    }

    @SuppressLint("MissingPermission")
    private fun refreshObdDevices() {
        val allowed = hasBluetoothPermission()
        needsBluetoothPermission = !allowed
        if (!allowed) {
            pairedDevices = emptyList()
            return
        }
        val bonded = getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
        pairedDevices = bonded.map { ObdDevice(it.name ?: it.address, it.address) }.sortedBy { it.name }
    }

    @SuppressLint("MissingPermission")
    private fun scanForAdapters() {
        if (scanning) return
        if (!hasBluetoothPermission()) return requestBluetooth()
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) {
            try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: ActivityNotFoundException) {
                // Nothing on this phone can turn it on from here.
            }
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: return
        scannedDevices = emptyList()
        try {
            scanner.startScan(scanCallback)
        } catch (_: IllegalStateException) {
            return // Bluetooth was turned off in that moment.
        }
        scanning = true
        mainHandler.postDelayed(::stopScan, SCAN_DURATION_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: IllegalStateException) {
            // Bluetooth is off, which ended the scan already.
        }
    }

    private fun selectInput(input: SpeedInput) {
        viewModel.controller.selectInput(input)
        if (input == SpeedInput.OBD) {
            if (hasBluetoothPermission()) refreshObdDevices() else requestBluetooth()
        }
    }

    private fun setUnits(mph: Boolean) {
        useMph = mph
        preferences.edit().putBoolean(KEY_MPH, mph).apply()
    }

    private fun setGViewMode(view: GView) {
        gView = view
        preferences.edit().putString(KEY_G_VIEW, view.name).apply()
    }

    private fun setGRangeChoice(range: Double) {
        gRange = range
        preferences.edit().putFloat(KEY_G_RANGE, range.toFloat()).apply()
    }

    private fun setRotationMode(mode: RotationMode) {
        rotation = mode
        preferences.edit().putString(KEY_ROTATION, mode.name).apply()
        applyRotation()
    }

    private fun applyRotation() {
        requestedOrientation = when (rotation) {
            RotationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            // The sensor variants still allow either way up, to suit how the phone is mounted.
            RotationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            RotationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /** Null below Android 13, which has no per-app language; the app then follows the phone. */
    private fun currentLanguage(): AppLanguage? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val tags = getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        return AppLanguage.entries.firstOrNull { it.tag.isNotEmpty() && tags.startsWith(it.tag) } ?: AppLanguage.PHONE
    }

    private fun setLanguage(choice: AppLanguage) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        chosenLanguage = choice
        getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(choice.tag)
    }

    private fun shareRecording(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/gzip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        startActivity(Intent.createChooser(send, getString(R.string.share_title)))
    }

    /** Writes the drive as a map file and offers it to other apps, to open or to send on. */
    private fun shareTrip(trip: SavedTrip, format: TripFormat) {
        try {
            val points = TripStore.read(trip.file)
            val text = tripText(this, trip.summary, useMph)
            val body = if (format == TripFormat.KML) TripExport.kml(points, text) else TripExport.gpx(points, text)
            val dir = File(cacheDir, "export")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException()
            // Only the newest is kept: each holds a whole drive's path.
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, trip.file.nameWithoutExtension + format.extension)
            file.writeText(body)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(format.mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Without this the share sheet itself may not read the file, and shows no name for it.
            send.clipData = ClipData.newRawUri(null, uri)
            // So map apps on this phone are offered too, not only ways to send the file.
            val open = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, format.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(send, getString(R.string.share_trip_title))
                .putExtra(Intent.EXTRA_ALTERNATE_INTENTS, arrayOf(open))
            startActivity(chooser)
        } catch (_: IOException) {
            Toast.makeText(this, R.string.drive_export_error, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val KEY_MPH = "use_mph"
        const val KEY_ROTATION = "rotation"
        const val KEY_G_VIEW = "g_view"
        const val KEY_G_RANGE = "g_range"
        const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
        const val KEY_ASKED_LOCATION = "asked_location"
        const val SCAN_DURATION_MS = 10_000L
        val BLUETOOTH_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    }
}
