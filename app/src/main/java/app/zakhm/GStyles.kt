package app.zakhm

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zakhm.core.TelemetrySnapshot
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

/** How the G-force instrument is drawn. All show the same readings. */
enum class GStyle(@StringRes val label: Int) {
    /** The circle with the dot and its trail, and rings on round values. */
    CIRCLE(R.string.g_style_circle),

    /** A ring that fills with the push, between a braking bar and an accelerating bar. */
    RING(R.string.g_style_ring),

    /** A framed window with the number, a curved bar under it, and which way the push is. */
    WINDOW(R.string.g_style_window),
}

private const val PEDAL_SEGMENTS = 10
private const val RING_START_DEGREES = 135f
private const val RING_SWEEP_DEGREES = 270f

/** The number's size against the ring's width. */
private const val RING_NUMBER_SHARE = 0.2f

/** Under this the car is coasting, so no direction is shown. */
private const val DIRECTION_MIN_G = 0.05

/** From here the fill turns to the alert colour, as the circle's dot does. */
private const val HIGH_G = 0.6

private fun gText(value: Double?) = value?.takeIf { it.isFinite() }?.let { "%.2f".format(Locale.US, it) } ?: "--"

private fun share(value: Double, range: Double) = (value / range).coerceIn(0.0, 1.0).toFloat()

/** A short message in place of the number, which keeps its own language's direction. */
@Composable
private fun Waiting(text: String, size: Int) {
    Text(
        text,
        color = Accent,
        fontSize = size.sp,
        textAlign = TextAlign.Center,
        style = TextStyle(textDirection = TextDirection.Content),
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/**
 * A ring that fills with the strength of the push, a marker on it pointing the way of the
 * push, and between two segment bars: braking on one side, speeding up on the other.
 */
@Composable
internal fun GRing(s: TelemetrySnapshot, range: Double, waiting: String?, modifier: Modifier, large: Boolean) {
    val colors = LocalDash.current
    val long = s.longitudinalG
    val lat = s.lateralG
    val total = s.totalG ?: 0.0
    val fill = if (total >= HIGH_G) colors.alert else colors.accent
    // Braking and speeding up are the car's own sides, so the bars never mirror for Arabic.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.panel().padding(12.dp), contentAlignment = Alignment.Center) {
            val barWidth = if (large) 56.dp else 44.dp
            val gap = 12.dp
            // The ring as big as the panel allows, and the bars as tall as the ring, so they read as one.
            val ring = minOf(maxHeight, maxWidth - barWidth * 2 - gap * 2)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            PedalBar(share(maxOf(0.0, -(long ?: 0.0)), range), colors.alert, R.string.peak_brake, Modifier.width(barWidth).height(ring))
            Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val width = size.minDimension * 0.075f
                        val radius = size.minDimension / 2 - width
                        val topLeft = Offset(center.x - radius, center.y - radius)
                        val arcSize = Size(radius * 2, radius * 2)
                        // A faint disc in the fill colour, like a lit face behind glass.
                        drawCircle(fill.copy(alpha = 0.08f), radius - width, center)
                        drawArc(colors.track, RING_START_DEGREES, RING_SWEEP_DEGREES, false, topLeft, arcSize, style = Stroke(width, cap = StrokeCap.Round))
                        val swept = RING_SWEEP_DEGREES * share(total, range)
                        if (swept > 0f) {
                            drawArc(fill, RING_START_DEGREES, swept, false, topLeft, arcSize, style = Stroke(width, cap = StrokeCap.Round))
                        }
                        // Where the push points: up for speeding up, left for a left turn, as on the circle.
                        if (long != null && lat != null && total >= DIRECTION_MIN_G) {
                            val x = -lat
                            val y = -long
                            val length = hypot(x, y).toFloat()
                            // On the ring itself, clear of the number in the middle.
                            val at = center + Offset((x / length).toFloat(), (y / length).toFloat()) * radius
                            drawCircle(colors.background, width * 0.75f, at)
                            drawCircle(colors.primary, width * 0.55f, at)
                        }
                    }
                    if (waiting != null) {
                        Waiting(waiting, if (large) 16 else 13)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // Sized from the ring, so it always fits inside it.
                            Text(gText(s.totalG), color = colors.primary, fontSize = (ring.value * RING_NUMBER_SHARE).sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.unit_g), color = colors.secondary, fontSize = if (large) 16.sp else 12.sp, letterSpacing = 2.sp)
                        }
                    }
                }
            }
            PedalBar(share(maxOf(0.0, long ?: 0.0), range), colors.good, R.string.peak_accel, Modifier.width(barWidth).height(ring))
        }
        }
    }
}

/** A column of segments lit from the bottom, with its name under it. */
@Composable
private fun PedalBar(share: Float, color: Color, @StringRes label: Int, modifier: Modifier) {
    val colors = LocalDash.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val gap = 3.dp.toPx()
            val segment = (size.height - gap * (PEDAL_SEGMENTS - 1)) / PEDAL_SEGMENTS
            val lit = ceil(share * PEDAL_SEGMENTS).toInt()
            for (i in 0 until PEDAL_SEGMENTS) {
                val top = size.height - (i + 1) * segment - i * gap
                drawRoundRect(
                    if (i < lit) color else colors.track,
                    Offset(0f, top),
                    Size(size.width, segment),
                    CornerRadius(2.dp.toPx()),
                )
            }
        }
        Text(stringResource(label), color = colors.secondary, fontSize = DashType.Label, maxLines = 1)
    }
}

/**
 * A framed window like a steering-wheel display: which way the push is, the number, and a
 * curved bar that fills with it. Arrows at the sides light up in a turn.
 */
@Composable
internal fun GWindow(s: TelemetrySnapshot, range: Double, waiting: String?, modifier: Modifier, large: Boolean) {
    val colors = LocalDash.current
    val long = s.longitudinalG
    val lat = s.lateralG
    val total = s.totalG ?: 0.0
    val fill = if (total >= HIGH_G) colors.alert else colors.accent
    val direction = when {
        long == null || lat == null || total < DIRECTION_MIN_G -> null
        abs(long) >= abs(lat) -> if (long > 0) R.string.peak_accel else R.string.peak_brake
        else -> if (lat > 0) R.string.peak_left else R.string.peak_right
    }
    val windowShape = RoundedCornerShape(if (large) 28.dp else 20.dp)
    // Left and right are the car's real directions, so they never mirror for Arabic.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier.panel().padding(12.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                val turn = share(abs(lat ?: 0.0), range)
                SideArrow(pointsLeft = true, lit = (lat ?: 0.0) > DIRECTION_MIN_G, strength = turn, color = fill)
                Column(
                    Modifier
                        .weight(1f)
                        .widthIn(max = if (large) 460.dp else 340.dp)
                        .border(2.dp, fill.copy(alpha = 0.5f), windowShape)
                        .background(colors.background, windowShape)
                        .padding(horizontal = 16.dp, vertical = if (large) 20.dp else 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        direction?.let { stringResource(it) } ?: " ",
                        color = fill,
                        fontSize = if (large) 16.sp else DashType.Label,
                        letterSpacing = 2.sp,
                        maxLines = 1,
                    )
                    if (waiting != null) {
                        Waiting(waiting, if (large) 16 else 13)
                    } else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(gText(s.totalG), color = colors.primary, fontSize = if (large) 72.sp else 48.sp, fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.unit_g),
                                color = colors.secondary,
                                fontSize = if (large) 24.sp else 16.sp,
                                modifier = Modifier.padding(start = 4.dp, bottom = if (large) 10.dp else 6.dp),
                            )
                        }
                    }
                    CurvedBar(share(total, range), fill, Modifier.fillMaxWidth().height(if (large) 26.dp else 18.dp))
                }
                SideArrow(pointsLeft = false, lit = (lat ?: 0.0) < -DIRECTION_MIN_G, strength = turn, color = fill)
            }
        }
    }
}

/** A shallow arc under the number, filling from the left with the push. */
@Composable
private fun CurvedBar(share: Float, color: Color, modifier: Modifier) {
    val track = LocalDash.current.track
    Canvas(modifier) {
        val width = size.height * 0.35f
        val sag = size.height - width
        fun curve(until: Float) = Path().apply {
            moveTo(width / 2, width / 2)
            // A quadratic curve split at [until], so the fill follows the same bend as the track.
            val end = until.coerceIn(0f, 1f)
            val steps = 24
            for (i in 1..steps) {
                val t = end * i / steps
                val x = width / 2 + (size.width - width) * t
                val y = width / 2 + sag * 4 * t * (1 - t)
                lineTo(x, y)
            }
        }
        drawPath(curve(1f), track, style = Stroke(width, cap = StrokeCap.Round))
        if (share > 0f) drawPath(curve(share), color, style = Stroke(width, cap = StrokeCap.Round))
    }
}

/** An arrow beside the window that lights up, brighter the harder the turn. */
@Composable
private fun SideArrow(pointsLeft: Boolean, lit: Boolean, strength: Float, color: Color) {
    val track = LocalDash.current.track
    Canvas(Modifier.width(18.dp).height(28.dp)) {
        val tip = if (pointsLeft) 0f else size.width
        val back = if (pointsLeft) size.width else 0f
        val path = Path().apply {
            moveTo(tip, size.height / 2)
            lineTo(back, 0f)
            lineTo(back, size.height)
            close()
        }
        drawPath(path, if (lit) color.copy(alpha = 0.4f + 0.6f * strength) else track)
    }
}
