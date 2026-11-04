package app.zakhm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.LocaleManager
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.zakhm.core.AvailableUpdate
import app.zakhm.core.SpeedInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class TelemetryViewModel(application: Application) : AndroidViewModel(application) {
    val controller = TelemetryController(application)

    private val _update = MutableStateFlow<AvailableUpdate?>(null)
    val update: StateFlow<AvailableUpdate?> = _update

    init {
        viewModelScope.launch(Dispatchers.IO) { _update.value = UpdateChecker.check() }
    }

    fun dismissUpdate() {
        _update.value = null
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

        hasPermission = checkPermission()
        if (!hasPermission) requestPermission()

        val controller = viewModel.controller
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val update by viewModel.update.collectAsStateWithLifecycle()
            TelemetryScreen(
                state = state,
                update = update,
                hasPermission = hasPermission,
                settings = ScreenSettings(
                    useMph = useMph,
                    rotation = rotation,
                    obdDevices = (pairedDevices + scannedDevices).distinctBy { it.address },
                    scanningForAdapters = scanning,
                    needsBluetoothPermission = needsBluetoothPermission,
                    language = currentLanguage(),
                ),
                actions = ScreenActions(
                    requestPermission = ::requestPermission,
                    calibrate = controller::calibrate,
                    resetPeaks = controller::resetPeaks,
                    setUseMph = ::setUnits,
                    setRotation = ::setRotationMode,
                    setLanguage = ::setLanguage,
                    selectInput = ::selectInput,
                    selectObdDevice = controller::selectObdDevice,
                    requestBluetooth = ::requestBluetooth,
                    scanForAdapters = ::scanForAdapters,
                    selectCar = controller::selectCar,
                    addCar = controller::addCar,
                    renameCar = controller::renameCar,
                    deleteCar = controller::deleteCar,
                    toggleRecording = controller::toggleRecording,
                    shareRecording = ::shareRecording,
                    downloadUpdate = ::downloadUpdate,
                    dismissUpdate = viewModel::dismissUpdate,
                ),
            )
        }
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
        permissionRequest.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
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
        val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        scannedDevices = emptyList()
        scanning = true
        scanner.startScan(scanCallback)
        mainHandler.postDelayed(::stopScan, SCAN_DURATION_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        mainHandler.removeCallbacksAndMessages(null)
        getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
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

    private fun setLanguage(language: AppLanguage) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
    }

    private fun downloadUpdate(update: AvailableUpdate) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.url)))
    }

    private fun shareRecording(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/gzip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share_title)))
    }

    private companion object {
        const val KEY_MPH = "use_mph"
        const val KEY_ROTATION = "rotation"
        const val SCAN_DURATION_MS = 10_000L
        val BLUETOOTH_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    }
}
