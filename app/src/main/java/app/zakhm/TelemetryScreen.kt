package app.zakhm

import android.app.Activity
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

import app.zakhm.core.CalibrationState
import app.zakhm.core.CarIdentity
import app.zakhm.core.CarProfile
import app.zakhm.core.CarRegistry
import app.zakhm.core.CarTheme
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

/** Which way the G-force dot moves. */
enum class GView(@StringRes val label: Int) {
    /** With the car: speeding up moves it up, a left turn moves it left. */
    CAR(R.string.g_view_car),

    /** The push felt in the seat: braking moves it up, a left turn moves it right. */
    DRIVER(R.string.g_view_driver),
}

data class ScreenSettings(
    val useMph: Boolean,
    val rotation: RotationMode,
    val obdDevices: List<ObdDevice>,
    val scanningForAdapters: Boolean,
    val needsBluetoothPermission: Boolean,
    /** Null where the phone doesn't support choosing a language per app. */
    val language: AppLanguage?,
    val gView: GView = GView.CAR,
    /** What the G circle's outer ring stands for, in G. Smaller zooms in. */
    val gRange: Double = DEFAULT_G_RANGE,
)

/** The zoom choices for the G circle; road cars rarely pass 1 G. */
val G_RANGES = listOf(0.5, 1.0, 1.5, 2.0)
const val DEFAULT_G_RANGE = 1.0

class ScreenActions(
    val requestPermission: () -> Unit,
    val calibrate: () -> Unit,
    val resetPeaks: () -> Unit,
    val setUseMph: (Boolean) -> Unit,
    val setRotation: (RotationMode) -> Unit,
    val setLanguage: (AppLanguage) -> Unit,
    /** The second value says whether the change is final and worth saving. */
    val setTheme: (CarTheme, Boolean) -> Unit,
    val selectInput: (SpeedInput) -> Unit,
    val selectObdDevice: (String) -> Unit,
    val requestBluetooth: () -> Unit,
    val scanForAdapters: () -> Unit,
    val toggleObdConnection: () -> Unit,
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
    val setGView: (GView) -> Unit = {},
    val setGRange: (Double) -> Unit = {},
    val shareTrip: (SavedTrip, TripFormat) -> Unit = { _, _ -> },
    val deleteTrip: (SavedTrip) -> Unit = {},
)


/** Equal-width digits, so numbers don't jitter sideways as they change. */
private val TabularDigits = TextStyle(fontFeatureSettings = "tnum")

private val PanelShape = RoundedCornerShape(18.dp)

private const val MAX_CAR_NAME_LENGTH = 40

/**
 * The text sizes for everything that isn't an instrument's own number, on Material's
 * scale, so the same role is the same size on every screen.
 */
internal object DashType {
    /** Captions around the instruments, section titles, and anything read at a glance. */
    val Label = 12.sp

    /** Status, hints and controls. */
    val Body = 14.sp
}

private const val FOCUS_FADE_OUT_MS = 90
private const val FOCUS_FADE_IN_MS = 180

/** How many font sizes wide three bold tabular digits are, with a little room spare. */
private const val BIG_SPEED_DIGIT_WIDTHS = 1.85f

/** The speed dial's share of the height in portrait, against 1 for the G circle. */
private const val PORTRAIT_DIAL_WEIGHT = 0.8f

/** Half a meter label's height, so the outer ring passes through the labels' middle. */
private val METER_LABEL_INSET = 8.dp
private const val HIGH_G = 0.6
private const val TRAIL_POINTS = 40
private const val GAUGE_START_DEGREES = 135f
private const val GAUGE_SWEEP_DEGREES = 270f
private const val MAX_INSTRUMENT_FONT_SCALE = 1.25f
private const val STATUS_SCROLLS = 3
private const val STATUS_SCROLL_PAUSE_MS = 2000
private val NoReadings = TelemetrySnapshot()

/** How much of the gauge's square its arc reaches down to, including the arc's glow. */
private const val GAUGE_DRAWN_HEIGHT = 0.88f

@Composable
internal fun Modifier.panel() = background(Panel, PanelShape).border(1.dp, PanelEdge, PanelShape)

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
    /** An instrument to start with full screen, for previews and tests. */
    startFocus: Focus? = null,
) {
    val theme = state.cars.firstOrNull { it.id == state.activeCarId }?.theme ?: CarTheme.DEFAULT
    DashTheme(theme) {
        val light = LocalDash.current.isLight
        val view = LocalView.current
        DisposableEffect(light) {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
            onDispose {}
        }
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
                    Dashboard(state, settings, actions, startFocus)
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
                fontSize = DashType.Body,
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
            Text(notes, color = Secondary, fontSize = DashType.Label, maxLines = 3, overflow = TextOverflow.Ellipsis)
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

/** One instrument filling the screen. */
enum class Focus { SPEED, G }

@Composable
private fun Dashboard(state: TelemetryUiState, settings: ScreenSettings, actions: ScreenActions, startFocus: Focus? = null) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var focus by rememberSaveable { mutableStateOf(startFocus) }
    val s = state.snapshot
    // The back gesture leaves the full-screen view before it leaves the app.
    BackHandler(enabled = focus != null) { focus = null }

    // Tapping an instrument fills the screen with it; tapping again brings both back.
    @Composable
    fun tapToFocus(target: Focus) = Modifier.clickable(
        onClickLabel = stringResource(if (focus == target) R.string.focus_back else R.string.focus_full),
        role = Role.Button,
    ) { focus = if (focus == target) null else target }

    // These are instruments with fixed room, so very large system text would only clip them.
    val density = LocalDensity.current
    val instrumentDensity = remember(density) { Density(density.density, min(density.fontScale, MAX_INSTRUMENT_FONT_SCALE)) }
    CompositionLocalProvider(LocalDensity provides instrumentDensity) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Full screen gives the header's room to the instrument, but keeps the status line.
            if (focus == null) Header(state, settings.useMph) { showSettings = true } else FocusStatus(state, settings.useMph)
            // A short fade through, so the change of view reads as one move. Android's
            // "remove animations" setting turns it into a cut.
            AnimatedContent(
                targetState = focus,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    fadeIn(tween(FOCUS_FADE_IN_MS, delayMillis = FOCUS_FADE_OUT_MS)) togetherWith fadeOut(tween(FOCUS_FADE_OUT_MS))
                },
                label = "focus",
            ) { shown ->
                val gap = Arrangement.spacedBy(8.dp)
                when {
                    shown == Focus.SPEED ->
                        BigSpeed(s, settings.useMph, Modifier.fillMaxSize().then(tapToFocus(Focus.SPEED)))
                    shown == Focus.G && landscape ->
                        Row(Modifier.fillMaxSize(), horizontalArrangement = gap) {
                            GMeter(s, settings.gView, settings.gRange, Modifier.weight(1f).fillMaxHeight().then(tapToFocus(Focus.G)), large = true)
                            PeakColumn(s, Modifier.width(92.dp).fillMaxHeight())
                        }
                    shown == Focus.G ->
                        Column(Modifier.fillMaxSize(), verticalArrangement = gap) {
                            GMeter(s, settings.gView, settings.gRange, Modifier.fillMaxWidth().weight(1f).then(tapToFocus(Focus.G)), large = true)
                            PeakRow(s, Modifier.fillMaxWidth().height(60.dp))
                        }
                    landscape ->
                        Row(Modifier.fillMaxSize(), horizontalArrangement = gap) {
                            SpeedDial(s, settings.useMph, Modifier.weight(1f).fillMaxHeight().then(tapToFocus(Focus.SPEED)))
                            GMeter(s, settings.gView, settings.gRange, Modifier.weight(1f).fillMaxHeight().then(tapToFocus(Focus.G)))
                            PeakColumn(s, Modifier.width(92.dp).fillMaxHeight())
                        }
                    else ->
                        Column(Modifier.fillMaxSize(), verticalArrangement = gap) {
                            // The G circle is limited by its panel's height on a phone, while the speed dial
                            // leaves room under its arc, so the circle gets the larger share.
                            SpeedDial(s, settings.useMph, Modifier.fillMaxWidth().weight(PORTRAIT_DIAL_WEIGHT).then(tapToFocus(Focus.SPEED)))
                            GMeter(s, settings.gView, settings.gRange, Modifier.fillMaxWidth().weight(1f).then(tapToFocus(Focus.G)))
                            PeakRow(s, Modifier.fillMaxWidth().height(60.dp))
                        }
                }
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
                fontSize = DashType.Label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusLine(statusText, statusColor)
        }
        RecordingBadge(state)
        IconButton(onClick = onOpenSettings, modifier = Modifier.semantics { contentDescription = settingsLabel }) {
            SlidersIcon()
        }
    }
}

/** Where the speed comes from and how good it is: the one line that must stay visible in every view. */
@Composable
private fun StatusLine(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        // Scrolls a few times if it doesn't fit, then stops, as moving text redraws the whole screen.
        Text(
            text,
            Modifier.basicMarquee(iterations = STATUS_SCROLLS, repeatDelayMillis = STATUS_SCROLL_PAUSE_MS),
            color = Primary,
            fontSize = DashType.Body,
            maxLines = 1,
        )
    }
}

@Composable
private fun RecordingBadge(state: TelemetryUiState) {
    if (!state.recording) return
    Box(Modifier.size(8.dp).background(Alert, CircleShape))
    Text(
        " %d:%02d".format(Locale.US, state.recordingSeconds / 60, state.recordingSeconds % 60),
        color = Alert,
        fontSize = DashType.Body,
        style = TabularDigits,
    )
}

/** The status alone, for full screen: an estimated or lost speed must never go unexplained. */
@Composable
private fun FocusStatus(state: TelemetryUiState, useMph: Boolean) {
    val (statusText, statusColor) = speedStatus(state, useMph)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusLine(statusText, statusColor, Modifier.weight(1f))
        RecordingBadge(state)
    }
}

@Composable
private fun SlidersIcon() {
    val line = Secondary
    val knobColor = Primary
    Canvas(Modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        listOf(0.22f to 0.68f, 0.5f to 0.3f, 0.78f to 0.6f).forEach { (y, knob) ->
            val rowY = size.height * y
            drawLine(line, Offset(0f, rowY), Offset(size.width, rowY), stroke, StrokeCap.Round)
            drawCircle(knobColor, 3.2.dp.toPx(), Offset(size.width * knob, rowY))
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
                ObdState.DISCONNECTED -> R.string.status_obd_disconnected
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

private fun formatG(value: Double?) = value?.takeIf { it.isFinite() }?.let { "%.2f".format(Locale.US, it) } ?: "--"

@Composable
private fun SpeedDial(s: TelemetrySnapshot, useMph: Boolean, modifier: Modifier) {
    val maxSpeed = if (useMph) 160 else 240
    val labelStep = if (useMph) 20 else 40
    val displayed = s.speedMps?.takeIf { it.isFinite() }?.let { it * unitFactor(useMph) }
    // Already filtered and arriving 20 times a second, so it looks smooth without animating every screen refresh.
    val fraction = ((displayed ?: 0.0) / maxSpeed).coerceIn(0.0, 1.0).toFloat()
    val currentFraction by rememberUpdatedState(fraction)
    val colors = LocalDash.current
    val valueColor = when (s.speedSource) {
        SpeedSource.ESTIMATED -> colors.accent
        SpeedSource.ACQUIRING, SpeedSource.NO_SIGNAL -> colors.secondary
        SpeedSource.LIVE, SpeedSource.STOPPED -> colors.primary
    }
    val measurer = rememberTextMeasurer()
    val face = Modifier.drawWithCache {
        val stroke = size.minDimension * 0.05f
        val radius = size.minDimension / 2 - stroke
        val center = Offset(size.width / 2, size.height / 2)
        val arcTopLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2, radius * 2)
        val labelStyle = TextStyle(color = colors.secondary, fontSize = (size.minDimension * 0.036f).toSp())
        val ticks = (0..maxSpeed step labelStep / 2).map { value ->
            val radians = Math.toRadians((GAUGE_START_DEGREES + GAUGE_SWEEP_DEGREES * value / maxSpeed).toDouble())
            val direction = Offset(cos(radians).toFloat(), sin(radians).toFloat())
            val major = value % labelStep == 0
            GaugeTick(
                from = center + direction * (radius - stroke),
                to = center + direction * (radius - stroke * (if (major) 2.1f else 1.6f)),
                major = major,
                label = if (major) measurer.measure(value.toString(), labelStyle) else null,
                labelCenter = center + direction * (radius - stroke * 3.3f),
            )
        }
        val trackStroke = Stroke(stroke, cap = StrokeCap.Round)
        val glowStroke = Stroke(stroke * 2.2f, cap = StrokeCap.Round)
        val majorWidth = 2.dp.toPx()
        val minorWidth = 1.dp.toPx()

        onDrawBehind {
            drawArc(colors.track, GAUGE_START_DEGREES, GAUGE_SWEEP_DEGREES, false, arcTopLeft, arcSize, style = trackStroke)
            if (currentFraction > 0f) {
                val sweep = GAUGE_SWEEP_DEGREES * currentFraction
                drawArc(colors.accent.copy(alpha = 0.18f), GAUGE_START_DEGREES, sweep, false, arcTopLeft, arcSize, style = glowStroke)
                drawArc(colors.accent, GAUGE_START_DEGREES, sweep, false, arcTopLeft, arcSize, style = trackStroke)
            }
            ticks.forEach { tick ->
                drawLine(
                    if (tick.major) colors.secondary else colors.track,
                    tick.from,
                    tick.to,
                    if (tick.major) majorWidth else minorWidth,
                )
                tick.label?.let { label ->
                    drawText(
                        label,
                        topLeft = Offset(
                            tick.labelCenter.x - label.size.width / 2f,
                            tick.labelCenter.y - label.size.height / 2f,
                        ),
                    )
                }
            }
        }
    }

    BoxWithConstraints(modifier.panel().padding(4.dp), contentAlignment = Alignment.Center) {
        // The circle is open at the bottom, so it can be larger than the room and let that empty part overhang.
        val side = min(maxWidth.value, maxHeight.value / GAUGE_DRAWN_HEIGHT)
        val overhang = (side - maxHeight.value).coerceAtLeast(0f) / 2
        Box(
            Modifier.offset(y = overhang.dp).requiredSize(side.dp).then(face),
            contentAlignment = Alignment.Center,
        ) {
            val density = LocalDensity.current
            val (big, small) = with(density) { (side * 0.27f).dp.toSp() to (side * 0.15f).dp.toSp() }
            SpeedNumber(displayed, valueColor, big, small, unitLabel(useMph))
        }
    }
}

private class GaugeTick(
    val from: Offset,
    val to: Offset,
    val major: Boolean,
    val label: TextLayoutResult?,
    val labelCenter: Offset,
)

@Composable
private fun SpeedNumber(value: Double?, color: Color, size: TextUnit, emptySize: TextUnit, unit: String, unitSize: TextUnit = DashType.Body) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value?.takeIf { it.isFinite() }?.roundToInt()?.toString() ?: "--",
            color = color,
            fontSize = if (value != null) size else emptySize,
            lineHeight = size,
            fontWeight = if (value != null) FontWeight.Bold else FontWeight.Normal,
            style = TabularDigits,
            maxLines = 1,
        )
        Text(unit.uppercase(Locale.US), color = Secondary, fontSize = unitSize, letterSpacing = labelSpacing(2.sp))
    }
}

/**
 * The speed alone, as big as the screen allows: a dial can never be wider than the
 * phone, but a number can grow into the height too. A bar underneath keeps the sense
 * of how far along the scale it is.
 */
@Composable
private fun BigSpeed(s: TelemetrySnapshot, useMph: Boolean, modifier: Modifier) {
    val maxSpeed = if (useMph) 160 else 240
    val displayed = s.speedMps?.takeIf { it.isFinite() }?.let { it * unitFactor(useMph) }
    val colors = LocalDash.current
    val valueColor = when (s.speedSource) {
        SpeedSource.ESTIMATED -> colors.accent
        SpeedSource.ACQUIRING, SpeedSource.NO_SIGNAL -> colors.secondary
        SpeedSource.LIVE, SpeedSource.STOPPED -> colors.primary
    }
    val fraction = ((displayed ?: 0.0) / maxSpeed).coerceIn(0.0, 1.0).toFloat()
    BoxWithConstraints(modifier.panel().padding(16.dp), contentAlignment = Alignment.Center) {
        // Three bold digits must fit across, and leave room under them for the unit and bar.
        val sizeDp = min(maxWidth.value / BIG_SPEED_DIGIT_WIDTHS, maxHeight.value * 0.55f)
        val density = LocalDensity.current
        val big = with(density) { sizeDp.dp.toSp() }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SpeedNumber(displayed, valueColor, big, big * 0.5f, unitLabel(useMph), unitSize = 20.sp)
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth(0.9f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.track),
                ) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(colors.accent))
                }
                // The ends of the scale, the same range as the dial, so the bar means something.
                // Numbers read left to right in every language, like the dial.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("0", color = Secondary, fontSize = DashType.Label, style = TabularDigits)
                        Text(maxSpeed.toString(), color = Secondary, fontSize = DashType.Label, style = TabularDigits)
                    }
                }
            }
        }
    }
}

@Composable
private fun GMeter(s: TelemetrySnapshot, view: GView, range: Double, modifier: Modifier, large: Boolean = false) {
    val long = s.longitudinalG
    val lat = s.lateralG
    val trail = remember(view, range) { mutableStateListOf<Offset>() }
    // The push on the driver is the car's acceleration turned around.
    val sign = if (view == GView.DRIVER) -1.0 else 1.0
    LaunchedEffect(long, lat, view, range) {
        if (long != null && lat != null) {
            // The dot sits next to the label for what put it there, in either view.
            trail.add(meterPoint(sign * lat, sign * long, range))
            if (trail.size > TRAIL_POINTS) trail.removeAt(0)
        } else {
            trail.clear()
        }
    }
    val total = s.totalG ?: 0.0
    val colors = LocalDash.current
    val dotColor = if (total >= HIGH_G) colors.alert else colors.accent
    val measurer = rememberTextMeasurer()
    val ringLabelStyle = TextStyle(color = colors.secondary, fontSize = DashType.Label)

    Box(modifier.panel().padding(2.dp), contentAlignment = Alignment.Center) {
        // Left and right here are the car's real directions, so they never mirror for Arabic.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true), contentAlignment = Alignment.Center) {
                // The ring runs through the middle of its labels, so it can use the panel's full size.
                Canvas(Modifier.fillMaxSize().padding(METER_LABEL_INSET)) {
                    val radius = size.minDimension / 2
                    val center = Offset(size.width / 2, size.height / 2)
                    // Rings on round values, so each can say what it stands for.
                    val rings = ringValues(range)
                    rings.forEach { g ->
                        drawCircle(
                            if (g == rings.last()) colors.secondary.copy(alpha = 0.6f) else colors.track,
                            (radius * g / range).toFloat(),
                            center,
                            style = Stroke(1.dp.toPx()),
                        )
                    }
                    drawLine(colors.track, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.dp.toPx())
                    drawLine(colors.track, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1.dp.toPx())
                    // Each ring's value where it crosses the upper-right diagonal, clear of the axis labels.
                    val diagonal = Offset(0.7071f, -0.7071f)
                    val pad = 2.dp.toPx()
                    rings.forEach { g ->
                        val text = measurer.measure(formatRange(g), ringLabelStyle)
                        val at = center + diagonal * (radius * g / range).toFloat()
                        val topLeft = Offset(at.x - text.size.width / 2f, at.y - text.size.height / 2f)
                        drawRect(
                            colors.panel,
                            topLeft - Offset(pad, 0f),
                            Size(text.size.width + 2 * pad, text.size.height.toFloat()),
                        )
                        drawText(text, topLeft = topLeft)
                    }

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
                val driver = view == GView.DRIVER
                MeterLabel(if (driver) R.string.meter_brake else R.string.meter_accel, Alignment.TopCenter)
                MeterLabel(if (driver) R.string.meter_accel else R.string.meter_brake, Alignment.BottomCenter)
                MeterLabel(if (driver) R.string.meter_right else R.string.meter_left, Alignment.CenterStart)
                MeterLabel(if (driver) R.string.meter_left else R.string.meter_right, Alignment.CenterEnd)
                // In the circle's empty middle, so the gauges keep their size when it comes and goes.
                if (long == null) {
                    Text(
                        if (s.phoneInHand) {
                            stringResource(R.string.phone_in_hand)
                        } else {
                            calibrationHint(s) ?: stringResource(R.string.calibrating)
                        },
                        Modifier.padding(horizontal = 44.dp).background(Panel).padding(4.dp),
                        color = Accent,
                        fontSize = DashType.Body,
                        textAlign = TextAlign.Center,
                        // The meter is laid out left to right, but a sentence must keep its own language's direction.
                        style = TextStyle(textDirection = TextDirection.Content),
                    )
                }
            }
        }
        Column(Modifier.align(Alignment.TopStart).padding(6.dp)) {
            // Full screen has room above the circle for a number readable at a glance.
            Text(
                formatG(s.totalG),
                color = Primary,
                fontSize = if (large) 56.sp else 26.sp,
                fontWeight = FontWeight.Bold,
                style = TabularDigits,
            )
            Text(stringResource(R.string.unit_g), color = Secondary, fontSize = if (large) 16.sp else 12.sp, letterSpacing = 2.sp)
        }
    }
}

/** Round ring values for a zoom: steps of 0.1, 0.25 or 0.5 G, ending at the outer ring. */
private fun ringValues(range: Double): List<Double> {
    val step = when {
        range <= 0.5 -> 0.1
        range <= 1.0 -> 0.25
        else -> 0.5
    }
    val count = Math.round(range / step).toInt()
    return (1..count).map { Math.round(it * step * 100) / 100.0 }
}

/** 0.1, 0.25, 0.5, 1, 1.5: as short as the value allows. */
private fun formatRange(range: Double): String =
    java.math.BigDecimal.valueOf(range).stripTrailingZeros().toPlainString()

private fun meterPoint(lateralG: Double, longitudinalG: Double, range: Double): Offset {
    var x = -lateralG / range
    var y = -longitudinalG / range
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
        // Sits on the outer ring, so it needs a clean gap in the line to stay readable.
        Modifier.align(alignment).background(Panel).padding(horizontal = 4.dp),
        color = Secondary,
        // Read at a glance from the driver's seat.
        fontSize = DashType.Label,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = labelSpacing(1.sp),
    )
}

@Composable
private fun PeakRow(s: TelemetrySnapshot, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PeakTile(R.string.peak_accel, s.peaks.accel, Modifier.weight(1f).fillMaxHeight())
        PeakTile(R.string.peak_brake, s.peaks.brake, Modifier.weight(1f).fillMaxHeight())
        PeakTile(R.string.peak_left, s.peaks.left, Modifier.weight(1f).fillMaxHeight())
        PeakTile(R.string.peak_right, s.peaks.right, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun PeakColumn(s: TelemetrySnapshot, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PeakTile(R.string.peak_accel, s.peaks.accel, Modifier.weight(1f).fillMaxWidth())
        PeakTile(R.string.peak_brake, s.peaks.brake, Modifier.weight(1f).fillMaxWidth())
        PeakTile(R.string.peak_left, s.peaks.left, Modifier.weight(1f).fillMaxWidth())
        PeakTile(R.string.peak_right, s.peaks.right, Modifier.weight(1f).fillMaxWidth())
    }
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
        Text(stringResource(label), color = Secondary, fontSize = DashType.Label, letterSpacing = labelSpacing(1.sp), maxLines = 1)
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
    // Live readings change 20 times a second, and nothing in here shows them.
    val withoutReadings = state.copy(snapshot = NoReadings, satellitesUsed = null, recordingSeconds = 0)
    val state = remember(withoutReadings) { withoutReadings }
    // Half a sheet leaves almost no room in landscape.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Panel) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            SettingsContent(state, settings, actions)
        }
    }
}

private enum class SettingsGroup { GUIDE, CAR, SOURCE, DISPLAY, G_FORCE, DRIVES, TOOLS }

private val GUIDE_STEPS = listOf(
    R.string.guide_step_mount,
    R.string.guide_step_source,
    R.string.guide_step_drive,
    R.string.guide_step_focus,
    R.string.guide_step_record,
)

/**
 * Everything in settings, as groups that open one at a time. Closed, each shows what
 * it's set to, so the whole of settings fits on one screen and most visits need no opening.
 */
@Composable
internal fun SettingsContent(
    state: TelemetryUiState,
    settings: ScreenSettings,
    actions: ScreenActions,
    /** A group to start with open, for previews and tests. */
    startOpen: String? = null,
) {
    var open by rememberSaveable { mutableStateOf(startOpen) }

    @Composable
    fun group(group: SettingsGroup, @StringRes title: Int, summary: String, content: @Composable ColumnScope.() -> Unit) {
        SettingsGroupRow(title, summary, open == group.name, { open = if (open == group.name) null else group.name }, content)
    }

    group(SettingsGroup.GUIDE, R.string.group_guide, stringResource(R.string.group_guide_summary)) {
        GUIDE_STEPS.forEachIndexed { index, step -> GuideStep(index + 1, stringResource(step)) }
        Hint(stringResource(R.string.guide_note))
    }

    val activeCar = state.cars.firstOrNull { it.id == state.activeCarId }
    group(SettingsGroup.CAR, R.string.section_car, activeCar?.let { carName(it) }.orEmpty()) {
        CarSettings(state, actions)
        ThemeSettings(activeCar?.theme ?: CarTheme.DEFAULT, actions.setTheme)
    }

    val sources = listOf(
        SpeedInput.PHONE to stringResource(R.string.source_phone),
        SpeedInput.OBD to stringResource(R.string.source_obd),
        SpeedInput.ANDROID_AUTO to stringResource(R.string.source_auto),
    )
    group(SettingsGroup.SOURCE, R.string.section_source, sources.first { it.first == state.selectedInput }.second) {
        ChoiceRow(sources, state.selectedInput, actions.selectInput)
        when (state.selectedInput) {
            SpeedInput.PHONE -> Hint(stringResource(R.string.source_hint_phone))
            SpeedInput.OBD -> ObdSettings(state, settings, actions)
            SpeedInput.ANDROID_AUTO -> Hint(stringResource(R.string.source_hint_auto))
        }
        Hint(stringResource(R.string.source_hint_general))
    }

    val unit = stringResource(if (settings.useMph) R.string.unit_mph else R.string.unit_kmh)
    group(SettingsGroup.DISPLAY, R.string.group_display, "$unit · ${stringResource(settings.rotation.label)}") {
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
    }

    val range = stringResource(R.string.g_range_choice, formatRange(settings.gRange))
    group(SettingsGroup.G_FORCE, R.string.group_g, "${stringResource(settings.gView.label)} · $range") {
        SectionTitle(R.string.section_g_view)
        ChoiceRow(GView.entries.map { it to stringResource(it.label) }, settings.gView, actions.setGView)
        Hint(stringResource(R.string.g_view_hint))
        SectionTitle(R.string.section_g_range)
        ChoiceRow(G_RANGES.map { it to stringResource(R.string.g_range_choice, formatRange(it)) }, settings.gRange, actions.setGRange)
        Hint(stringResource(R.string.g_range_hint))
        Hint(stringResource(R.string.focus_hint))
    }

    val drives = if (state.recording) {
        stringResource(R.string.drives_recording)
    } else {
        stringResource(R.string.drives_count, state.trips.size)
    }
    group(SettingsGroup.DRIVES, R.string.group_drives, drives) {
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
        state.recordingError?.let { Text(it, color = Alert, fontSize = DashType.Label) }
        TripsSection(state.trips, settings.useMph, actions.shareTrip, actions.deleteTrip)
    }

    group(SettingsGroup.TOOLS, R.string.section_tools, stringResource(R.string.tools_summary)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactButton(stringResource(R.string.tool_calibrate), actions.calibrate, Modifier.weight(1f))
            CompactButton(stringResource(R.string.tool_reset_peaks), actions.resetPeaks, Modifier.weight(1f))
        }
        Hint(stringResource(R.string.tools_hint))
    }
}

/** One group's heading, which opens and closes it, with its contents under it while open. */
@Composable
private fun SettingsGroupRow(
    @StringRes title: Int,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spoken = stringResource(if (expanded) R.string.group_open else R.string.group_closed)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = spoken }
                .heightIn(min = 56.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), color = Primary, fontSize = DashType.Body, fontWeight = FontWeight.SemiBold)
                if (summary.isNotEmpty()) {
                    Text(summary, color = Secondary, fontSize = DashType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val stroke = Secondary
            Canvas(Modifier.size(16.dp)) {
                // A chevron: down while closed, up while open.
                val tip = if (expanded) size.height * 0.3f else size.height * 0.7f
                val ends = if (expanded) size.height * 0.7f else size.height * 0.3f
                val width = Stroke(2.dp.toPx(), cap = StrokeCap.Round).width
                drawLine(stroke, Offset(size.width * 0.15f, ends), Offset(size.width / 2, tip), width, StrokeCap.Round)
                drawLine(stroke, Offset(size.width * 0.85f, ends), Offset(size.width / 2, tip), width, StrokeCap.Round)
            }
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(PanelEdge))
    }
}

@Composable
private fun GuideStep(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(number.toString(), color = Accent, fontSize = DashType.Body, fontWeight = FontWeight.SemiBold, style = TabularDigits)
        Text(text, color = Primary, fontSize = DashType.Body, modifier = Modifier.weight(1f))
    }
}

private enum class CarDialog { ADD, RENAME, DELETE }

@Composable
private fun CarSettings(state: TelemetryUiState, actions: ScreenActions) {
    var dialog by remember { mutableStateOf<CarDialog?>(null) }
    val active = state.cars.firstOrNull { it.id == state.activeCarId } ?: return
    val activeName = carName(active)

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
    // A Bluetooth LE adapter isn't paired, so it's only listed during a scan. Still show the one in use.
    val saved = state.obdAddress
    val savedIsListed = settings.obdDevices.any { it.address == saved }
    if (settings.obdDevices.isEmpty() && saved == null && !settings.scanningForAdapters) {
        Hint(stringResource(R.string.obd_none_found))
    }
    if (saved != null && !savedIsListed) {
        ChoiceChip(stringResource(R.string.obd_saved_adapter), true, Modifier.fillMaxWidth()) {}
    }
    settings.obdDevices.forEach { device ->
        ChoiceChip(device.name, device.address == state.obdAddress, Modifier.fillMaxWidth()) {
            actions.selectObdDevice(device.address)
        }
    }
    // Lets go of the adapter, as when another app needs it, until asked to connect again.
    if (saved != null) {
        val disconnected = state.obdState == ObdState.DISCONNECTED
        CompactButton(
            stringResource(if (disconnected) R.string.obd_connect else R.string.obd_disconnect),
            actions.toggleObdConnection,
            Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun SectionTitle(@StringRes text: Int) {
    Text(
        stringResource(text).uppercase(Locale.US),
        color = Secondary,
        fontSize = DashType.Label,
        letterSpacing = labelSpacing(1.5.sp),
        // Further from the section above than from its own controls, so each group reads as one.
        modifier = Modifier.padding(top = 14.dp),
    )
}

@Composable
internal fun Hint(text: String) {
    Text(text, color = Secondary, fontSize = DashType.Body)
}

@Composable
internal fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
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
            .clip(shape)
            .background(if (selected) Accent else Color.Transparent)
            .border(1.dp, if (selected) Accent else PanelEdge, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) OnAccent else Primary,
            fontSize = DashType.Body,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun CompactButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        // Android's minimum touch target, the same as the choice chips.
        modifier = modifier.heightIn(min = 48.dp),
        border = BorderStroke(1.dp, PanelEdge),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(text, fontSize = DashType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
