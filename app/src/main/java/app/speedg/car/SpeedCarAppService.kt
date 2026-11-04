package app.speedg.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.speedg.core.CarIdentity
import kotlin.math.abs

/** Android Auto's entry point. The car screen only relays speed; the phone shows the dashboard. */
class SpeedCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        return HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()
    }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = SpeedCarScreen(carContext)
    }
}

private class SpeedCarScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {
    private var listening = false
    private var receiving = false

    private val speedListener = OnCarDataAvailableListener<Speed> { speed ->
        val raw = speed.rawSpeedMetersPerSecond
        val value = raw.value
        val available = raw.status == CarValue.STATUS_SUCCESS && value != null
        if (available) AndroidAutoSpeed.publish(abs(value!!))
        if (available != receiving) {
            receiving = available
            invalidate()
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        startListening()
        identifyCar()
    }

    private fun identifyCar() {
        try {
            carInfo().fetchModel(ContextCompat.getMainExecutor(carContext)) { model ->
                val make = model.manufacturer.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value
                val name = model.name.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value
                val year = model.year.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value
                if (!make.isNullOrBlank() && !name.isNullOrBlank()) {
                    AndroidAutoSpeed.publishIdentity(CarIdentity.fromModel(make, name, year))
                }
            }
        } catch (_: SecurityException) {
            // This car doesn't let apps read its model, so the user picks the car themselves.
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        if (listening) carInfo().removeSpeedListener(speedListener)
        listening = false
    }

    private fun hasPermission() =
        carContext.checkSelfPermission(CAR_SPEED_PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun carInfo() = carContext.getCarService(CarHardwareManager::class.java).carInfo

    private fun startListening() {
        if (listening || !hasPermission()) return
        carInfo().addSpeedListener(ContextCompat.getMainExecutor(carContext), speedListener)
        listening = true
    }

    override fun onGetTemplate(): Template {
        val granted = hasPermission()
        val message = when {
            !granted -> "SpeedG needs permission to read this car's speed. Park, then allow it."
            receiving -> "Sending car speed to your phone. Keep SpeedG open on the phone to see it."
            else -> "Waiting for speed from this car. Not every car shares it."
        }
        val builder = MessageTemplate.Builder(message).setTitle("SpeedG").setHeaderAction(Action.APP_ICON)
        if (!granted) {
            builder.addAction(
                Action.Builder()
                    .setTitle("Allow")
                    .setOnClickListener(ParkedOnlyOnClickListener.create { requestPermission() })
                    .build(),
            )
        }
        return builder.build()
    }

    private fun requestPermission() {
        carContext.requestPermissions(listOf(CAR_SPEED_PERMISSION)) { granted, _ ->
            if (CAR_SPEED_PERMISSION in granted) {
                startListening()
            } else {
                CarToast.makeText(carContext, "Using the phone's GPS instead", CarToast.LENGTH_LONG).show()
            }
            invalidate()
        }
    }

    private companion object {
        const val CAR_SPEED_PERMISSION = "com.google.android.gms.permission.CAR_SPEED"
    }
}
