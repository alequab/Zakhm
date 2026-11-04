package app.speedg

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.speedg.core.AvailableUpdate
import app.speedg.core.CalibrationState
import app.speedg.core.SpeedInput
import app.speedg.core.SpeedSource
import app.speedg.core.TelemetrySnapshot
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class RotationMode(val label: String) {
    AUTO("Auto"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
}

data class ObdDevice(val name: String, val address: String)

data class ScreenSettings(
    val useMph: Boolean,
    val rotation: RotationMode,
    val obdDevices: List<ObdDevice>,
    val needsBluetoothPermission: Boolean,
)

class ScreenActions(
    val requestPermission: () -> Unit,
    val calibrate: () -> Unit,
    val resetPeaks: () -> Unit,
    val setUseMph: (Boolean) -> Unit,
    val setRotation: (RotationMode) -> Unit,
    val selectInput: (SpeedInput) -> Unit,
    val selectObdDevice: (String) -> Unit,
    val requestBluetooth: () -> Unit,
    val selectCar: (String) -> Unit,
    val addCar: (String) -> Unit,
    val renameCar: (String, String) -> Unit,
    val deleteCar: (String) -> Unit,
    val toggleRecording: () -> Unit,
    val shareRecording: (File) -> Unit,
    val downloadUpdate: (AvailableUpdate) -> Unit,
    val dismissUpdate: () -> Unit,
)

private val Background = Color(0xFF07090D)
private val Panel = Color(0xFF12161D)
private val PanelEdge = Color(0xFF232A35)
private val Primary = Color(0xFFF4F6F8)
private val Secondary = Color(0xFF8B97A5)
private val Track = Color(0xFF29313C)
private val Accent = Color(0xFFFFB020)
private val Good = Color(0xFF4CD787)
private val Alert = Color(0xFFFF5A47)

/** Equal-width digits, so numbers don't jitter sideways as they change. */
private val TabularDigits = TextStyle(fontFeatureSettings = "tnum")

private val PanelShape = RoundedCornerShape(18.dp)

private const val MAX_CAR_NAME_LENGTH = 40
private const val METER_RANGE_G = 1.0
private const val HIGH_G = 0.6
private const val TRAIL_POINTS = 40
private const val GAUGE_START_DEGREES = 135f
private const val GAUGE_SWEEP_DEGREES = 270f

private fun Modifier.panel() = background(Panel, PanelShape).border(1.dp, PanelEdge, PanelShape)

@Composable
fun TelemetryScreen(
    state: TelemetryUiState,
    update: AvailableUpdate?,
    hasPermission: Boolean,
    settings: ScreenSettings,
    actions: ScreenActions,
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Background, surface = Panel)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Background)
                .safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column {
                if (update != null) UpdateBanner(update, actions)
                if (hasPermission) {
                    Dashboard(state, settings, actions)
                } else {
                    PermissionPrompt(actions.requestPermission)
                }
            }
        }
    }
}

@Composable
private fun UpdateBanner(update: AvailableUpdate, actions: ScreenActions) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Update available: ${update.version}",
            Modifier.weight(1f),
            color = Accent,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CompactButton("Download", { actions.downloadUpdate(update) })
        CompactButton("Later", actions.dismissUpdate)
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "SpeedG needs precise location to read speed from GPS. Your location is never saved or sent anywhere.",
            color = Primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text("Allow precise location") }
    }
}

@Composable
private fun Dashboard(state: TelemetryUiState, settings: ScreenSettings, actions: ScreenActions) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val s = state.snapshot

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Header(state, settings.useMph) { showSettings = true }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeedGauge(s, settings.useMph, Modifier.weight(1f).fillMaxHeight())
                    GMeter(s, Modifier.weight(1f).fillMaxHeight())
                    Column(
                        Modifier.width(112.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PeakTile("ACCEL", s.peaks.accel, Modifier.weight(1f).fillMaxWidth())
                        PeakTile("BRAKE", s.peaks.brake, Modifier.weight(1f).fillMaxWidth())
                        PeakTile("LEFT", s.peaks.left, Modifier.weight(1f).fillMaxWidth())
                        PeakTile("RIGHT", s.peaks.right, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Header(state, settings.useMph) { showSettings = true }
                SpeedGauge(s, settings.useMph, Modifier.fillMaxWidth().weight(1.1f))
                GMeter(s, Modifier.fillMaxWidth().weight(1f))
                Row(Modifier.fillMaxWidth().height(68.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PeakTile("ACCEL", s.peaks.accel, Modifier.weight(1f).fillMaxHeight())
                    PeakTile("BRAKE", s.peaks.brake, Modifier.weight(1f).fillMaxHeight())
                    PeakTile("LEFT", s.peaks.left, Modifier.weight(1f).fillMaxHeight())
                    PeakTile("RIGHT", s.peaks.right, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(state, settings, actions) { showSettings = false }
    }
}

@Composable
private fun Header(state: TelemetryUiState, useMph: Boolean, onOpenSettings: () -> Unit) {
    val (statusText, statusColor) = speedStatus(state, useMph)
    val s = state.snapshot
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val carName = state.cars.firstOrNull { it.id == state.activeCarId }?.name
            Text(
                state.carNotice ?: carName.orEmpty(),
                color = if (state.carNotice != null) Good else Secondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(statusColor, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(statusText, color = Primary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            calibrationHint(s)?.let {
                Text(it, color = Accent, fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (state.recording) {
            Box(Modifier.size(8.dp).background(Alert, CircleShape))
            Text(
                " %d:%02d".format(Locale.US, state.recordingSeconds / 60, state.recordingSeconds % 60),
                color = Alert,
                fontSize = 13.sp,
                style = TabularDigits,
            )
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.semantics { contentDescription = "Settings" }) {
            SlidersIcon()
        }
    }
}

@Composable
private fun SlidersIcon() {
    Canvas(Modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        listOf(0.22f to 0.68f, 0.5f to 0.3f, 0.78f to 0.6f).forEach { (y, knob) ->
            val rowY = size.height * y
            drawLine(Secondary, Offset(0f, rowY), Offset(size.width, rowY), stroke, StrokeCap.Round)
            drawCircle(Primary, 3.2.dp.toPx(), Offset(size.width * knob, rowY))
        }
    }
}

private fun speedStatus(state: TelemetryUiState, useMph: Boolean): Pair<String, Color> {
    val s = state.snapshot
    val fallback = when {
        state.selectedInput == SpeedInput.PHONE || state.activeInput != SpeedInput.PHONE -> null
        state.selectedInput == SpeedInput.ANDROID_AUTO -> "Android Auto not sending speed"
        else -> when (state.obdState) {
            ObdState.IDLE -> "Choose an OBD-II adapter in settings"
            ObdState.CONNECTING -> "Connecting to OBD-II"
            ObdState.CONNECTED -> "OBD-II connected, waiting for speed"
            ObdState.CAR_NOT_RESPONDING -> "Car isn't answering OBD-II"
            ObdState.FAILED -> "Can't reach the OBD-II adapter"
        }
    }
    if (fallback != null && s.speedSource != SpeedSource.NO_SIGNAL) return "$fallback · using GPS" to Accent
    return when {
        state.activeInput == SpeedInput.OBD -> "OBD-II · car speed" to Good
        state.activeInput == SpeedInput.ANDROID_AUTO -> "Android Auto · car speed" to Good
        !state.gpsEnabled -> "Turn on Location in phone settings" to Alert
        s.speedSource == SpeedSource.ACQUIRING -> "Waiting for GPS. Needs a clear view of the sky" to Accent
        s.speedSource == SpeedSource.NO_SIGNAL -> "No speed signal" to Alert
        s.speedSource == SpeedSource.ESTIMATED -> "GPS lost: speed is an estimate" to Accent
        else -> gpsQuality(state, useMph) to Good
    }
}

private fun gpsQuality(state: TelemetryUiState, useMph: Boolean): String {
    val parts = mutableListOf("GPS")
    state.snapshot.speedSigmaMps?.let {
        parts += "±%.1f %s".format(Locale.US, it * unitFactor(useMph), unitLabel(useMph))
    }
    state.satellitesUsed?.let { parts += "$it satellites" }
    return parts.joinToString(" · ")
}

private fun calibrationHint(s: TelemetrySnapshot) = when (s.calibration) {
    CalibrationState.WAITING_FOR_STOP -> "Stop the car to calibrate"
    CalibrationState.LEARNING_FORWARD ->
        "Drive straight, speed up and brake gently: learning ${(s.learningProgress * 100).roundToInt()}%"
    CalibrationState.READY -> null
}

private fun unitFactor(useMph: Boolean) = if (useMph) 2.23694 else 3.6

private fun unitLabel(useMph: Boolean) = if (useMph) "mph" else "km/h"

private fun formatG(value: Double?) = value?.let { "%.2f".format(Locale.US, it) } ?: "--"

@Composable
private fun SpeedGauge(s: TelemetrySnapshot, useMph: Boolean, modifier: Modifier) {
    val maxSpeed = if (useMph) 160 else 240
    val labelStep = if (useMph) 20 else 40
    val displayed = s.speedMps?.let { it * unitFactor(useMph) }
    val fraction by animateFloatAsState(
        targetValue = ((displayed ?: 0.0) / maxSpeed).coerceIn(0.0, 1.0).toFloat(),
        animationSpec = tween(120),
        label = "speed",
    )
    val valueColor = when (s.speedSource) {
        SpeedSource.ESTIMATED -> Accent
        SpeedSource.ACQUIRING, SpeedSource.NO_SIGNAL -> Secondary
        SpeedSource.LIVE, SpeedSource.STOPPED -> Primary
    }
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(modifier.panel().padding(10.dp), contentAlignment = Alignment.Center) {
        val side = min(maxWidth.value, maxHeight.value)
        Box(Modifier.size(side.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.045f
                val radius = size.minDimension / 2 - stroke
                val center = Offset(size.width / 2, size.height / 2)
                val arcTopLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(radius * 2, radius * 2)

                drawArc(
                    Track,
                    GAUGE_START_DEGREES,
                    GAUGE_SWEEP_DEGREES,
                    false,
                    arcTopLeft,
                    arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                if (fraction > 0f) {
                    drawArc(
                        Accent.copy(alpha = 0.18f),
                        GAUGE_START_DEGREES,
                        GAUGE_SWEEP_DEGREES * fraction,
                        false,
                        arcTopLeft,
                        arcSize,
                        style = Stroke(stroke * 2.2f, cap = StrokeCap.Round),
                    )
                    drawArc(
                        Accent,
                        GAUGE_START_DEGREES,
                        GAUGE_SWEEP_DEGREES * fraction,
                        false,
                        arcTopLeft,
                        arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }

                for (value in 0..maxSpeed step labelStep / 2) {
                    val radians = Math.toRadians(
                        (GAUGE_START_DEGREES + GAUGE_SWEEP_DEGREES * value / maxSpeed).toDouble(),
                    )
                    val direction = Offset(cos(radians).toFloat(), sin(radians).toFloat())
                    val major = value % labelStep == 0
                    val inner = radius - stroke * (if (major) 2.1f else 1.6f)
                    drawLine(
                        if (major) Secondary else Track,
                        center + direction * (radius - stroke),
                        center + direction * inner,
                        (if (major) 2.dp else 1.dp).toPx(),
                    )
                    if (major) {
                        val label = measurer.measure(
                            value.toString(),
                            TextStyle(color = Secondary, fontSize = (size.minDimension * 0.026f).toSp()),
                        )
                        val at = center + direction * (radius - stroke * 3.6f)
                        drawText(
                            label,
                            topLeft = Offset(at.x - label.size.width / 2f, at.y - label.size.height / 2f),
                        )
                    }
                }
            }
            SpeedNumber(displayed, valueColor, (side * 0.27f).sp, (side * 0.15f).sp, unitLabel(useMph))
        }
    }
}

@Composable
private fun SpeedNumber(value: Double?, color: Color, size: TextUnit, emptySize: TextUnit, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value?.roundToInt()?.toString() ?: "--",
            color = color,
            fontSize = if (value != null) size else emptySize,
            lineHeight = size,
            fontWeight = if (value != null) FontWeight.Bold else FontWeight.Normal,
            style = TabularDigits,
            maxLines = 1,
        )
        Text(unit.uppercase(Locale.US), color = Secondary, fontSize = 13.sp, letterSpacing = 2.sp)
    }
}

@Composable
private fun GMeter(s: TelemetrySnapshot, modifier: Modifier) {
    val long = s.longitudinalG
    val lat = s.lateralG
    val trail = remember { mutableStateListOf<Offset>() }
    LaunchedEffect(long, lat) {
        if (long != null && lat != null) {
            // Left turns go left and acceleration goes up, matching the labels around the meter.
            trail.add(meterPoint(lat, long))
            if (trail.size > TRAIL_POINTS) trail.removeAt(0)
        } else {
            trail.clear()
        }
    }
    val total = s.totalG ?: 0.0
    val dotColor = if (total >= HIGH_G) Alert else Accent

    Box(modifier.panel().padding(10.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(16.dp)) {
                val radius = size.minDimension / 2
                val center = Offset(size.width / 2, size.height / 2)
                listOf(0.25, 0.5, 0.75, 1.0).forEach { g ->
                    drawCircle(
                        if (g == 1.0) Secondary.copy(alpha = 0.6f) else Track,
                        (radius * g / METER_RANGE_G).toFloat(),
                        center,
                        style = Stroke(1.dp.toPx()),
                    )
                }
                drawLine(Track, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.dp.toPx())
                drawLine(Track, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1.dp.toPx())

                trail.forEachIndexed { i, point ->
                    val age = (i + 1f) / trail.size
                    drawCircle(
                        dotColor.copy(alpha = 0.35f * age),
                        (2.dp + 3.dp * age).toPx(),
                        center + point * radius,
                    )
                }
                trail.lastOrNull()?.let { drawCircle(dotColor, 8.dp.toPx(), center + it * radius) }
            }
            MeterLabel("ACCEL", Alignment.TopCenter)
            MeterLabel("BRAKE", Alignment.BottomCenter)
            MeterLabel("L", Alignment.CenterStart)
            MeterLabel("R", Alignment.CenterEnd)
            if (long == null) Text("Calibrating", color = Secondary, fontSize = 13.sp)
        }
        Column(Modifier.align(Alignment.TopStart)) {
            Text(
                formatG(s.totalG),
                color = Primary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                style = TabularDigits,
            )
            Text("G", color = Secondary, fontSize = 12.sp, letterSpacing = 2.sp)
        }
    }
}

private fun meterPoint(lateralG: Double, longitudinalG: Double): Offset {
    var x = -lateralG / METER_RANGE_G
    var y = -longitudinalG / METER_RANGE_G
    val distance = hypot(x, y)
    if (distance > 1.0) {
        x /= distance
        y /= distance
    }
    return Offset(x.toFloat(), y.toFloat())
}

@Composable
private fun BoxScope.MeterLabel(text: String, alignment: Alignment) {
    Text(text, Modifier.align(alignment), color = Secondary, fontSize = 10.sp, letterSpacing = 1.sp)
}

@Composable
private fun PeakTile(label: String, value: Double, modifier: Modifier) {
    Column(
        modifier.panel().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            formatG(value),
            color = if (value >= HIGH_G) Alert else Primary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            style = TabularDigits,
            maxLines = 1,
        )
        Text(label, color = Secondary, fontSize = 10.sp, letterSpacing = 1.sp, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    state: TelemetryUiState,
    settings: ScreenSettings,
    actions: ScreenActions,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CarSettings(state, actions)

            SectionTitle("Speed source")
            ChoiceRow(
                listOf(SpeedInput.PHONE to "Phone GPS", SpeedInput.OBD to "OBD-II", SpeedInput.ANDROID_AUTO to "Android Auto"),
                state.selectedInput,
                actions.selectInput,
            )
            when (state.selectedInput) {
                SpeedInput.PHONE -> Hint("Speed comes from the phone's GPS and motion sensors.")
                SpeedInput.OBD -> ObdSettings(state, settings, actions)
                SpeedInput.ANDROID_AUTO -> Hint(
                    "Open SpeedG on the car screen and keep it open here too. Not every car shares its speed. " +
                        "Android Auto must allow unknown sources in its developer settings.",
                )
            }
            Hint("G-force always comes from the phone. If a car source stops, speed switches to GPS.")

            SectionTitle("Units")
            ChoiceRow(listOf(false to "km/h", true to "mph"), settings.useMph, actions.setUseMph)

            SectionTitle("Screen")
            ChoiceRow(RotationMode.entries.map { it to it.label }, settings.rotation, actions.setRotation)

            SectionTitle("Tools")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactButton("Calibrate", actions.calibrate, Modifier.weight(1f))
                CompactButton("Reset peaks", actions.resetPeaks, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactButton(if (state.recording) "Stop recording" else "Record drive", actions.toggleRecording, Modifier.weight(1f))
                val file = state.lastRecording
                if (file != null && !state.recording) {
                    CompactButton("Share recording", { actions.shareRecording(file) }, Modifier.weight(1f))
                }
            }
            state.recordingError?.let { Text(it, color = Alert, fontSize = 12.sp) }
        }
    }
}

private enum class CarDialog { ADD, RENAME, DELETE }

@Composable
private fun CarSettings(state: TelemetryUiState, actions: ScreenActions) {
    var dialog by remember { mutableStateOf<CarDialog?>(null) }
    val active = state.cars.firstOrNull { it.id == state.activeCarId } ?: return

    SectionTitle("Car")
    state.cars.forEach { car ->
        ChoiceChip(car.name, car.id == active.id, Modifier.fillMaxWidth()) { actions.selectCar(car.id) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompactButton("Add car", { dialog = CarDialog.ADD }, Modifier.weight(1f))
        CompactButton("Rename", { dialog = CarDialog.RENAME }, Modifier.weight(1f))
        if (state.cars.size > 1) {
            CompactButton("Delete", { dialog = CarDialog.DELETE }, Modifier.weight(1f))
        }
    }
    Hint("Each car keeps its own speed source, calibration and peak values.")
    Hint(
        when {
            active.identityKey?.startsWith("vin:") == true -> "Recognized automatically by its VIN over OBD-II."
            active.identityKey != null -> "Recognized automatically by its model over Android Auto."
            else -> "OBD-II or Android Auto can recognize a car automatically if it shares its VIN or model."
        },
    )

    when (dialog) {
        CarDialog.ADD -> CarNameDialog("Add a car", "", { dialog = null }, actions.addCar)
        CarDialog.RENAME -> CarNameDialog("Rename car", active.name, { dialog = null }) { name ->
            actions.renameCar(active.id, name)
        }
        CarDialog.DELETE -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete ${active.name}?") },
            text = { Text("Its saved calibration and peak values will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    actions.deleteCar(active.id)
                    dialog = null
                }) { Text("Delete", color = Alert) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        null -> Unit
    }
}

@Composable
private fun CarNameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_CAR_NAME_LENGTH) },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onConfirm(name)
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ObdSettings(state: TelemetryUiState, settings: ScreenSettings, actions: ScreenActions) {
    if (settings.needsBluetoothPermission) {
        Hint("SpeedG needs Bluetooth permission to reach your adapter.")
        CompactButton("Allow Bluetooth", actions.requestBluetooth, Modifier.fillMaxWidth())
        return
    }
    Hint("Pair a Bluetooth ELM327 adapter in Android's settings, then choose it here. Bluetooth LE adapters aren't supported.")
    if (settings.obdDevices.isEmpty()) {
        Hint("No paired Bluetooth devices found.")
    }
    settings.obdDevices.forEach { device ->
        ChoiceChip(device.name, device.address == state.obdAddress, Modifier.fillMaxWidth()) {
            actions.selectObdDevice(device.address)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(Locale.US),
        color = Secondary,
        fontSize = 11.sp,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Secondary, fontSize = 13.sp)
}

@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            ChoiceChip(label, value == selected, Modifier.weight(1f)) { onSelect(value) }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .background(if (selected) Accent else Color.Transparent, shape)
            .border(1.dp, if (selected) Accent else PanelEdge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Background else Primary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CompactButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        border = BorderStroke(1.dp, PanelEdge),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
