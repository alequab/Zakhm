package app.zakhm

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.clipPath
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
import kotlin.math.ceil
import kotlin.math.hypot

/** How the G-force instrument is drawn. All show the same readings. */
enum class GStyle(@StringRes val label: Int) {
    /** The circle with the dot and its trail, and rings on round values. */
    CIRCLE(R.string.g_style_circle),

    /** A ring that fills with the push, between a braking bar and an accelerating bar. */
    RING(R.string.g_style_ring),

    /** Nested frames around the number, lit on the side the push goes, more of them the harder it is. */
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
        maxLines = 3,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
 * Nested frames around the number, lit on the side the push goes. The harder the push, the
 * more frames light, outward from the number, and the wider the lit part: the theme colour
 * first, then white, then the alert colour on the outermost.
 */
@Composable
internal fun GWindow(s: TelemetrySnapshot, view: GView, range: Double, waiting: String?, modifier: Modifier, large: Boolean) {
    val colors = LocalDash.current
    val long = s.longitudinalG
    val lat = s.lateralG
    val total = s.totalG ?: 0.0
    // The same way round as the circle: the car's movement, or the push felt in the seat.
    val sign = if (view == GView.DRIVER) -1.0 else 1.0
    val ringColors = listOf(colors.accent, colors.primary, colors.alert)
    // Left and right are the car's real directions, so they never mirror for Arabic.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.panel().padding(12.dp), contentAlignment = Alignment.Center) {
            // As wide as the panel allows, at the frames' own shape.
            val width = minOf(maxWidth, maxHeight * FRAMES_ASPECT, if (large) 560.dp else 420.dp)
            val height = width / FRAMES_ASPECT
            Box(Modifier.width(width).height(height), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val gap = size.height * FRAME_GAP_SHARE
                    val line = 2.dp.toPx()
                    fun frame(i: Int): Path {
                        // Frame 0 is the outermost; the number sits inside the last one.
                        val inset = gap * i + line
                        val w = size.width - 2 * inset
                        val h = size.height - 2 * inset
                        return Path().apply {
                            addRoundRect(
                                androidx.compose.ui.geometry.RoundRect(
                                    inset, inset, inset + w, inset + h,
                                    CornerRadius(h * FRAME_CORNER_SHARE, h * FRAME_CORNER_SHARE),
                                ),
                            )
                        }
                    }
                    for (i in 0 until FRAME_COUNT) drawPath(frame(i), colors.track, style = Stroke(line))

                    if (long != null && lat != null && total >= DIRECTION_MIN_G) {
                        // The way of the push on screen, as on the circle: x right, y down.
                        val x = (-sign * lat).toFloat()
                        val y = (-sign * long).toFloat()
                        val angle = Math.toDegrees(kotlin.math.atan2(y.toDouble(), x.toDouble())).toFloat()
                        // (The angle is taken on the G values, before the frames' width stretches it.)
                        val strength = share(total, range)
                        // Wider as the push grows, as in a hard corner the light spreads along the side.
                        val spread = MARK_MIN_DEGREES + (MARK_MAX_DEGREES - MARK_MIN_DEGREES) * strength
                        // The wedge is stretched to the frames' shape, so a mark at the top is as wide a share
                        // of the frame as one at the side.
                        val wedge = Path().apply {
                            moveTo(center.x, center.y)
                            for (step in 0..WEDGE_STEPS) {
                                val t = Math.toRadians((angle - spread / 2 + spread * step / WEDGE_STEPS).toDouble())
                                lineTo(center.x + (kotlin.math.cos(t) * size.width).toFloat(), center.y + (kotlin.math.sin(t) * size.height).toFloat())
                            }
                            close()
                        }
                        // The frames light from the number outward: the inner one at once, the others as the push grows.
                        clipPath(wedge) {
                            for (ring in 0 until LIT_FRAMES) {
                                if (strength < ring.toFloat() / LIT_FRAMES) continue
                                val path = frame(FRAME_COUNT - 2 - ring)
                                val color = ringColors[ring]
                                drawPath(path, color.copy(alpha = 0.25f), style = Stroke(gap * 0.9f))
                                drawPath(path, color, style = Stroke(gap * 0.45f, cap = StrokeCap.Round))
                            }
                        }
                    }

                    // The number's own box, the innermost frame filled in.
                    drawPath(frame(FRAME_COUNT - 1), colors.background)
                    drawPath(frame(FRAME_COUNT - 1), colors.secondary.copy(alpha = 0.6f), style = Stroke(line))
                }
                if (waiting != null) {
                    // Inside the number's box, wrapping onto two lines rather than running past its frames.
                    Box(Modifier.width(width * WAITING_WIDTH_SHARE), contentAlignment = Alignment.Center) {
                        Waiting(waiting, if (large) 15 else 12)
                    }
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            gText(s.totalG),
                            color = colors.primary,
                            fontSize = (height.value * WINDOW_NUMBER_SHARE).sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.unit_g),
                            color = colors.secondary,
                            fontSize = (height.value * WINDOW_NUMBER_SHARE * 0.5f).sp,
                            modifier = Modifier.padding(start = 3.dp, bottom = (height.value * 0.03f).dp),
                        )
                    }
                }
            }
        }
    }
}

/** Four frames: three that light, and the number's box inside them. */
private const val FRAME_COUNT = 4
private const val LIT_FRAMES = 3
private const val FRAMES_ASPECT = 2.1f
private const val FRAME_GAP_SHARE = 0.11f
private const val FRAME_CORNER_SHARE = 0.32f
private const val MARK_MIN_DEGREES = 40f
private const val MARK_MAX_DEGREES = 100f
private const val WEDGE_STEPS = 16
private const val WINDOW_NUMBER_SHARE = 0.2f

/** The number box's inner width against the frames', less some room. */
private const val WAITING_WIDTH_SHARE = 0.62f
