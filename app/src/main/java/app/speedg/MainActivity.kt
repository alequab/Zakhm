package app.speedg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import app.speedg.core.AvailableUpdate
import app.speedg.core.SpeedInput
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
    private var obdDevices by mutableStateOf(emptyList<ObdDevice>())
    private var needsBluetoothPermission by mutableStateOf(false)

    private val preferences by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasPermission = checkPermission()
            if (hasPermission) viewModel.controller.start()
        }

    private val bluetoothRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshObdDevices() }

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
                settings = ScreenSettings(useMph, rotation, obdDevices, needsBluetoothPermission),
                actions = ScreenActions(
                    requestPermission = ::requestPermission,
                    calibrate = controller::calibrate,
                    resetPeaks = controller::resetPeaks,
                    setUseMph = ::setUnits,
                    setRotation = ::setRotationMode,
                    selectInput = ::selectInput,
                    selectObdDevice = controller::selectObdDevice,
                    requestBluetooth = ::requestBluetooth,
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
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshObdDevices() {
        val allowed = hasBluetoothPermission()
        needsBluetoothPermission = !allowed
        if (!allowed) {
            obdDevices = emptyList()
            return
        }
        val bonded = getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
        obdDevices = bonded.map { ObdDevice(it.name ?: it.address, it.address) }.sortedBy { it.name }
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

    private fun downloadUpdate(update: AvailableUpdate) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.url)))
    }

    private fun shareRecording(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/gzip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share drive recording"))
    }

    private companion object {
        const val KEY_MPH = "use_mph"
        const val KEY_ROTATION = "rotation"
    }
}
