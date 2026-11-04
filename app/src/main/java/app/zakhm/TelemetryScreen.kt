package app.zakhm

import android.view.View
import androidx.annotation.StringRes
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import app.zakhm.core.CalibrationState
import app.zakhm.core.CarIdentity
import app.zakhm.core.CarProfile
import app.zakhm.core.CarRegistry
import app.zakhm.core.SpeedInput
import app.zakhm.core.SpeedSource
import app.zakhm.core.TelemetrySnapshot
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class RotationMode(@StringRes val label: Int) {
    AUTO(R.string.rotation_auto),
    PORTRAIT(R.string.rotation_portrait),
    LANDSCAPE(R.string.rotation_landscape),
}

enum class AppLanguage(val tag: String, @StringRes val label: Int) {
    PHONE("", R.string.language_system),
    ARABIC("ar", R.string.language_arabic),
    ENGLISH("en", R.string.language_english),
}

data class ObdDevice(val name: String, val address: String)

data class ScreenSettings(
    val useMph: Boolean,
    val rotation: RotationMode,
    val obdDevices: List<ObdDevice>,
    val scanningForAdapters: Boolean,
    val needsBluetoothPermission: Boolean,
    /** Null where the phone doesn't support choosing a language per app. */
    val language: AppLanguage?,
)

class ScreenActions(
    val requestPermission: () -> Unit,
    val calibrate: () -> Unit,
    val resetPeaks: () -> Unit,
    val setUseMph: (Boolean) -> Unit,
    val setRotation: (RotationMode) -> Unit,
    val setLanguage: (AppLanguage) -> Unit,
    val selectInput: (SpeedInput) -> Unit,
    val selectObdDevice: (String) -> Unit,
    val requestBluetooth: () -> Unit,
    val scanForAdapters: () -> Unit,
    val selectCar: (String) -> Unit,
    val addCar: (String) -> Unit,
    val renameCar: (String, String) -> Unit,
    val deleteCar: (String) -> Unit,
    val saveUnknownCarToCurrent: () -> Unit,
    val saveUnknownCarAsNew: () -> Unit,
    val skipUnknownCar: () -> Unit,
    val toggleRecording: () -> Unit,
    val shareRecording: (File) -> Unit,
    val startUpdate: () -> Unit,
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

/** Letter spacing for small labels. Arabic letters must stay joined, so it gets none. */
@Composable
private fun labelSpacing(spacing: TextUnit): TextUnit =
    if (LocalConfiguration.current.layoutDirection == View.LAYOUT_DIRECTION_RTL) 0.sp else spacing

/** The name to show for a car. The untouched default follows the app's language. */
@Composable
private fun carName(car: CarProfile): String =
    if (car.name == CarRegistry.DEFAULT_NAME) stringResource(R.string.car_default_name) else car.name

@Composable
fun TelemetryScreen(
    state: TelemetryUiState,
    update: UpdateState,
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
                if (update != UpdateState.None) UpdateBanner(update, actions)
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
private fun UpdateBanner(state: UpdateState, actions: ScreenActions) {
    val version = state.update?.versionName.orEmpty()
    val message = when (state) {
        is UpdateState.Downloading -> stringResource(R.string.update_downloading, version, state.percent)
        is UpdateState.Installing -> stringResource(R.string.update_installing, version)
        is UpdateState.NeedsPermission -> stringResource(R.string.update_permission)
        is UpdateState.Failed -> stringResource(R.string.update_failed)
        else -> stringResource(R.string.update_available, version)
    }
    val busy = state is UpdateState.Downloading || state is UpdateState.Installing
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                Modifier.weight(1f),
                color = if (state is UpdateState.Failed) Alert else Accent,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!busy) {
                val action = if (state is UpdateState.Failed) R.string.update_retry else R.string.update_install
                CompactButton(stringResource(action), actions.startUpdate)
                CompactButton(stringResource(R.string.update_later), actions.dismissUpdate)
            }
        }
        val notes = state.update?.notes.orEmpty()
        if (state is UpdateState.Available && notes.isNotBlank()) {
            Text(notes, color = Secondary, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
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
            stringResource(R.string.location_rationale, stringResource(R.string.app_name)),
            color = Primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text(stringResource(R.string.location_allow)) }
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
                    Zakhmauge(s, settings.useMph, Modifier.weight(1f).fillMaxHeight())
                    GMeter(s, Modifier.weight(1f).fillMaxHeight())
                    Column(
                        Modifier.width(112.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PeakTile(R.string.peak_accel, s.peaks.accel, Modifier.weight(1f).fillMaxWidth())
                        PeakTile(R.string.peak_brake, s.peaks.brake, Modifier.weight(1f).fillMaxWidth())
                        PeakTile(R.string.peak_left, s.peaks.left, Modifier.weight(1f).fillMaxWidth())
                        PeakTile(R.string.peak_right, s.peaks.right, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Header(state, settings.useMph) { showSettings = true }
                Zakhmauge(s, settings.useMph, Modifier.fillMaxWidth().weight(1.1f))
                GMeter(s, Modifier.fillMaxWidth().weight(1f))
                Row(Modifier.fillMaxWidth().height(68.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PeakTile(R.string.peak_accel, s.peaks.accel, Modifier.weight(1f).fillMaxHeight())
                    PeakTile(R.string.peak_brake, s.peaks.brake, Modifier.weight(1f).fillMaxHeight())
                    PeakTile(R.string.peak_left, s.peaks.left, Modifier.weight(1f).fillMaxHeight())
                    PeakTile(R.string.peak_right, s.peaks.right, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(state, settings, actions) { showSettings = false }
    }
    state.unknownCar?.let { UnknownCarDialog(it, state, actions) }
}

@Composable
private fun UnknownCarDialog(identity: CarIdentity, state: TelemetryUiState, actions: ScreenActions) {
    val current = state.cars.firstOrNull { it.id == state.activeCarId } ?: return
    val currentName = carName(current)
    val vin = identity.vin
    AlertDialog(
        onDismissRequest = actions.skipUnknownCar,
        title = { Text(stringResource(R.string.unknown_car_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (vin != null) {
                        stringResource(R.string.unknown_car_vin, vin)
                    } else {
                        stringResource(R.string.unknown_car_model, identity.suggestedName)
                    },
                )
                Text(stringResource(R.string.unknown_car_question, currentName))
                if (current.identityKey != null) {
                    Text(stringResource(R.string.unknown_car_replaces, currentName), color = Accent)
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = actions.saveUnknownCarToCurrent) {
                    Text(stringResource(R.string.unknown_car_save_current, currentName))
                }
                TextButton(onClick = actions.saveUnknownCarAsNew) {
                    Text(stringResource(R.string.unknown_car_save_new))
                }
                TextButton(onClick = actions.skipUnknownCar) {
                    Text(stringResource(R.string.unknown_car_skip))
                }
            }
        },
    )
}

@Composable
private fun Header(state: TelemetryUiState, useMph: Boolean, onOpenSettings: () -> Unit) {
    val (statusText, statusColor) = speedStatus(state, useMph)
    val s = state.snapshot
    val settingsLabel = stringResource(R.string.settings)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val activeCar = state.cars.firstOrNull { it.id == state.activeCarId }
            Text(
                state.carNotice ?: activeCar?.let { carName(it) }.orEmpty(),
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
        IconButton(onClick = onOpenSettings, modifier = Modifier.semantics { contentDescription = settingsLabel }) {
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

@Composable
private fun speedStatus(state: TelemetryUiState, useMph: Boolean): Pair<String, Color> {
    val s = state.snapshot
    val fallback = when {
        state.selectedInput == SpeedInput.PHONE || state.activeInput != SpeedInput.PHONE -> null
        state.selectedInput == SpeedInput.ANDROID_AUTO -> stringResource(R.string.status_auto_no_speed)
        else -> stringResource(
            when (state.obdState) {
                ObdState.IDLE -> R.string.status_obd_choose
                ObdState.CONNECTING -> R.string.status_obd_connecting
                ObdState.CONNECTED -> R.string.status_obd_waiting
                ObdState.CAR_NOT_RESPONDING -> R.string.status_obd_no_answer
                ObdState.FAILED -> R.string.status_obd_failed
            },
        )
    }
    if (fallback != null && s.speedSource != SpeedSource.NO_SIGNAL) {
        return stringResource(R.string.status_using_gps, fallback) to Accent
    }
    return when {
        state.activeInput == SpeedInput.OBD -> stringResource(R.string.status_obd_live) to Good
        state.activeInput == SpeedInput.ANDROID_AUTO -> stringResource(R.string.status_auto_live) to Good
        !state.gpsEnabled -> stringResource(R.string.status_location_off) to Alert
        s.speedSource == SpeedSource.ACQUIRING -> stringResource(R.string.status_waiting_gps) to Accent
        s.speedSource == SpeedSource.NO_SIGNAL -> stringResource(R.string.status_no_signal) to Alert
        s.speedSource == SpeedSource.ESTIMATED -> stringResource(R.string.status_gps_lost) to Accent
        else -> gpsQuality(state, useMph) to Good
    }
}

@Composable
private fun gpsQuality(state: TelemetryUiState, useMph: Boolean): String {
    val parts = mutableListOf(stringResource(R.string.status_gps))
    state.snapshot.speedSigmaMps?.let {
        val value = "%.1f".format(Locale.US, it * unitFactor(useMph))
        parts += stringResource(R.string.status_accuracy, value, unitLabel(useMph))
    }
    state.satellitesUsed?.let { parts += stringResource(R.string.status_satellites, it) }
    return parts.joinToString(" · ")
}

@Composable
private fun calibrationHint(s: TelemetrySnapshot): String? = when (s.calibration) {
    CalibrationState.WAITING_FOR_STOP -> stringResource(R.string.calibrate_stop)
    CalibrationState.LEARNING_FORWARD ->
        stringResource(R.string.calibrate_learning, (s.learningProgress * 100).roundToInt())
    CalibrationState.READY -> null
}

private fun unitFactor(useMph: Boolean) = if (useMph) 2.23694 else 3.6

@Composable
private fun unitLabel(useMph: Boolean) = stringResource(if (useMph) R.string.unit_mph else R.string.unit_kmh)

private fun formatG(value: Double?) = value?.let { "%.2f".format(Locale.US, it) } ?: "--"

@Composable
private fun Zakhmauge(s: TelemetrySnapshot, useMph: Boolean, modifier: Modifier) {
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
        Text(unit.uppercase(Locale.US), color = Secondary, fontSize = 13.sp, letterSpacing = labelSpacing(2.sp))
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
        // Left and right here are the car's real directions, so they never mirror for Arabic.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(18.dp)) {
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
                MeterLabel(R.string.meter_accel, Alignment.TopCenter)
                MeterLabel(R.string.meter_brake, Alignment.BottomCenter)
                MeterLabel(R.string.meter_left, Alignment.CenterStart)
                MeterLabel(R.string.meter_right, Alignment.CenterEnd)
                if (long == null) Text(stringResource(R.string.calibrating), color = Secondary, fontSize = 13.sp)
            }
        }
        Column(Modifier.align(Alignment.TopStart)) {
            Text(
                formatG(s.totalG),
                color = Primary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                style = TabularDigits,
            )
            Text(stringResource(R.string.unit_g), color = Secondary, fontSize = 12.sp, letterSpacing = 2.sp)
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
private fun BoxScope.MeterLabel(@StringRes text: Int, alignment: Alignment) {
    Text(
        stringResource(text),
        Modifier.align(alignment),
        color = Secondary,
        fontSize = 10.sp,
        letterSpacing = labelSpacing(1.sp),
    )
}

@Composable
private fun PeakTile(@StringRes label: Int, value: Double, modifier: Modifier) {
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
        Text(stringResource(label), color = Secondary, fontSize = 10.sp, letterSpacing = labelSpacing(1.sp), maxLines = 1)
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

            SectionTitle(R.string.section_source)
            ChoiceRow(
                listOf(
                    SpeedInput.PHONE to stringResource(R.string.source_phone),
                    SpeedInput.OBD to stringResource(R.string.source_obd),
                    SpeedInput.ANDROID_AUTO to stringResource(R.string.source_auto),
                ),
                state.selectedInput,
                actions.selectInput,
            )
            when (state.selectedInput) {
                SpeedInput.PHONE -> Hint(stringResource(R.string.source_hint_phone))
                SpeedInput.OBD -> ObdSettings(state, settings, actions)
                SpeedInput.ANDROID_AUTO -> Hint(stringResource(R.string.source_hint_auto))
            }
            Hint(stringResource(R.string.source_hint_general))

            SectionTitle(R.string.section_units)
            ChoiceRow(
                listOf(false to stringResource(R.string.unit_kmh), true to stringResource(R.string.unit_mph)),
                settings.useMph,
                actions.setUseMph,
            )

            SectionTitle(R.string.section_screen)
            ChoiceRow(RotationMode.entries.map { it to stringResource(it.label) }, settings.rotation, actions.setRotation)

            settings.language?.let { language ->
                SectionTitle(R.string.section_language)
                ChoiceRow(AppLanguage.entries.map { it to stringResource(it.label) }, language, actions.setLanguage)
            }

            SectionTitle(R.string.section_tools)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactButton(stringResource(R.string.tool_calibrate), actions.calibrate, Modifier.weight(1f))
                CompactButton(stringResource(R.string.tool_reset_peaks), actions.resetPeaks, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactButton(
                    stringResource(if (state.recording) R.string.tool_stop_recording else R.string.tool_record),
                    actions.toggleRecording,
                    Modifier.weight(1f),
                )
                val file = state.lastRecording
                if (file != null && !state.recording) {
                    CompactButton(
                        stringResource(R.string.tool_share_recording),
                        { actions.shareRecording(file) },
                        Modifier.weight(1f),
                    )
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
    val activeName = carName(active)

    SectionTitle(R.string.section_car)
    state.cars.forEach { car ->
        ChoiceChip(carName(car), car.id == active.id, Modifier.fillMaxWidth()) { actions.selectCar(car.id) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompactButton(stringResource(R.string.car_add), { dialog = CarDialog.ADD }, Modifier.weight(1f))
        CompactButton(stringResource(R.string.car_rename), { dialog = CarDialog.RENAME }, Modifier.weight(1f))
        if (state.cars.size > 1) {
            CompactButton(stringResource(R.string.car_delete), { dialog = CarDialog.DELETE }, Modifier.weight(1f))
        }
    }
    Hint(stringResource(R.string.car_hint_saved))
    Hint(
        stringResource(
            when {
                CarIdentity.vinOf(active.identityKey) != null -> R.string.car_hint_vin
                active.identityKey != null -> R.string.car_hint_model
                else -> R.string.car_hint_unknown
            },
        ),
    )
    CarIdentity.vinOf(active.identityKey)?.let { Hint(stringResource(R.string.car_vin, it)) }

    when (dialog) {
        CarDialog.ADD -> CarNameDialog(stringResource(R.string.car_add_title), "", { dialog = null }, actions.addCar)
        CarDialog.RENAME -> CarNameDialog(stringResource(R.string.car_rename_title), activeName, { dialog = null }) { name ->
            actions.renameCar(active.id, name)
        }
        CarDialog.DELETE -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.car_delete_title, activeName)) },
            text = { Text(stringResource(R.string.car_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    actions.deleteCar(active.id)
                    dialog = null
                }) { Text(stringResource(R.string.car_delete), color = Alert) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
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
                label = { Text(stringResource(R.string.car_name_label)) },
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
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ObdSettings(state: TelemetryUiState, settings: ScreenSettings, actions: ScreenActions) {
    if (settings.needsBluetoothPermission) {
        Hint(stringResource(R.string.obd_needs_permission))
        CompactButton(stringResource(R.string.obd_allow_bluetooth), actions.requestBluetooth, Modifier.fillMaxWidth())
        return
    }
    Hint(stringResource(R.string.obd_hint))
    CompactButton(
        stringResource(if (settings.scanningForAdapters) R.string.obd_scanning else R.string.obd_scan),
        actions.scanForAdapters,
        Modifier.fillMaxWidth(),
    )
    if (settings.obdDevices.isEmpty() && !settings.scanningForAdapters) {
        Hint(stringResource(R.string.obd_none_found))
    }
    settings.obdDevices.forEach { device ->
        ChoiceChip(device.name, device.address == state.obdAddress, Modifier.fillMaxWidth()) {
            actions.selectObdDevice(device.address)
        }
    }
}

@Composable
private fun SectionTitle(@StringRes text: Int) {
    Text(
        stringResource(text).uppercase(Locale.US),
        color = Secondary,
        fontSize = 11.sp,
        letterSpacing = labelSpacing(1.5.sp),
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
