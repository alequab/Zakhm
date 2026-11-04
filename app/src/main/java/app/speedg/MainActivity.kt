package app.speedg

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
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

    private val preferences by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasPermission = checkPermission()
            if (hasPermission) viewModel.controller.start()
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
                useMph = useMph,
                rotation = rotation,
                actions = ScreenActions(
                    requestPermission = ::requestPermission,
                    calibrate = controller::calibrate,
                    resetPeaks = controller::resetPeaks,
                    toggleUnits = ::toggleUnits,
                    cycleRotation = ::cycleRotation,
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

    private fun toggleUnits() {
        useMph = !useMph
        preferences.edit().putBoolean(KEY_MPH, useMph).apply()
    }

    private fun cycleRotation() {
        rotation = rotation.next()
        preferences.edit().putString(KEY_ROTATION, rotation.name).apply()
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
