package app.speedg

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.speedg.core.AvailableUpdate
import app.speedg.core.CalibrationState
import app.speedg.core.SpeedSource
import app.speedg.core.TelemetrySnapshot
import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class RotationMode(val label: String) {
    AUTO("Auto"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape");

    fun next() = entries[(ordinal + 1) % entries.size]
}

class ScreenActions(
    val requestPermission: () -> Unit,
    val calibrate: () -> Unit,
    val resetPeaks: () -> Unit,
    val toggleUnits: () -> Unit,
    val cycleRotation: () -> Unit,
    val toggleRecording: () -> Unit,
    val shareRecording: (File) -> Unit,
    val downloadUpdate: (AvailableUpdate) -> Unit,
    val dismissUpdate: () -> Unit,
)

private val Background = Color(0xFF0B0F14)
private val Primary = Color(0xFFF2F5F8)
private val Secondary = Color(0xFF93A1AF)
private val Ring = Color(0xFF33404D)
private val Accent = Color(0xFFFFB300)
private val Alert = Color(0xFFFF6B5B)

private const val METER_RANGE_G = 1.0

/** Equal-width digits, so numbers don't jitter sideways as they change. */
private val TabularDigits = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun TelemetryScreen(
    state: TelemetryUiState,
    update: AvailableUpdate?,
    hasPermission: Boolean,
    useMph: Boolean,
    rotation: RotationMode,
    actions: ScreenActions,
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Background)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Background)
                .safeDrawingPadding()
                .padding(12.dp),
        ) {
            Column {
                if (update != null) UpdateBanner(update, actions)
                if (hasPermission) {
                    Dashboard(state, useMph, rotation, actions)
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
private fun Dashboard(state: TelemetryUiState, useMph: Boolean, rotation: RotationMode, actions: ScreenActions) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight
        if (isLandscape) {
            val speedSize = (maxHeight.value * 0.34f).coerceAtMost(maxWidth.value * 0.16f).sp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    StatusLine(state, useMph)
                    SpeedReadout(state.snapshot, useMph, speedSize)
                    Controls(state, useMph, rotation, actions)
                }
                Box(
                    Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true),
                    contentAlignment = Alignment.Center,
                ) {
                    GMeter(state.snapshot)
                }
                Column(
                    Modifier.width(110.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    CurrentG(state.snapshot)
                    Spacer(Modifier.height(12.dp))
                    PeaksColumn(state.snapshot, actions.resetPeaks)
                }
            }
        } else {
            val speedSize = (maxWidth.value * 0.34f).coerceAtMost(maxHeight.value * 0.17f).sp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                StatusLine(state, useMph)
                SpeedReadout(state.snapshot, useMph, speedSize)
                Box(
                    Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    GMeter(state.snapshot)
                }
                CurrentG(state.snapshot)
                Spacer(Modifier.height(4.dp))
                PeaksRow(state.snapshot, actions.resetPeaks)
                Spacer(Modifier.height(8.dp))
                Controls(state, useMph, rotation, actions)
            }
        }
    }
}

@Composable
private fun StatusLine(state: TelemetryUiState, useMph: Boolean) {
    val s = state.snapshot
    val (gpsText, gpsColor) = when {
        !state.gpsEnabled -> "Turn on Location in phone settings" to Alert
        s.speedSource == SpeedSource.ACQUIRING -> "Waiting for GPS. Needs a clear view of the sky" to Accent
        s.speedSource == SpeedSource.NO_GPS -> "No GPS signal" to Alert
        s.speedSource == SpeedSource.ESTIMATED -> "GPS lost: speed is an estimate" to Accent
        else -> gpsQuality(state, useMph) to Secondary
    }
    val (calibrationText, calibrationColor) = when (s.calibration) {
        CalibrationState.WAITING_FOR_STOP -> "Stop the car to calibrate" to Accent
        CalibrationState.LEARNING_FORWARD ->
            "Drive straight, speed up and brake gently: learning ${(s.learningProgress * 100).roundToInt()}%" to Accent
        CalibrationState.READY -> "Calibrated" to Secondary
    }
    Column {
        Text(gpsText, color = gpsColor, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(calibrationText, color = calibrationColor, fontSize = 13.sp, maxLines = 2)
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

@Composable
private fun SpeedReadout(s: TelemetrySnapshot, useMph: Boolean, fontSize: TextUnit) {
    val color = when (s.speedSource) {
        SpeedSource.ESTIMATED -> Accent
        SpeedSource.ACQUIRING, SpeedSource.NO_GPS -> Secondary
        SpeedSource.GPS, SpeedSource.STOPPED -> Primary
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = s.speedMps?.let { (it * unitFactor(useMph)).roundToInt().toString() } ?: "--",
            color = color,
            fontSize = fontSize,
            lineHeight = fontSize,
            fontWeight = FontWeight.Bold,
            style = TabularDigits,
            maxLines = 1,
        )
        Text(unitLabel(useMph), color = Secondary, fontSize = 16.sp)
    }
}

private fun unitFactor(useMph: Boolean) = if (useMph) 2.23694 else 3.6

private fun unitLabel(useMph: Boolean) = if (useMph) "mph" else "km/h"

private fun formatG(value: Double?) = value?.let { "%.2f".format(Locale.US, it) } ?: "--"

@Composable
private fun GMeter(s: TelemetrySnapshot) {
    val long = s.longitudinalG
    val lat = s.lateralG
    Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(18.dp)) {
            val radius = size.minDimension / 2
            val center = Offset(size.width / 2, size.height / 2)
            listOf(0.25, 0.5, 0.75, 1.0).forEach { g ->
                drawCircle(Ring, (radius * g / METER_RANGE_G).toFloat(), center, style = Stroke(1.5.dp.toPx()))
            }
            drawLine(Ring, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.dp.toPx())
            drawLine(Ring, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1.dp.toPx())

            if (long != null && lat != null) {
                // Left turns go left and acceleration goes up, matching the labels around the meter.
                var x = -lat / METER_RANGE_G
                var y = -long / METER_RANGE_G
                val distance = hypot(x, y)
                if (distance > 1.0) {
                    x /= distance
                    y /= distance
                }
                drawCircle(
                    Accent,
                    9.dp.toPx(),
                    Offset(center.x + (x * radius).toFloat(), center.y + (y * radius).toFloat()),
                )
            }
        }
        MeterLabel("ACCEL", Alignment.TopCenter)
        MeterLabel("BRAKE", Alignment.BottomCenter)
        MeterLabel("L", Alignment.CenterStart)
        MeterLabel("R", Alignment.CenterEnd)
        if (long == null) {
            Text("Calibrating", color = Secondary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.MeterLabel(text: String, alignment: Alignment) {
    Text(text, Modifier.align(alignment), color = Secondary, fontSize = 11.sp)
}

@Composable
private fun CurrentG(s: TelemetrySnapshot) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            formatG(s.totalG),
            color = Primary,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            style = TabularDigits,
        )
        Text(" G", color = Secondary, fontSize = 16.sp, modifier = Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun PeaksRow(s: TelemetrySnapshot, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Peak("Accel", s.peaks.accel, Modifier.weight(1f))
        Peak("Brake", s.peaks.brake, Modifier.weight(1f))
        Peak("Left", s.peaks.left, Modifier.weight(1f))
        Peak("Right", s.peaks.right, Modifier.weight(1f))
        CompactButton("Reset", onReset)
    }
}

@Composable
private fun PeaksColumn(s: TelemetrySnapshot, onReset: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Peak("Accel", s.peaks.accel, Modifier.fillMaxWidth())
        Peak("Brake", s.peaks.brake, Modifier.fillMaxWidth())
        Peak("Left", s.peaks.left, Modifier.fillMaxWidth())
        Peak("Right", s.peaks.right, Modifier.fillMaxWidth())
        CompactButton("Reset peaks", onReset, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Peak(label: String, value: Double, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(formatG(value), color = Primary, fontSize = 16.sp, style = TabularDigits)
        Text(label, color = Secondary, fontSize = 11.sp)
    }
}

@Composable
private fun Controls(state: TelemetryUiState, useMph: Boolean, rotation: RotationMode, actions: ScreenActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactButton("Calibrate", actions.calibrate, Modifier.weight(1f))
            CompactButton(if (useMph) "Use km/h" else "Use mph", actions.toggleUnits, Modifier.weight(1f))
            CompactButton("Screen: ${rotation.label}", actions.cycleRotation, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val recordLabel = if (state.recording) {
                "Stop %d:%02d".format(Locale.US, state.recordingSeconds / 60, state.recordingSeconds % 60)
            } else {
                "Record drive"
            }
            CompactButton(recordLabel, actions.toggleRecording, Modifier.weight(1f))
            val file = state.lastRecording
            if (file != null && !state.recording) {
                CompactButton("Share recording", { actions.shareRecording(file) }, Modifier.weight(1f))
            }
        }
        state.recordingError?.let { Text(it, color = Alert, fontSize = 12.sp) }
    }
}

@Composable
private fun CompactButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(text, color = Primary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
